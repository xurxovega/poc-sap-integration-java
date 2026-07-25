package com.poc.sap.article.bootstrap.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.sap.article.application.SyncArticleUseCase;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listener Kafka para CDC (Debezium) del dominio Article.
 * Los errores se propagan al error handler del contenedor (DLT + backoff).
 */
@Component
public class ArticleKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(ArticleKafkaListener.class);

    // Boot 4 autoconfigura Jackson 3 (tools.jackson); no hay bean del
    // ObjectMapper clasico, se usa el mapper compartido de common.
    private final ObjectMapper mapper = SapJsonMapper.mapper();

    private final SyncArticleUseCase syncUseCase;

    public ArticleKafkaListener(SyncArticleUseCase syncUseCase) {
        this.syncUseCase = syncUseCase;
    }

    @KafkaListener(topics = "${article.kafka.topic:outbox.ARTICLE}",
                   groupId = "${article.kafka.group:article-consumer}")
    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {
        log.info("Kafka article recibido key={} offset={}", record.key(), record.offset());
        var node = mapper.readTree(record.value());
        IngestionMessage msg = new IngestionMessage(
                node.path("entityId").asText(),
                "article",
                OperationType.valueOf(node.path("operation").asText("UPDATE")),
                IngestionOrigin.CDC,
                node.path("payloadHash").asText(),
                node.path("payload").toString());
        if (msg.operation() == OperationType.DELETE) {
            // Article no tiene use case de borrado: se descarta explicitamente.
            log.warn("Operacion DELETE no soportada para article, se descarta entityId={}", msg.entityId());
            return;
        }
        syncUseCase.execute(msg);
    }
}
