package com.poc.sap.customer.bootstrap.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.customer.application.general.DeleteCustomerUseCase;
import com.poc.sap.customer.application.general.SyncCustomerUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listener Kafka para CDC (Debezium) del dominio Customer (TECH.md §6).
 * Topic por dominio: outbox.CUSTOMER. Idempotente por payloadHash.
 * Los errores se propagan al error handler del contenedor (DLT + backoff).
 */
@Component
public class CustomerKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerKafkaListener.class);

    // Boot 4 autoconfigura Jackson 3 (tools.jackson); no hay bean del
    // ObjectMapper clasico, se usa el mapper compartido de common.
    private final ObjectMapper mapper = SapJsonMapper.mapper();

    private final SyncCustomerUseCase syncUseCase;
    private final DeleteCustomerUseCase deleteUseCase;

    public CustomerKafkaListener(SyncCustomerUseCase syncUseCase,
                                 DeleteCustomerUseCase deleteUseCase) {
        this.syncUseCase = syncUseCase;
        this.deleteUseCase = deleteUseCase;
    }

    @KafkaListener(topics = "${customer.kafka.topic:outbox.CUSTOMER}",
                   groupId = "${customer.kafka.group:customer-consumer}")
    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {
        log.info("Kafka recibido topic={} key={} offset={}",
                record.topic(), record.key(), record.offset());
        var node = mapper.readTree(record.value());
        IngestionMessage msg = new IngestionMessage(
                node.path("entityId").asText(),
                "customer",
                OperationType.valueOf(node.path("operation").asText("UPDATE")),
                IngestionOrigin.CDC,
                node.path("payloadHash").asText(),
                node.path("payload").toString());
        if (msg.operation() == OperationType.DELETE) {
            deleteUseCase.execute(msg.entityId(), msg.payloadHash());
        } else {
            syncUseCase.execute(msg);
        }
    }
}
