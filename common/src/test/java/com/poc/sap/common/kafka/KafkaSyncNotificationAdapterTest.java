package com.poc.sap.common.kafka;

import com.poc.sap.common.domain.SyncState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Spec sdd/customer/sincronizacion-cliente.md AC-9 (ADR-0010). */
@ExtendWith(MockitoExtension.class)
class KafkaSyncNotificationAdapterTest {

    @Mock KafkaTemplate<Object, Object> kafka;
    @Mock ObjectProvider<KafkaTemplate<Object, Object>> provider;

    private static Map<String, SyncState> results() {
        Map<String, SyncState> r = new LinkedHashMap<>();
        r.put("ADDRESS", SyncState.SENT_SAP); r.put("BANKING", SyncState.SAP_ERROR);
        return r;
    }

    @Test
    void publishesAJsonAlertWithOkAndFailedParts() {
        when(provider.getIfAvailable()).thenReturn(kafka);
        var adapter = new KafkaSyncNotificationAdapter(provider, "sap.sync.alerts", true);

        adapter.partialFailure("customer", "C-1", "h-1", results());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("sap.sync.alerts"), eq("C-1"), payload.capture());
        assertThat(payload.getValue().toString())
                .contains("\"type\":\"SYNC_PARTIAL_FAILURE\"")
                .contains("\"ok\":[\"ADDRESS\"]")
                .contains("\"failed\":[\"BANKING=SAP_ERROR\"]");
    }

    @Test
    void withoutKafkaOrDisabledOnlyLogsAndNeverFails() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatCode(() -> new KafkaSyncNotificationAdapter(provider, "t", true).partialFailure("customer", "C-1", "h", results()))
                .doesNotThrowAnyException();
        when(provider.getIfAvailable()).thenReturn(kafka);
        new KafkaSyncNotificationAdapter(provider, "t", false).partialFailure("customer", "C-1", "h", results());
        verify(kafka, never()).send(any(String.class), any(), any());
        doThrow(new RuntimeException("kafka caido")).when(kafka).send(any(String.class), any(), any());
        assertThatCode(() -> new KafkaSyncNotificationAdapter(provider, "t", true).partialFailure("customer", "C-1", "h", results()))
                .doesNotThrowAnyException();
    }
}
