package com.poc.sap.article.bootstrap.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.poc.sap.article.application.SyncArticleUseCase;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.common.domain.SyncState;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

/**
 * Test unit directo del {@link ArticleKafkaListener} (TECH.md §6). Stub un
 * {@link ConsumerRecord} y verifica que invoca el use case con el mensaje
 * correcto. No levanta broker Kafka.
 */
@ExtendWith(MockitoExtension.class)
class ArticleKafkaListenerTest {

    private static final String TOPIC = "outbox.ARTICLE";

    @Mock SyncArticleUseCase syncUseCase;
    private ArticleKafkaListener listener;

    @BeforeEach
    void setUp() {
        listener = new ArticleKafkaListener(syncUseCase);
        lenient().when(syncUseCase.execute(any(IngestionMessage.class)))
                .thenReturn(SyncState.SENT_SAP);
    }

    @Test
    void parsesMessageAndInvokesUseCase() throws JsonProcessingException {
        String payload = """
                {"entityId":"A-1","operation":"UPDATE","payloadHash":"h-1","payload":{}}
                """;
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "A-1", payload);

        listener.onMessage(record);

        verify(syncUseCase).execute(argThat(m ->
                "A-1".equals(m.entityId())
                        && m.operation() == OperationType.UPDATE
                        && m.origin() == IngestionOrigin.CDC
                        && "h-1".equals(m.payloadHash())));
    }

    @Test
    void defaultsOperationToUpdateWhenMissing() throws JsonProcessingException {
        String payload = """
                {"entityId":"A-2","payloadHash":"h-2","payload":{}}
                """;
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "A-2", payload);

        listener.onMessage(record);

        verify(syncUseCase).execute(argThat(m -> m.operation() == OperationType.UPDATE));
    }

    @Test
    void deleteOperationIsDiscardedWithoutInvokingUseCase() throws JsonProcessingException {
        String payload = """
                {"entityId":"A-3","operation":"DELETE","payloadHash":"h-3","payload":{}}
                """;
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "A-3", payload);

        listener.onMessage(record);

        verify(syncUseCase, never()).execute(any());
    }

    @Test
    void malformedJsonPropagatesException() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "A-X", "not json");

        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(JsonProcessingException.class);

        verify(syncUseCase, never()).execute(any());
    }

    @Test
    void invalidOperationValuePropagatesException() {
        String payload = """
                {"entityId":"A-4","operation":"BOGUS","payloadHash":"h-4","payload":{}}
                """;
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "A-4", payload);

        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(IllegalArgumentException.class);

        verify(syncUseCase, never()).execute(any());
    }

    @Test
    void useCaseFailurePropagatesException() {
        when(syncUseCase.execute(any(IngestionMessage.class)))
                .thenThrow(new IllegalStateException("boom"));
        String payload = """
                {"entityId":"A-5","operation":"UPDATE","payloadHash":"h-5","payload":{}}
                """;
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "A-5", payload);

        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }
}
