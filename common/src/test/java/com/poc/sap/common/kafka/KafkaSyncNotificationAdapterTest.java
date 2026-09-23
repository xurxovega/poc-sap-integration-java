package com.poc.sap.common.kafka;

import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncNotificationPort.SyncPartialFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Spec sdd/customer/sincronizacion-cliente.md AC-9 y AC-12 (ADR-0010). */
@ExtendWith(MockitoExtension.class)
class KafkaSyncNotificationAdapterTest {

    @Mock KafkaTemplate<Object, Object> kafka;
    @Mock ObjectProvider<KafkaTemplate<Object, Object>> provider;

    private static final Instant AT = Instant.parse("2026-09-18T10:00:00Z");

    private static SyncPartialFailure event() {
        return new SyncPartialFailure("customer", "C-1", "cyc-7", "h-1", SyncState.SAP_ERROR,
                List.of(new FeatureOutcome("ADDRESS", SyncState.SENT_SAP, null, AT),
                        new FeatureOutcome("BANKING", SyncState.SAP_ERROR, "HTTP 400: IBAN invalido", AT)),
                AT);
    }

    @Test
    void publishesAJsonAlertWithOkAndFailedParts() {
        when(provider.getIfAvailable()).thenReturn(kafka);
        var adapter = new KafkaSyncNotificationAdapter(provider, "sap.sync.alerts", true);

        adapter.partialFailure(event());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("sap.sync.alerts"), eq("C-1"), payload.capture());
        assertThat(payload.getValue().toString())
                .contains("\"type\":\"SYNC_PARTIAL_FAILURE\"")
                .contains("\"ok\":[\"ADDRESS\"]")
                .contains("\"failed\":[\"BANKING=SAP_ERROR\"]");
    }

    /**
     * AC-12 (sdd/customer/sincronizacion-cliente.md): el aviso no dice solo QUE
     * parte fallo, dice POR QUE y a que ciclo pertenece. Sin eso, quien opera tiene
     * que ir a buscar el motivo a los logs.
     */
    @Test
    void alertCarriesCycleIdAggregateStateAndPerFeatureDetail() {
        when(provider.getIfAvailable()).thenReturn(kafka);
        var adapter = new KafkaSyncNotificationAdapter(provider, "sap.sync.alerts", true);

        adapter.partialFailure(event());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("sap.sync.alerts"), eq("C-1"), payload.capture());
        String json = payload.getValue().toString();
        assertThat(json)
                .contains("\"cycleId\":\"cyc-7\"")
                .contains("\"aggregateState\":\"SAP_ERROR\"")
                .contains("\"attempts\"")
                .contains("\"feature\":\"BANKING\"")
                .contains("\"detail\":\"HTTP 400: IBAN invalido\"");
    }

    @Test
    void withoutKafkaOrDisabledOnlyLogsAndNeverFails() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatCode(() -> new KafkaSyncNotificationAdapter(provider, "t", true).partialFailure(event()))
                .doesNotThrowAnyException();
        when(provider.getIfAvailable()).thenReturn(kafka);
        new KafkaSyncNotificationAdapter(provider, "t", false).partialFailure(event());
        verify(kafka, never()).send(any(String.class), any(), any());
        doThrow(new RuntimeException("kafka caido")).when(kafka).send(any(String.class), any(), any());
        assertThatCode(() -> new KafkaSyncNotificationAdapter(provider, "t", true).partialFailure(event()))
                .doesNotThrowAnyException();
    }
}
