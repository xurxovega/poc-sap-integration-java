package com.poc.sap.customer.bootstrap.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.customer.application.general.SyncCustomerUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listener Kafka para CDC (Debezium) del dominio Customer (TECH.md §6).
 * Topic por dominio: outbox.CUSTOMER. Idempotente por payloadHash.
 */
@Component
public class CustomerKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerKafkaListener.class);

    private final SyncCustomerUseCase syncUseCase;
    private final ObjectMapper mapper = new ObjectMapper();

    public CustomerKafkaListener(SyncCustomerUseCase syncUseCase) {
        this.syncUseCase = syncUseCase;
    }

    @KafkaListener(topics = "${customer.kafka.topic:outbox.CUSTOMER}",
                   groupId = "${customer.kafka.group:customer-consumer}")
    public void onMessage(ConsumerRecord<String, String> record) {
        log.info("Kafka recibido topic={} key={} offset={}",
                record.topic(), record.key(), record.offset());
        try {
            var node = mapper.readTree(record.value());
            IngestionMessage msg = new IngestionMessage(
                    node.path("entityId").asText(),
                    "customer",
                    OperationType.valueOf(node.path("operation").asText("UPDATE")),
                    IngestionOrigin.CDC,
                    node.path("payloadHash").asText(),
                    node.path("payload").toString());
            syncUseCase.execute(msg);
        } catch (Exception e) {
            log.error("Error procesando mensaje Kafka customer key={}", record.key(), e);
        }
    }
}