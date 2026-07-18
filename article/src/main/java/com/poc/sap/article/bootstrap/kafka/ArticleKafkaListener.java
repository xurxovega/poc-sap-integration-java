package com.poc.sap.article.bootstrap.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.sap.article.application.SyncArticleUseCase;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ArticleKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(ArticleKafkaListener.class);

    private final SyncArticleUseCase syncUseCase;
    private final ObjectMapper mapper = new ObjectMapper();

    public ArticleKafkaListener(SyncArticleUseCase syncUseCase) {
        this.syncUseCase = syncUseCase;
    }

    @KafkaListener(topics = "${article.kafka.topic:outbox.ARTICLE}",
                   groupId = "${article.kafka.group:article-consumer}")
    public void onMessage(ConsumerRecord<String, String> record) {
        log.info("Kafka article recibido key={} offset={}", record.key(), record.offset());
        try {
            var node = mapper.readTree(record.value());
            IngestionMessage msg = new IngestionMessage(
                    node.path("entityId").asText(),
                    "article",
                    OperationType.valueOf(node.path("operation").asText("UPDATE")),
                    IngestionOrigin.CDC,
                    node.path("payloadHash").asText(),
                    node.path("payload").toString());
            syncUseCase.execute(msg);
        } catch (Exception e) {
            log.error("Error procesando Kafka article key={}", record.key(), e);
        }
    }
}