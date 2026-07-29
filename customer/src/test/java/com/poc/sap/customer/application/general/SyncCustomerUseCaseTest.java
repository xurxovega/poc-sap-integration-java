package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.ImageStorePort;
import com.poc.sap.common.domain.port.HistoryIndexerPort;
import com.poc.sap.common.domain.port.LegacyRepositoryPort;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.application.address.SyncAddressUseCase;
import com.poc.sap.customer.application.banking.SyncBankingUseCase;
import com.poc.sap.customer.application.contact.SyncContactUseCase;
import com.poc.sap.customer.application.fiscal.SyncFiscalUseCase;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests unit del orchestrador general {@link SyncCustomerUseCase}.
 * Puro: sin Spring, mocks de todos los ports y use cases de feature.
 */
@ExtendWith(MockitoExtension.class)
class SyncCustomerUseCaseTest {

    @Mock CustomerLegacyRepositoryPort legacyRepo;
    @Mock CustomerImageStorePort imageStore;
    @Mock CustomerHistoryIndexerPort historyIndexer;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;
    @Mock SyncAddressUseCase address;
    @Mock SyncFiscalUseCase fiscal;
    @Mock SyncContactUseCase contact;
    @Mock SyncBankingUseCase banking;

    private SyncCustomerUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SyncCustomerUseCase(
                legacyRepo, imageStore, historyIndexer, stateRepo, metrics,
                address, fiscal, contact, banking);
        lenient().when(stateRepo.alreadySent(anyString(), anyString(), anyString()))
                .thenReturn(false);
    }

    @Test
    void happyPathReturnsSentSapWhenAllFeaturesSucceed() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(address.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);
        when(fiscal.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);
        when(contact.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);
        when(banking.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(imageStore).save(eq("C-1"), eq(c));
        verify(historyIndexer).index(eq("C-1"), eq(c), anyString());
        verify(legacyRepo, times(1)).fetch("C-1");
    }

    @Test
    void returnsErrorWhenLegacyFetchEmpty() {
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.empty());

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.ERROR);
        verify(imageStore, never()).save(anyString(), any());
        verify(address, never()).execute(any(), anyString());
    }

    @Test
    void returnsInvalidWhenAggregateValidationFails() {
        Customer invalid = CustomerFixtures.invalidAddressCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(invalid));

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.INVALID);
        verify(address, never()).execute(any(), anyString());
        verify(imageStore, never()).save(anyString(), any());
    }

    @Test
    void partialValidationSkipsInvalidFeatures() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(address.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);

        Set<CustomerFeature> onlyAddress = EnumSet.of(CustomerFeature.ADDRESS);
        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage(), onlyAddress);

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(address).execute(eq(c), anyString());
        verify(fiscal, never()).execute(any(), anyString());
        verify(contact, never()).execute(any(), anyString());
        verify(banking, never()).execute(any(), anyString());
    }

    @Test
    void returnsInvalidWhenAnyFeatureReturnsInvalid() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(address.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);
        when(fiscal.execute(eq(c), anyString())).thenReturn(SyncState.INVALID);
        when(contact.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);
        when(banking.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.INVALID);
    }

    @Test
    void returnsSapErrorWhenAnyFeatureSapError() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(address.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);
        when(fiscal.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);
        when(contact.execute(eq(c), anyString())).thenReturn(SyncState.SAP_ERROR);
        when(banking.execute(eq(c), anyString())).thenReturn(SyncState.SENT_SAP);

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
    }

    @Test
    void alreadySentPayloadSkipsPipelineAndReturnsSentSap() {
        when(stateRepo.alreadySent("customer", "C-1", "hash-001")).thenReturn(true);

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(legacyRepo, never()).fetch(anyString());
        verify(imageStore, never()).save(anyString(), any());
        verify(stateRepo, never()).transition(anyString(), anyString(), any());
        verify(address, never()).execute(any(), anyString());
    }

    @Test
    void unchangedSnapshotAfterSentSapSkipsResend() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(stateRepo.currentState("customer", "C-1")).thenReturn(Optional.of(SyncState.SENT_SAP));
        when(imageStore.find("C-1")).thenReturn(Optional.of(c));

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(imageStore, never()).save(anyString(), any());
        verify(historyIndexer, never()).index(anyString(), any(), anyString());
        verify(address, never()).execute(any(), anyString());
        verify(banking, never()).execute(any(), anyString());
    }

    @Test
    void changedSnapshotAfterSentSapIsResent() {
        Customer stored = CustomerFixtures.validCustomer();
        Customer modified = new Customer(stored.id(), stored.code(), stored.name() + " MOD",
                stored.status(), stored.address(), stored.fiscal(), stored.contact(), stored.banking());
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(modified));
        when(stateRepo.currentState("customer", "C-1")).thenReturn(Optional.of(SyncState.SENT_SAP));
        when(imageStore.find("C-1")).thenReturn(Optional.of(stored));
        when(address.execute(eq(modified), anyString())).thenReturn(SyncState.SENT_SAP);
        when(fiscal.execute(eq(modified), anyString())).thenReturn(SyncState.SENT_SAP);
        when(contact.execute(eq(modified), anyString())).thenReturn(SyncState.SENT_SAP);
        when(banking.execute(eq(modified), anyString())).thenReturn(SyncState.SENT_SAP);

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(imageStore).save(eq("C-1"), eq(modified));
        verify(historyIndexer).index(eq("C-1"), eq(modified), anyString());
    }

    @Test
    void emptyFeaturesThrowsIllegalArgument() {
        assertThatThrownBy(() ->
                useCase.execute(CustomerFixtures.ingestionMessage(), EnumSet.noneOf(CustomerFeature.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("features");
    }

    @Test
    void nullFeaturesThrowsIllegalArgument() {
        assertThatThrownBy(() ->
                useCase.execute(CustomerFixtures.ingestionMessage(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}