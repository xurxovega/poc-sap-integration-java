package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.security.AccessScope;
import com.poc.sap.common.security.ApiRoles;
import com.poc.sap.customer.application.general.UpsertBusinessPartnerUseCase;
import com.poc.sap.customer.application.general.UpsertBusinessPartnerUseCase.BusinessPartnerPatchRequest;
import com.poc.sap.customer.application.general.UpsertBusinessPartnerUseCase.BusinessPartnerPutRequest;
import com.poc.sap.customer.domain.BusinessPartnerUpsertException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints REST de escritura de Business Partner (PRD-10,
 * spec {@code docs/sdd/customer/upsert-business-partner-manual.md}).
 *
 * <p>PUT alta/actualizacion completa, PATCH update parcial. Disparan el mismo
 * pipeline que la sincronizacion CDC: el use case construye un
 * {@code Customer} minimo desde el body y se lo pasa al
 * {@code SyncCustomerUseCase.executeFromPayload}, que ejecuta la maquina de
 * estados, dedupe por hash y upsert en SAP S/4 Public Cloud (POST si el BP
 * no existe, PATCH con {@code If-Match} si ya esta).
 *
 * <p>Seguridad (spec {@code docs/sdd/common/seguridad-api.md} R-3 y R-5): rol
 * {@link ApiRoles#WRITE} (o superior por jerarquia). <b>No</b> admite
 * {@link ApiRoles#EXTERNAL_READ}: las escrituras contra S/4 son facturables,
 * no son una operacion que un cliente externo pueda iniciar. El PII del
 * nombre se ignora: si lo que llega es PII real (no anonimizado) no se le
 * devuelve al cliente; el cuerpo de respuesta es solo el estado del ciclo.
 *
 * <p>Activacion condicional: el bean y el controller solo se registran si
 * {@link UpsertBusinessPartnerUseCase} esta disponible, que a su vez solo
 * se monta si {@code BusinessPartnerODataAdapter} esta activo
 * ({@code sap.odata.customer.enabled=true}). Si no, los endpoints no se
 * registran y el dispatcher responde 404. Mismo patron que el controller de
 * lectura de PRD-9.
 */
@RestController
@RequestMapping("/business-partners")
@ConditionalOnBean(UpsertBusinessPartnerUseCase.class)
public class BusinessPartnerWriteController {

    private final UpsertBusinessPartnerUseCase useCase;
    /** Inyectado por simetria con el resto de controllers; aqui no se usa. */
    @SuppressWarnings("unused")
    private final AccessScope accessScope;

    public BusinessPartnerWriteController(UpsertBusinessPartnerUseCase useCase, AccessScope accessScope) {
        this.useCase = useCase;
        this.accessScope = accessScope;
    }

    /**
     * PUT: alta o actualizacion completa. Body:
     * {@code {"name":"Foo SL","category":"2"}}. {@code category} opcional
     * (default {@code "2"} para clientes).
     */
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('" + ApiRoles.WRITE + "')")
    public ResponseEntity<Map<String, Object>> put(
            @PathVariable String id,
            @RequestBody BusinessPartnerPutRequest body) {
        SyncState state = useCase.put(id, body, accessScope);
        return ResponseEntity.ok(asResponse(id, state));
    }

    /**
     * PATCH: actualizacion parcial. Body con al menos uno de
     * {@code name}, {@code category}. El campo {@code category} se rechaza
     * con 400 en esta version (R-1 del spec: el adapter solo soporta
     * {@code OrganizationBPName1} en PATCH).
     */
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('" + ApiRoles.WRITE + "')")
    public ResponseEntity<Map<String, Object>> patch(
            @PathVariable String id,
            @RequestBody BusinessPartnerPatchRequest body) {
        SyncState state = useCase.patch(id, body, accessScope);
        return ResponseEntity.ok(asResponse(id, state));
    }

    private static Map<String, Object> asResponse(String id, SyncState state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityId", id);
        m.put("state", state.name());
        return m;
    }

    /**
     * {@code BusinessPartnerUpsertException} del use case -> 400 con cuerpo
     * {@code {"error":"<motivo>"}}. Aplica a campos obligatorios faltantes y
     * a campos rechazados por reglas del spec (R-1).
     */
    @ExceptionHandler(BusinessPartnerUpsertException.class)
    public ResponseEntity<Map<String, Object>> badRequest(BusinessPartnerUpsertException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }

    /**
     * Conflicto de concurrencia: otro ciclo (Kafka o REST) esta procesando
     * esta misma entidad (ADR-0011). No es un fallo del servidor, es un
     * conflicto temporal: 409 y que el llamante reintente. Mismo patron que
     * {@code SyncCustomerController#onConcurrentTransition}.
     */
    @ExceptionHandler(ConcurrentTransitionException.class)
    public ProblemDetail onConcurrentTransition(ConcurrentTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "La entidad la esta procesando otro ciclo de sincronizacion; reintentelo.");
        problem.setTitle("Sincronizacion concurrente");
        return problem;
    }
}
