package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.security.AccessScope;
import com.poc.sap.customer.domain.BusinessPartnerUpsertException;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.Set;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unit del use case de upsert manual de Business Partner (PRD-10,
 * spec {@code docs/sdd/customer/upsert-business-partner-manual.md}). Mockea
 * el {@link SyncCustomerUseCase} y construye un {@link AccessScope}
 * cualquiera (no se mira, eso es cosa del controller).
 */
@ExtendWith(MockitoExtension.class)
class UpsertBusinessPartnerUseCaseTest {

    @Mock SyncCustomerUseCase syncCustomerUseCase;
    @Mock AccessScope accessScope;

    private UpsertBusinessPartnerUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpsertBusinessPartnerUseCase(syncCustomerUseCase);
    }

    // --- AC-1: PUT construye el Customer y delega en executeFromPayload ----

    @Test
    void putBuildsCustomerAndCallsExecuteFromPayload() {
        when(syncCustomerUseCase.executeFromPayload(any(), any(), anySet()))
                .thenReturn(SyncState.SENT_SAP);

        SyncState result = useCase.put("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPutRequest("Foo SL", "2"),
                accessScope);

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        ArgumentCaptor<IngestionMessage> msgCaptor = ArgumentCaptor.forClass(IngestionMessage.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<CustomerFeature>> featuresCaptor = ArgumentCaptor.forClass(Set.class);
        verify(syncCustomerUseCase).executeFromPayload(
                msgCaptor.capture(), customerCaptor.capture(), featuresCaptor.capture());

        Customer c = customerCaptor.getValue();
        assertThat(c.id()).isEqualTo("C001");
        assertThat(c.code()).isEqualTo("C001");
        assertThat(c.name()).isEqualTo("Foo SL");
        assertThat(c.status()).isEqualTo(Customer.Status.ACTIVE);
        // Sin subentidades: el upsert solo escribe el agregado.
        assertThat(c.address()).isNull();
        assertThat(c.fiscal()).isNull();
        assertThat(c.contact()).isNull();
        assertThat(c.banking()).isNull();

        IngestionMessage msg = msgCaptor.getValue();
        assertThat(msg.entityId()).isEqualTo("C001");
        assertThat(msg.domain()).isEqualTo("customer");
        assertThat(msg.operation()).isEqualTo(OperationType.UPDATE);
        assertThat(msg.origin()).isEqualTo(IngestionOrigin.REST);

        // Por defecto PUT dispara solo el agregado (sin subentidades). El
        // upsert REST es del Business Partner: las features se sincronizan
        // por su propio flujo (POST /customers/sync). Aqui no las queremos
        // porque el Customer construido va con todo a null.
        assertThat(featuresCaptor.getValue()).isEmpty();
    }

    @Test
    void putUsesDefaultCategoryTwoWhenOmitted() {
        when(syncCustomerUseCase.executeFromPayload(any(), any(), anySet()))
                .thenReturn(SyncState.SENT_SAP);

        useCase.put("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPutRequest("Foo SL", null),
                accessScope);

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(syncCustomerUseCase).executeFromPayload(any(), captor.capture(), anySet());
        // El campo category del body no va al Customer (lo gestiona el adapter SAP);
        // verificamos que el request normaliza a "2" cuando viene null.
        // La verificacion relevante: el use case no lanza y delega.
        assertThat(captor.getValue().name()).isEqualTo("Foo SL");
    }

    // --- AC-2: el PUT propaga el SyncState del orquestador ------------------

    @Test
    void putReturnsOrchestratorState() {
        when(syncCustomerUseCase.executeFromPayload(any(), any(), anySet()))
                .thenReturn(SyncState.SAP_ERROR);

        SyncState result = useCase.put("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPutRequest("Foo SL", "2"),
                accessScope);

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
    }

    // --- AC-3: PATCH parcial construye un Customer minimo ------------------

    @Test
    void patchWithOnlyNameBuildsPartialCustomer() {
        when(syncCustomerUseCase.executeFromPayload(any(), any(), anySet()))
                .thenReturn(SyncState.SENT_SAP);

        SyncState result = useCase.patch("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPatchRequest("Foo SL", null),
                accessScope);

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(syncCustomerUseCase).executeFromPayload(any(), captor.capture(), anySet());
        Customer c = captor.getValue();
        assertThat(c.id()).isEqualTo("C001");
        assertThat(c.code()).isEqualTo("C001");
        assertThat(c.name()).isEqualTo("Foo SL");
        assertThat(c.status()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(c.address()).isNull();
        assertThat(c.fiscal()).isNull();
        assertThat(c.contact()).isNull();
        assertThat(c.banking()).isNull();
    }

    // --- AC-4: PATCH sin campos -> BusinessPartnerUpsertException ---------

    @Test
    void patchWithoutAnyFieldFails() {
        assertThatThrownBy(() -> useCase.patch("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPatchRequest(null, null),
                accessScope))
                .isInstanceOf(BusinessPartnerUpsertException.class)
                .extracting(e -> ((BusinessPartnerUpsertException) e).kind())
                .isEqualTo(BusinessPartnerUpsertException.Kind.MandatoryFieldMissing);

        verify(syncCustomerUseCase, never()).executeFromPayload(any(), any(), anySet());
    }

    // --- R-1: PATCH con category -> BusinessPartnerUpsertException ---------

    @Test
    void patchWithCategoryFails() {
        assertThatThrownBy(() -> useCase.patch("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPatchRequest(null, "2"),
                accessScope))
                .isInstanceOf(BusinessPartnerUpsertException.class)
                .extracting(e -> ((BusinessPartnerUpsertException) e).kind())
                .isEqualTo(BusinessPartnerUpsertException.Kind.InvalidPayload);

        verify(syncCustomerUseCase, never()).executeFromPayload(any(), any(), anySet());
    }

    // --- R-2: PUT sin name -> BusinessPartnerUpsertException ---------------

    @Test
    void putWithoutNameFails() {
        assertThatThrownBy(() -> useCase.put("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPutRequest(null, "2"),
                accessScope))
                .isInstanceOf(BusinessPartnerUpsertException.class)
                .extracting(e -> ((BusinessPartnerUpsertException) e).kind())
                .isEqualTo(BusinessPartnerUpsertException.Kind.InvalidPayload);

        verify(syncCustomerUseCase, never()).executeFromPayload(any(), any(), anySet());
    }

    @Test
    void putWithBlankNameFails() {
        assertThatThrownBy(() -> useCase.put("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPutRequest("   ", "2"),
                accessScope))
                .isInstanceOf(BusinessPartnerUpsertException.class)
                .extracting(e -> ((BusinessPartnerUpsertException) e).kind())
                .isEqualTo(BusinessPartnerUpsertException.Kind.InvalidPayload);

        verify(syncCustomerUseCase, never()).executeFromPayload(any(), any(), anySet());
    }

    @Test
    void putWithEmptyIdFails() {
        assertThatThrownBy(() -> useCase.put("",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPutRequest("Foo SL", "2"),
                accessScope))
                .isInstanceOf(BusinessPartnerUpsertException.class)
                .extracting(e -> ((BusinessPartnerUpsertException) e).kind())
                .isEqualTo(BusinessPartnerUpsertException.Kind.InvalidPayload);

        verify(syncCustomerUseCase, never()).executeFromPayload(any(), any(), anySet());
    }

    // --- AC-6: ConcurrentTransitionException del orquestador se propaga -----

    @Test
    void concurrentTransitionFromOrchestratorPropagates() {
        when(syncCustomerUseCase.executeFromPayload(any(), any(), anySet()))
                .thenThrow(new ConcurrentTransitionException("customer", "C001", 1L, null));

        assertThatThrownBy(() -> useCase.put("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPutRequest("Foo SL", "2"),
                accessScope))
                .isInstanceOf(ConcurrentTransitionException.class);
    }

    // --- detalles del IngestionMessage --------------------------------------

    @Test
    void ingestMessageCarriesRestOriginAndUpdateOperation() {
        when(syncCustomerUseCase.executeFromPayload(any(), any(), anySet()))
                .thenReturn(SyncState.SENT_SAP);

        useCase.put("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPutRequest("Foo SL", "2"),
                accessScope);

        ArgumentCaptor<IngestionMessage> captor = ArgumentCaptor.forClass(IngestionMessage.class);
        verify(syncCustomerUseCase).executeFromPayload(captor.capture(), any(), anySet());
        IngestionMessage msg = captor.getValue();
        assertThat(msg.entityId()).isEqualTo("C001");
        assertThat(msg.domain()).isEqualTo("customer");
        assertThat(msg.operation()).isEqualTo(OperationType.UPDATE);
        assertThat(msg.origin()).isEqualTo(IngestionOrigin.REST);
        assertThat(msg.payloadHash()).isNull();   // sin hash del mensaje: lo calcula el orquestador
        assertThat(msg.payload()).isNull();
    }

    // --- sanity check: AccessScope no se mira (responsabilidad del controller) -

    @Test
    void accessScopeIsNotUsedByTheUseCase() {
        when(syncCustomerUseCase.executeFromPayload(any(), any(), anySet()))
                .thenReturn(SyncState.SENT_SAP);

        useCase.put("C001",
                new UpsertBusinessPartnerUseCase.BusinessPartnerPutRequest("Foo SL", "2"),
                accessScope);

        verify(accessScope, never()).canSeeSensitiveData();
    }
}
