package com.poc.sap.article.bootstrap.kafka;

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

import static org.assertj.core.api.Assertions.assertThat;
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
    void parsesMessageAndInvokesUseCase() {
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
    void defaultsOperationToUpdateWhenMissing() {
        String payload = """
                {"entityId":"A-2","payloadHash":"h-2","payload":{}}
                """;
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "A-2", payload);

        listener.onMessage(record);

        verify(syncUseCase).execute(argThat(m -> m.operation() == OperationType.UPDATE));
    }

    @Test
    void malformedJsonDoesNotPropagateException() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "A-X", "not json");

        listener.onMessage(record);

        verify(syncUseCase, never()).execute(any());
    }

    @Test
    void invalidOperationValueDoesNotPropagateException() {
        String payload = """
                {"entityId":"A-3","operation":"BOGUS","payloadHash":"h-3","payload":{}}
                """;
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "A-3", payload);

        listener.onMessage(record);

        verify(syncUseCase, never()).execute(any());
    }
}