package com.poc.sap.customer.bootstrap.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.common.domain.PayloadHasher;
import com.poc.sap.customer.application.general.DeleteCustomerUseCase;
import com.poc.sap.customer.application.general.SyncCustomerUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.util.Locale;

/**
 * Listener Kafka para CDC (Debezium) del dominio Customer (TECH.md §6).
 * Topic por dominio: outbox.CUSTOMER.
 *
 * <p><b>Mensaje fino</b> (ADR-0013): el aviso solo lleva la identidad del cambio
 * ({@code entityId}, {@code operation} y, si vienen, {@code occurredAt}/{@code version}).
 * El estado actual lo relee el use case del legacy. Los mensajes antiguos que
 * todavia traigan {@code payloadHash} y {@code payload} se siguen aceptando: se
 * parsean, pero no son fuente de datos.
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

    // concurrency declarado (ADR-0011): hilos por instancia. La regla es
    // particiones >= instancias x concurrency; con 12 particiones y 3 hilos
    // caben 4 instancias entre los dos clusters. Un solo consumer group
    // compartido por todos los clusters: grupos distintos harian que cada
    // cluster escribiera el mismo cambio en SAP.
    @KafkaListener(topics = "${customer.kafka.topic:outbox.CUSTOMER}",
                   groupId = "${customer.kafka.group:customer-consumer}",
                   concurrency = "${customer.kafka.concurrency:3}")
    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {
        log.info("Kafka recibido topic={} key={} offset={}",
                record.topic(), record.key(), record.offset());
        if (record.value() == null) {
            // Tombstone de Kafka (compactacion o borrado de clave): no es un mensaje
            // de negocio. Antes producia IllegalArgumentException y tres reintentos.
            log.info("Tombstone ignorado topic={} key={} offset={}", record.topic(), record.key(), record.offset());
            return;
        }
        var node = mapper.readTree(record.value());
        IngestionMessage msg = new IngestionMessage(
                node.path("entityId").asText(),
                "customer",
                OperationType.valueOf(node.path("operation").asText("UPDATE").toUpperCase(Locale.ROOT)),
                IngestionOrigin.CDC,
                node.hasNonNull("payloadHash") ? node.get("payloadHash").asText() : null,
                node.hasNonNull("payload") ? node.get("payload").toString() : null);
        if (msg.operation() == OperationType.DELETE) {
            // La baja no tiene snapshot que releer: la fila ya no esta en el legacy.
            // Si el mensaje no trae hash, se deriva de la identidad (ADR-0013).
            String hash = msg.payloadHash() != null
                    ? msg.payloadHash()
                    : PayloadHasher.ofIdentity("customer", msg.entityId(), "DELETE");
            deleteUseCase.execute(msg.entityId(), hash);
        } else {
            syncUseCase.execute(msg);
        }
    }
}
