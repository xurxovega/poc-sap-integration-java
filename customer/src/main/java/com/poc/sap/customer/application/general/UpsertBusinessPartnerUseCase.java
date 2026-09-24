package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.security.AccessScope;
import com.poc.sap.customer.domain.BusinessPartnerUpsertException;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;

import java.util.EnumSet;
import java.util.Set;

/**
 * Use case de upsert manual de Business Partner (PRD-10,
 * spec {@code docs/sdd/customer/upsert-business-partner-manual.md}).
 *
 * <p>Traduce un body REST (PUT o PATCH) en una llamada al orquestador general
 * con el {@code Customer} construido desde el payload, en vez de releerlo del
 * legacy. La regla de seguridad (rol WRITE), el manejo de
 * {@code ConcurrentTransitionException} y el mapeo del body a HTTP son
 * responsabilidad del controller; este use case solo valida reglas de
 * aplicacion y construye el snapshot.
 *
 * <p>No tiene dependencias de Spring ni de HTTP: el controller lo invoca y
 * captura las excepciones que traduce a codigos HTTP. El
 * {@link AccessScope} se inyecta por simetria con el resto de use cases
 * pero aqui no se consulta: el enmascarado PII es responsabilidad del
 * controller y aplica solo a lecturas (PRD-9); este flujo es de escritura
 * y solo lo llama un WRITE.
 */
public class UpsertBusinessPartnerUseCase {

    /** Categoria OData por defecto para clientes (Organization, BP cat 2). */
    private static final String DEFAULT_CUSTOMER_CATEGORY = "2";

    private final SyncCustomerUseCase syncCustomerUseCase;

    public UpsertBusinessPartnerUseCase(SyncCustomerUseCase syncCustomerUseCase) {
        this.syncCustomerUseCase = syncCustomerUseCase;
    }

    /**
     * PUT: alta o actualizacion completa. Body obligatorio con
     * {@code name}; {@code category} opcional (default {@code "2"}).
     *
     * <p>El orquestador se invoca con set de features vacio: este flujo
     * escribe solo el Business Partner (nombre y categoria en el alta). Las
     * subentidades ADDRESS/FISCAL/CONTACT/BANKING se siguen sincronizando
     * por su propio camino ({@code POST /customers/sync}); el SAP upsert
     * con lookup + PATCH solo toca el agregado.
     *
     * @throws BusinessPartnerUpsertException si el body no es valido.
     */
    public SyncState put(String id,
                         BusinessPartnerPutRequest req,
                         AccessScope scope) {
        validateId(id);
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new BusinessPartnerUpsertException(
                    BusinessPartnerUpsertException.Kind.InvalidPayload,
                    "name obligatorio");
        }
        String category = (req.category() == null || req.category().isBlank())
                ? DEFAULT_CUSTOMER_CATEGORY : req.category();
        Customer customer = buildCustomer(id, req.name(), category);
        IngestionMessage msg = message(id);
        return syncCustomerUseCase.executeFromPayload(msg, customer, noFeatures());
    }

    /**
     * PATCH: actualizacion parcial. Body con al menos un campo
     * ({@code name} o {@code category}); ambos son opcionales pero
     * {@code category} se rechaza por R-1 (el adapter solo soporta name en
     * PATCH). El {@link AccessScope} se acepta por consistencia con
     * {@link #put} y con el resto de casos de uso del modulo.
     *
     * @throws BusinessPartnerUpsertException si el body no es valido
     *         (sin campos, o con {@code category}).
     */
    public SyncState patch(String id,
                           BusinessPartnerPatchRequest req,
                           AccessScope scope) {
        validateId(id);
        if (req == null) {
            throw new BusinessPartnerUpsertException(
                    BusinessPartnerUpsertException.Kind.MandatoryFieldMissing,
                    "al menos un campo: name, category");
        }
        boolean hasName = req.name() != null && !req.name().isBlank();
        boolean hasCategory = req.category() != null && !req.category().isBlank();
        if (!hasName && !hasCategory) {
            throw new BusinessPartnerUpsertException(
                    BusinessPartnerUpsertException.Kind.MandatoryFieldMissing,
                    "al menos un campo: name, category");
        }
        // R-1: PATCH no actualiza category (BusinessPartnerODataAdapter.update
        // solo cubre OrganizationBPName1). Si el caller quiere cambiar la
        // categoria tiene que ir por PUT, que es upsert completo.
        if (hasCategory) {
            throw new BusinessPartnerUpsertException(
                    BusinessPartnerUpsertException.Kind.InvalidPayload,
                    "category no se puede modificar por PATCH en esta version");
        }
        Customer customer = buildCustomer(id, req.name(), DEFAULT_CUSTOMER_CATEGORY);
        IngestionMessage msg = message(id);
        return syncCustomerUseCase.executeFromPayload(msg, customer, noFeatures());
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessPartnerUpsertException(
                    BusinessPartnerUpsertException.Kind.InvalidPayload,
                    "id obligatorio");
        }
    }

    /** Construye el snapshot minimo que el orquestador pasa a SAP. */
    private static Customer buildCustomer(String id, String name, String category) {
        // El Customer solo lleva lo del upsert: id, code, name, status y
        // subentidades vacias. El adapter S/4 (`BusinessPartnerODataAdapter`)
        // lee `customer.name()` y rellena `OrganizationBPName1`; `category`
        // se pasa por separado en el POST (alta), no en el Customer.
        return new Customer(id, id, name, Customer.Status.ACTIVE, null, null, null, null);
    }

    private static IngestionMessage message(String id) {
        // IngestionMessage fino (ADR-0013): la fuente del dato es el body REST,
        // asi que el aviso no lleva payload ni hash. El orquestador calcula
        // el hash sobre el Customer que le pasamos.
        return IngestionMessage.thin(id, "customer", OperationType.UPDATE, IngestionOrigin.REST);
    }

    private static Set<CustomerFeature> noFeatures() {
        // Sin subentidades: este flujo es solo del BP. La maquina de estados
        // del orquestador permite set vacio desde executeFromPayload (PRD-10,
        // sincronizacion-cliente.md R-1).
        return EnumSet.noneOf(CustomerFeature.class);
    }

    /**
     * Body de PUT. Ambos campos son Strings sin coercion: el controller
     * los deserializa directamente. {@code category} es opcional y se
     * normaliza a {@code "2"} cuando viene vacio.
     */
    public record BusinessPartnerPutRequest(String name, String category) {}

    /**
     * Body de PATCH. Cualquier campo puede ser null; debe haber al menos
     * uno no nulo.
     */
    public record BusinessPartnerPatchRequest(String name, String category) {}
}
