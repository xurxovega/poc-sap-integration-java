package com.poc.sap.customer.bootstrap.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.customer.application.general.SyncCustomerUseCase;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.SyncState;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

/**
 * Test unit directo del {@link CustomerKafkaListener} (TECH.md §6).
 * Stub un {@link ConsumerRecord} y verifica que invoca el use case con el
 * mensaje correcto. No levanta broker Kafka.
 */
@ExtendWith(MockitoExtension.class)
class CustomerKafkaListenerTest {

    private static final String TOPIC = "outbox.CUSTOMER";

    @Mock SyncCustomerUseCase syncUseCase;
    private CustomerKafkaListener listener;

    @BeforeEach
    void setUp() {
        listener = new CustomerKafkaListener(syncUseCase);
        lenient().when(syncUseCase.execute(any(IngestionMessage.class))).thenReturn(SyncState.SENT_SAP);
    }

    @Test
    void parsesMessageAndInvokesUseCase() {
        String payload = """
                {"entityId":"C-1","operation":"UPDATE","payloadHash":"h-1","payload":{}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 0L, "C-1", payload);

        listener.onMessage(record);

        verify(syncUseCase).execute(argThat(m ->
                "C-1".equals(m.entityId())
                        && m.operation() == OperationType.UPDATE
                        && m.origin() == IngestionOrigin.CDC
                        && "h-1".equals(m.payloadHash())));
    }

    @Test
    void defaultsOperationToUpdateWhenMissing() {
        String payload = """
                {"entityId":"C-2","payloadHash":"h-2","payload":{}}
                """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 0L, "C-2", payload);

        listener.onMessage(record);

        verify(syncUseCase).execute(argThat(m -> m.operation() == OperationType.UPDATE));
    }

    @Test
    void malformedJsonDoesNotPropagateException() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "C-X", "not json");

        listener.onMessage(record);

        verify(syncUseCase, never()).execute(any());
    }

    @Test
    void objectMapperIsAvailable() {
        assertThat(new ObjectMapper()).isNotNull();
    }
}