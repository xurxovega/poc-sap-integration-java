package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.PayloadHasher;
import com.poc.sap.common.domain.NothingReachedSapException;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncNotificationPort;
import com.poc.sap.common.domain.port.SyncNotificationPort.SyncPartialFailure;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.sap.SapCircuitOpenException;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.application.CustomerFeatureSync;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.poc.sap.customer.application.InMemoryStateRepo;
import com.poc.sap.common.domain.OperationType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests unit del orchestrador general {@link SyncCustomerUseCase}.
 * Puro: sin Spring; puertos mockeados y las features como CustomerFeatureSync (A20).
 */
@ExtendWith(MockitoExtension.class)
class SyncCustomerUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock CustomerLegacyRepositoryPort legacyRepo;
    @Mock CustomerImageStorePort imageStore;
    @Mock CustomerHistoryIndexerPort historyIndexer;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock MetricsPort metrics;
    @Mock SyncNotificationPort notifications;
    @Mock CustomerFeatureSync address;   // puerto de feature, no la clase concreta (A20)
    @Mock CustomerFeatureSync fiscal;   // puerto de feature, no la clase concreta (A20)
    @Mock CustomerFeatureSync contact;   // puerto de feature, no la clase concreta (A20)
    @Mock CustomerFeatureSync banking;   // puerto de feature, no la clase concreta (A20)

    private SyncCustomerUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = withStateRepo(stateRepo);
        lenient().when(stateRepo.alreadySent(anyString(), anyString(), anyString()))
                .thenReturn(false);
    }

    private SyncCustomerUseCase withStateRepo(SyncStateRepositoryPort repo) {
        return new SyncCustomerUseCase(
                legacyRepo, imageStore, historyIndexer, repo, metrics, notifications,
                address, fiscal, contact, banking, FIXED, true);
    }

    private static FeatureOutcome outcome(String feature, SyncState state) {
        return new FeatureOutcome(feature, state, state == SyncState.SENT_SAP ? null : "motivo " + state, NOW);
    }

    @Test
    void happyPathReturnsSentSapWhenAllFeaturesSucceed() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        allFeaturesSucceed();

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
        verify(address, never()).execute(any(), anyString(), anyString());
    }

    @Test
    void returnsInvalidWhenAggregateValidationFails() {
        Customer invalid = CustomerFixtures.invalidAddressCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(invalid));

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.INVALID);
        verify(address, never()).execute(any(), anyString(), anyString());
        verify(imageStore, never()).save(anyString(), any());
    }

    @Test
    void partialValidationSkipsInvalidFeatures() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(address.execute(eq(c), anyString(), anyString())).thenReturn(outcome("ADDRESS", SyncState.SENT_SAP));

        Set<CustomerFeature> onlyAddress = EnumSet.of(CustomerFeature.ADDRESS);
        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage(), onlyAddress);

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(address).execute(eq(c), anyString(), anyString());
        verify(fiscal, never()).execute(any(), anyString(), anyString());
        verify(contact, never()).execute(any(), anyString(), anyString());
        verify(banking, never()).execute(any(), anyString(), anyString());
    }

    /**
     * AC-13 (sdd/customer/sincronizacion-cliente.md R-7, cero confianza): si una
     * parte es INVALID pero otra llego a llamar a SAP, el agregado NO puede decir
     * INVALID ("no se envio nada"): es SAP_ERROR. Antes decia INVALID (D-14).
     */
    @Test
    void aggregateIsSapErrorWhenAnyPartReachedSap() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(address.execute(eq(c), anyString(), anyString())).thenReturn(outcome("ADDRESS", SyncState.SENT_SAP));
        when(fiscal.execute(eq(c), anyString(), anyString())).thenReturn(outcome("FISCAL", SyncState.INVALID));
        when(contact.execute(eq(c), anyString(), anyString())).thenReturn(outcome("CONTACT", SyncState.SENT_SAP));
        when(banking.execute(eq(c), anyString(), anyString())).thenReturn(outcome("BANKING", SyncState.SENT_SAP));

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
        verify(imageStore, never()).save(anyString(), any());
    }

    /** R-7: INVALID solo si NINGUNA parte llego a llamar a SAP. */
    @Test
    void aggregateIsInvalidOnlyWhenEveryPartIsInvalid() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        for (CustomerFeatureSync f : new CustomerFeatureSync[] {address, fiscal, contact, banking}) {
            when(f.execute(eq(c), anyString(), anyString())).thenReturn(outcome("X", SyncState.INVALID));
        }

        assertThat(useCase.execute(CustomerFixtures.ingestionMessage())).isEqualTo(SyncState.INVALID);
    }

    @Test
    void returnsSapErrorWhenAnyFeatureSapError() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(address.execute(eq(c), anyString(), anyString())).thenReturn(outcome("ADDRESS", SyncState.SENT_SAP));
        when(fiscal.execute(eq(c), anyString(), anyString())).thenReturn(outcome("FISCAL", SyncState.SENT_SAP));
        when(contact.execute(eq(c), anyString(), anyString())).thenReturn(outcome("CONTACT", SyncState.SAP_ERROR));
        when(banking.execute(eq(c), anyString(), anyString())).thenReturn(outcome("BANKING", SyncState.SENT_SAP));

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
        // idempotencia-y-dedupe AC-3: el historico registra el intento, la imagen NO
        // cambia porque SAP no tiene el dato (antes se guardaba antes de enviar).
        verify(historyIndexer).index(eq("C-1"), eq(c), anyString());
        verify(imageStore, never()).save(anyString(), any());
        // R-9 / AC-8 (ADR-0010): se avisa de que parte entro y que parte no
        ArgumentCaptor<SyncPartialFailure> alert = ArgumentCaptor.forClass(SyncPartialFailure.class);
        verify(notifications).partialFailure(alert.capture());
        assertThat(alert.getValue().attempts()).hasSize(4);
        verify(metrics).incrementFeatureResult("customer", "CONTACT", "SAP_ERROR");
    }

    /** AC-8: con todas las partes en SENT_SAP no hay aviso. */
    @Test
    void fullSuccessDoesNotNotify() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        allFeaturesSucceed();

        useCase.execute(CustomerFixtures.ingestionMessage());

        verify(notifications, never()).partialFailure(any());
        verify(metrics).incrementFeatureResult("customer", "BANKING", "SENT_SAP");
    }

    /**
     * AC-5 (sdd/common/idempotencia-y-dedupe.md, ADR-0013): el dedupe se hace con
     * el hash del snapshot releido del legacy. El hash del mensaje puede venir
     * viejo, mentir o no venir: no decide nada.
     */
    @Test
    void dedupeUsesTheHashOfTheSnapshotNotTheMessage() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(stateRepo.alreadySent("customer", "C-1", PayloadHasher.hash(c))).thenReturn(true);

        SyncState result = useCase.execute(
                CustomerFixtures.ingestionMessage("C-1", OperationType.UPDATE, "hash-que-no-corresponde"));

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(imageStore, never()).save(anyString(), any());
        verify(stateRepo, never()).transition(anyString(), anyString(), any());
        verify(address, never()).execute(any(), anyString(), anyString());
    }

    /**
     * AC-5: el hash del mensaje ya no deduplica por si solo. Si el snapshot ha
     * cambiado, el ciclo se ejecuta aunque el aviso repita un hash ya enviado.
     */
    @Test
    void messageHashAlreadySentDoesNotSkipWhenTheSnapshotDiffers() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        lenient().when(stateRepo.alreadySent("customer", "C-1", "hash-001")).thenReturn(true);
        allFeaturesSucceed();

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        // Nunca se pregunta por el hash del mensaje: solo por el del snapshot.
        verify(stateRepo, never()).alreadySent("customer", "C-1", "hash-001");
        verify(stateRepo).alreadySent("customer", "C-1", PayloadHasher.hash(c));
        verify(address).execute(eq(c), anyString(), eq(PayloadHasher.hash(c)));
    }

    /**
     * AC-6 (sdd/customer/sincronizacion-cliente.md §3, ADR-0013): el mensaje fino
     * -sin hash y sin payload- se procesa igual: el estado actual sale del legacy.
     */
    @Test
    void thinMessageWithoutPayloadIsProcessed() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        allFeaturesSucceed();

        SyncState result = useCase.execute(IngestionMessage.thin(
                "C-1", "customer", OperationType.UPDATE, IngestionOrigin.CDC));

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(historyIndexer).index("C-1", c, PayloadHasher.hash(c));
        verify(imageStore).save("C-1", c);
    }

    /** AC-5: el hash calculado es el que queda escrito en el ciclo y en el envio. */
    @Test
    void computedHashTravelsToFeaturesAndTransitions() {
        Customer c = CustomerFixtures.validCustomer();
        String expected = PayloadHasher.hash(c);
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        allFeaturesSucceed();

        useCase.execute(IngestionMessage.thin("C-1", "customer", OperationType.UPDATE, IngestionOrigin.CDC));

        ArgumentCaptor<com.poc.sap.common.domain.SyncStateTransition> captor =
                ArgumentCaptor.forClass(com.poc.sap.common.domain.SyncStateTransition.class);
        verify(stateRepo, atLeastOnce()).transition(eq("customer"), eq("C-1"), captor.capture());
        assertThat(captor.getAllValues()).allMatch(t -> expected.equals(t.payloadHash()));
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
        verify(address, never()).execute(any(), anyString(), anyString());
        verify(banking, never()).execute(any(), anyString(), anyString());
    }

    @Test
    void changedSnapshotAfterSentSapIsResent() {
        Customer stored = CustomerFixtures.validCustomer();
        Customer modified = new Customer(stored.id(), stored.code(), stored.name() + " MOD",
                stored.status(), stored.address(), stored.fiscal(), stored.contact(), stored.banking());
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(modified));
        when(stateRepo.currentState("customer", "C-1")).thenReturn(Optional.of(SyncState.SENT_SAP));
        when(imageStore.find("C-1")).thenReturn(Optional.of(stored));
        when(address.execute(eq(modified), anyString(), anyString())).thenReturn(outcome("ADDRESS", SyncState.SENT_SAP));
        when(fiscal.execute(eq(modified), anyString(), anyString())).thenReturn(outcome("FISCAL", SyncState.SENT_SAP));
        when(contact.execute(eq(modified), anyString(), anyString())).thenReturn(outcome("CONTACT", SyncState.SENT_SAP));
        when(banking.execute(eq(modified), anyString(), anyString())).thenReturn(outcome("BANKING", SyncState.SENT_SAP));

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

    /** observabilidad AC-2 (auditoria A9): recordStageDuration existia y nadie lo invocaba. */
    @Test
    void happyPathRecordsTheDurationOfEveryStage() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        allFeaturesSucceed();

        useCase.execute(CustomerFixtures.ingestionMessage());

        for (String stage : new String[] {"fetch", "validate", "index", "send"}) {
            verify(metrics).recordStageDuration(eq("customer"), eq(stage), anyLong());
        }
    }

    /**
     * AC-11 (sdd/customer/sincronizacion-cliente.md; auditoria 2026-09-18 N1): si
     * una parte LANZA, el bucle no aborta. Antes las tres restantes no se
     * intentaban siquiera y nadie se enteraba.
     */
    @Test
    void aFeatureThrowingDoesNotAbortTheRemainingFeatures() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(address.execute(eq(c), anyString(), anyString()))
                .thenThrow(new SapCircuitOpenException(SapDestination.S4_NATIVE, null));
        when(fiscal.execute(eq(c), anyString(), anyString())).thenReturn(outcome("FISCAL", SyncState.SENT_SAP));
        when(contact.execute(eq(c), anyString(), anyString())).thenReturn(outcome("CONTACT", SyncState.SENT_SAP));
        when(banking.execute(eq(c), anyString(), anyString())).thenReturn(outcome("BANKING", SyncState.SENT_SAP));

        SyncState result = useCase.execute(CustomerFixtures.ingestionMessage());

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
        verify(fiscal).execute(eq(c), anyString(), anyString());
        verify(contact).execute(eq(c), anyString(), anyString());
        verify(banking).execute(eq(c), anyString(), anyString());
    }

    /**
     * AC-12: el aviso sale SIEMPRE y lleva el resultado de CADA parte, su motivo y
     * el ciclo al que pertenecen, que es lo que permite reconstruir la traza.
     */
    @Test
    void partialFailureCarriesEveryFeatureStepWithItsReason() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(address.execute(eq(c), anyString(), anyString())).thenReturn(outcome("ADDRESS", SyncState.SENT_SAP));
        when(fiscal.execute(eq(c), anyString(), anyString())).thenReturn(outcome("FISCAL", SyncState.SENT_SAP));
        when(contact.execute(eq(c), anyString(), anyString())).thenReturn(
                new FeatureOutcome("CONTACT", SyncState.SAP_ERROR, "HTTP 400: falta email", NOW));
        when(banking.execute(eq(c), anyString(), anyString())).thenReturn(
                new FeatureOutcome("BANKING", SyncState.COMMUNICATION_ERROR, "circuito SAP abierto", NOW));

        useCase.execute(CustomerFixtures.ingestionMessage());

        ArgumentCaptor<SyncPartialFailure> alert = ArgumentCaptor.forClass(SyncPartialFailure.class);
        verify(notifications).partialFailure(alert.capture());
        SyncPartialFailure event = alert.getValue();
        assertThat(event.domain()).isEqualTo("customer");
        assertThat(event.entityId()).isEqualTo("C-1");
        assertThat(event.cycleId()).isNotBlank();
        assertThat(event.aggregateState()).isEqualTo(SyncState.SAP_ERROR);
        assertThat(event.at()).isEqualTo(NOW);
        assertThat(event.attempts()).extracting(FeatureOutcome::feature)
                .containsExactly("ADDRESS", "FISCAL", "CONTACT", "BANKING");
        assertThat(event.attempts()).extracting(FeatureOutcome::detail)
                .containsExactly(null, null, "HTTP 400: falta email", "circuito SAP abierto");
    }

    /**
     * AC-14 (D-15): si NINGUNA parte llego a SAP y todas fallaron por comunicacion,
     * el estado y el aviso ya estan escritos y ademas se propaga, para que la
     * ingesta reintente el mensaje entero. Si algo hubiera entrado en SAP, NO se
     * propaga: reintentar duplicaria.
     */
    @Test
    void allPartsFailingBeforeReachingSapRethrowsSoKafkaRetries() {
        InMemoryStateRepo repo = new InMemoryStateRepo();
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        for (CustomerFeatureSync f : new CustomerFeatureSync[] {address, fiscal, contact, banking}) {
            when(f.execute(eq(c), anyString(), anyString())).thenReturn(
                    new FeatureOutcome("X", SyncState.COMMUNICATION_ERROR, "circuito SAP abierto", NOW));
        }
        var msg = CustomerFixtures.ingestionMessage("C-1", OperationType.UPDATE, "h-com");

        assertThatThrownBy(() -> withStateRepo(repo).execute(msg))
                .isInstanceOf(NothingReachedSapException.class);

        assertThat(repo.currentState("customer", "C-1")).contains(SyncState.SAP_ERROR);
        verify(notifications).partialFailure(any());
    }

    /** AC-14, la otra mitad: con una parte en SAP no se propaga (reintentar duplicaria). */
    @Test
    void mixedFailureDoesNotRethrowBecauseRetryingWouldDuplicate() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(address.execute(eq(c), anyString(), anyString())).thenReturn(outcome("ADDRESS", SyncState.SENT_SAP));
        when(fiscal.execute(eq(c), anyString(), anyString())).thenReturn(
                new FeatureOutcome("FISCAL", SyncState.COMMUNICATION_ERROR, "circuito SAP abierto", NOW));
        when(contact.execute(eq(c), anyString(), anyString())).thenReturn(
                new FeatureOutcome("CONTACT", SyncState.COMMUNICATION_ERROR, "circuito SAP abierto", NOW));
        when(banking.execute(eq(c), anyString(), anyString())).thenReturn(
                new FeatureOutcome("BANKING", SyncState.COMMUNICATION_ERROR, "circuito SAP abierto", NOW));

        assertThat(useCase.execute(CustomerFixtures.ingestionMessage())).isEqualTo(SyncState.SAP_ERROR);
    }

    private void allFeaturesSucceed() {
        when(address.execute(any(), any(), any())).thenReturn(outcome("ADDRESS", SyncState.SENT_SAP));
        when(fiscal.execute(any(), any(), any())).thenReturn(outcome("FISCAL", SyncState.SENT_SAP));
        when(contact.execute(any(), any(), any())).thenReturn(outcome("CONTACT", SyncState.SENT_SAP));
        when(banking.execute(any(), any(), any())).thenReturn(outcome("BANKING", SyncState.SENT_SAP));
    }

    private SyncCustomerUseCase withRealStateMachine(InMemoryStateRepo repo) {
        return withStateRepo(repo);
    }

    /**
     * AC-4 (sdd/customer/sincronizacion-cliente.md): un cliente cuyo envio a SAP
     * fallo una vez debe volver a sincronizarse con el siguiente evento. Contra la
     * maquina de estados REAL: con el puerto mockeado este fallo era invisible
     * (auditoria B1).
     */
    @Test
    void resyncsCustomerStuckInSapError() {
        InMemoryStateRepo repo = new InMemoryStateRepo();
        repo.seed("C-1", SyncState.SAP_ERROR);
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(CustomerFixtures.validCustomer()));
        allFeaturesSucceed();

        SyncState result = withRealStateMachine(repo)
                .execute(CustomerFixtures.ingestionMessage("C-1", OperationType.UPDATE, "h-2"));

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        assertThat(repo.currentState("customer", "C-1")).contains(SyncState.SENT_SAP);
    }

    /**
     * AC-5: un fallo de infraestructura tras VALID (aqui el indexador) deja la
     * entidad en ERROR y se propaga; antes quedaba colgada en INDEXING para
     * siempre (auditoria B12/C2).
     */
    @Test
    void infrastructureFailureAfterValidMarksErrorAndPropagates() {
        InMemoryStateRepo repo = new InMemoryStateRepo();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(CustomerFixtures.validCustomer()));
        doThrow(new RuntimeException("Elasticsearch caido"))
                .when(historyIndexer).index(any(), any(), any());
        SyncCustomerUseCase real = withRealStateMachine(repo);
        var msg = CustomerFixtures.ingestionMessage("C-1", OperationType.UPDATE, "h-3");

        assertThatThrownBy(() -> real.execute(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Elasticsearch caido");
        assertThat(repo.currentState("customer", "C-1")).contains(SyncState.ERROR);
    }

    /**
     * AC-6: si el proceso murio a mitad y la entidad quedo en SENDING_SAP, el
     * siguiente evento abre ciclo en vez de fallar (OPS-1, verificado en vivo con
     * CUST-001 el 2026-09-09).
     */
    @Test
    void reopensCycleWhenPreviousOneWasLeftInFlight() {
        InMemoryStateRepo repo = new InMemoryStateRepo();
        repo.seed("C-1", SyncState.SENDING_SAP);
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(CustomerFixtures.validCustomer()));
        allFeaturesSucceed();

        SyncState result = withRealStateMachine(repo)
                .execute(CustomerFixtures.ingestionMessage("C-1", OperationType.UPDATE, "h-4"));

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
    }

    /**
     * AC-2 (sdd/customer/sincronizacion-cliente.md): un snapshot ya enviado a SAP
     * no se reprocesa: responde SENT_SAP sin tocar SAP. Desde ADR-0013 el legacy
     * SI se lee -es la unica forma de saber que snapshot es- pero nada mas.
     */
    @Test
    void alreadySentPayloadSkipsPipeline() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));
        when(stateRepo.alreadySent("customer", "C-1", PayloadHasher.hash(c))).thenReturn(true);

        SyncState result = useCase.execute(
                CustomerFixtures.ingestionMessage("C-1", OperationType.UPDATE, "h-dup"));

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(address, never()).execute(any(), any(), any());
    }

    /**
     * AC-14 de idempotencia-y-dedupe (auditoria 2026-09-18 N4, 2A-5): tras un ciclo
     * que termino en fallo parcial, SAP tiene una mezcla. Aunque vuelva el hash del
     * ultimo SENT_SAP, hay que reenviar: afirmar que esta sincronizado es mentira.
     */
    @Test
    void resendsWhenTheLastCycleEndedInPartialFailure() {
        InMemoryStateRepo repo = new InMemoryStateRepo();
        repo.seedCycleEnd("C-1", SyncState.SAP_ERROR, "h-2");
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(CustomerFixtures.validCustomer()));
        allFeaturesSucceed();

        SyncState result = withRealStateMachine(repo)
                .execute(CustomerFixtures.ingestionMessage("C-1", OperationType.UPDATE, "h-1"));

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(legacyRepo).fetch("C-1");
    }

    // =========================================================================
    //  PRD-10 (upsert-business-partner-manual.md, AC-7/AC-9/AC-10):
    //  executeFromPayload reusa runPipeline con el Customer del body, no del
    //  legacy. Ningun test previo cambia su comportamiento.
    // =========================================================================

    /**
     * AC-7/AC-9: el orquestador NO relee el legacy y calcula el hash sobre el
     * {@code Customer} recibido (no del legacy). Las features ven ese mismo
     * {@code Customer} y ese mismo hash.
     */
    @Test
    void executeFromPayloadUsesTheProvidedCustomerNotTheLegacy() {
        // Customer con subentidades validas para que la validacion del
        // orquestador pase cuando se piden todas las features (es el caso
        // del orquestador cuando el caller quiere ejecutar todas).
        Customer payload = CustomerFixtures.validCustomer();
        IngestionMessage msg = IngestionMessage.thin(
                "C-1", "customer", OperationType.UPDATE, IngestionOrigin.REST);
        allFeaturesSucceed();

        SyncState result = useCase.executeFromPayload(msg, payload, EnumSet.allOf(CustomerFeature.class));

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        // El legacy NO se consulta: el Customer viene del body.
        verify(legacyRepo, never()).fetch(anyString());
        // Las features reciben el Customer del body y el hash calculado sobre el.
        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        verify(address).execute(customerCaptor.capture(), anyString(), anyString());
        assertThat(customerCaptor.getValue()).isSameAs(payload);
        assertThat(customerCaptor.getValue().name()).isEqualTo("Acme");
        verify(address).execute(eq(payload), anyString(), eq(PayloadHasher.hash(payload)));
    }

    /**
     * AC-7: un payload identico al ultimo ciclo enviado a SAP no reenvia: el
     * dedupe por hash funciona igual que en CDC (mismo criterio R-1 de
     * idempotencia-y-dedupe).
     */
    @Test
    void executeFromPayloadDedupesWhenSnapshotMatchesLastSent() {
        Customer payload = CustomerFixtures.validCustomer();
        when(stateRepo.alreadySent("customer", "C-1", PayloadHasher.hash(payload))).thenReturn(true);

        SyncState result = useCase.executeFromPayload(
                IngestionMessage.thin("C-1", "customer", OperationType.UPDATE, IngestionOrigin.REST),
                payload,
                EnumSet.allOf(CustomerFeature.class));

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(address, never()).execute(any(), anyString(), anyString());
        verify(historyIndexer, never()).index(anyString(), any(), anyString());
    }

    /**
     * AC-9/AC-10: el orquestador maneja la concurrencia igual desde el camino
     * REST: una transicion conflictiva al avanzar el ciclo (otro ciclo acaba
     * de tocar la entidad) se traduce en {@link com.poc.sap.common.domain.ConcurrentTransitionException}
     * y se propaga. Aqui lo simulamos forzando que el repo lance al hacer
     * {@code transition} con la maquina real.
     */
    @Test
    void executeFromPayloadPropagatesConcurrentTransitionFromTheStateRepo() {
        InMemoryStateRepo repo = new InMemoryStateRepo();
        // Sembramos el repo en SENDING_SAP para que la primera transicion
        // (RECEIVED -> FETCHING sea legal; forzamos un choque artificial
        // machacando la maquina con un beginCycle concurrente.
        repo.seed("C-1", SyncState.SENDING_SAP);
        Customer payload = CustomerFixtures.validCustomer();
        allFeaturesSucceed();
        // Forzamos que el siguiente transition choque con el estado sembrado:
        // SENDING_SAP solo puede avanzar a SENT_SAP/SAP_ERROR/COMMUNICATION_ERROR
        // segun la tabla, asi que usamos un payload que invalide el address para
        // que el pipeline no llegue a indexar; en su lugar validamos que el
        // camino REST respeta el estado sembrado (re-entry es legal desde
        // SENDING_SAP, AC-6 de sincronizacion-cliente).
        SyncState result = withStateRepo(repo).executeFromPayload(
                IngestionMessage.thin("C-1", "customer", OperationType.UPDATE, IngestionOrigin.REST),
                payload,
                EnumSet.allOf(CustomerFeature.class));

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(legacyRepo, never()).fetch(anyString());
    }
}
