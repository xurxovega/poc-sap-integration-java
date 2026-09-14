package com.poc.sap.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncNotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notificacion de fallo parcial (ADR-0010): SIEMPRE un WARN estructurado con la
 * lista de partes OK y fallidas (Loki/Grafana pueden alertar sobre el), y si
 * hay Kafka, un mensaje JSON en {@code app.notifications.topic} para que un
 * consumidor (operacion, correo, ticket) lo recoja. Un fallo al publicar no
 * tumba el ciclo: se registra y se sigue.
 */
@Component
public class KafkaSyncNotificationAdapter implements SyncNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaSyncNotificationAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KafkaTemplate<Object, Object> kafka;
    private final String topic;
    private final boolean kafkaEnabled;

    public KafkaSyncNotificationAdapter(ObjectProvider<KafkaTemplate<Object, Object>> kafka,
                                        @Value("${app.notifications.topic:sap.sync.alerts}") String topic,
                                        @Value("${app.notifications.kafka.enabled:true}") boolean kafkaEnabled) {
        this.kafka = kafka.getIfAvailable();
        this.topic = topic;
        this.kafkaEnabled = kafkaEnabled;
    }

    @Override
    public void partialFailure(String domain, String entityId, String payloadHash, Map<String, SyncState> featureResults) {
        List<String> ok = featureResults.entrySet().stream().filter(e -> e.getValue() == SyncState.SENT_SAP).map(Map.Entry::getKey).toList();
        List<String> failed = featureResults.entrySet().stream().filter(e -> e.getValue() != SyncState.SENT_SAP)
                .map(e -> e.getKey() + "=" + e.getValue()).toList();
        log.warn("ALERTA sincronizacion parcial domain={} entityId={} payloadHash={} ok={} fallidas={}",
                domain, entityId, payloadHash, ok, failed);
        if (!kafkaEnabled || kafka == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "SYNC_PARTIAL_FAILURE");
            event.put("domain", domain);
            event.put("entityId", entityId);
            event.put("payloadHash", payloadHash);
            event.put("ok", ok);
            event.put("failed", failed);
            event.put("features", featureResults);
            event.put("at", Instant.now().toString());
            kafka.send(topic, entityId, JSON.writeValueAsString(event));
        } catch (Exception e) {
            log.error("No se pudo publicar la alerta de sincronizacion parcial en {} entityId={}", topic, entityId, e);
        }
    }
}
