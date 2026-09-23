package com.poc.sap.common.kafka;

import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.port.SyncNotificationPort;
import com.poc.sap.common.sap.json.SapJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notificacion de fallo parcial (ADR-0010): SIEMPRE un WARN estructurado con la
 * lista de partes OK y fallidas (Loki/Grafana pueden alertar sobre el), y si
 * hay Kafka, un mensaje JSON en {@code app.notifications.topic} para que un
 * consumidor (operacion, correo, ticket) lo recoja. Un fallo al publicar no
 * tumba el ciclo: se registra y se sigue.
 *
 * <p>El mensaje lleva la traza de pasos del ciclo: {@code cycleId}, el estado del
 * agregado y, por cada parte, su estado y su motivo. Conserva {@code ok} y
 * {@code failed} de la version anterior, que cuestan cero y evitan romper a un
 * consumidor existente (OPS-8 / 2A-11).
 */
@Component
public class KafkaSyncNotificationAdapter implements SyncNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaSyncNotificationAdapter.class);

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
    public void partialFailure(SyncPartialFailure event) {
        List<String> ok = event.attempts().stream().filter(FeatureOutcome::ok)
                .map(FeatureOutcome::feature).toList();
        List<String> failed = event.attempts().stream().filter(a -> !a.ok())
                .map(a -> a.feature() + "=" + a.state()).toList();
        log.warn("ALERTA sincronizacion parcial domain={} entityId={} cycleId={} payloadHash={} estado={} ok={} fallidas={}",
                event.domain(), event.entityId(), event.cycleId(), event.payloadHash(),
                event.aggregateState(), ok, failed);
        if (!kafkaEnabled || kafka == null) {
            return;
        }
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "SYNC_PARTIAL_FAILURE");
            message.put("domain", event.domain());
            message.put("entityId", event.entityId());
            message.put("cycleId", event.cycleId());
            message.put("payloadHash", event.payloadHash());
            message.put("aggregateState", String.valueOf(event.aggregateState()));
            message.put("ok", ok);
            message.put("failed", failed);
            message.put("attempts", steps(event.attempts()));
            message.put("at", String.valueOf(event.at()));
            kafka.send(topic, event.entityId(), SapJsonMapper.write(message));
        } catch (Exception e) {
            log.error("No se pudo publicar la alerta de sincronizacion parcial en {} entityId={}",
                    topic, event.entityId(), e);
        }
    }

    /** Un paso por parte, en el orden en que se intentaron. */
    private static List<Map<String, Object>> steps(List<FeatureOutcome> attempts) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (FeatureOutcome a : attempts) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("feature", a.feature());
            step.put("state", String.valueOf(a.state()));
            if (a.detail() != null) {
                step.put("detail", a.detail());
            }
            step.put("at", String.valueOf(a.at()));
            steps.add(step);
        }
        return steps;
    }
}
