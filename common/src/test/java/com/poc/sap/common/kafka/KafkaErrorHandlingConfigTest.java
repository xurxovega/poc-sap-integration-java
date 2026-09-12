package com.poc.sap.common.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de la {@link KafkaErrorHandlingConfig} (TECH.md §6; compartida por todos los dominios desde la Fase 7). C5/B1 de la
 * auditoria: una transicion ilegal, un mensaje malformado o una operacion
 * desconocida no son fallos transitorios; reintentarlos tres veces con backoff
 * solo retrasa su llegada a la DLT y anade ruido.
 */
@ExtendWith(MockitoExtension.class)
class KafkaErrorHandlingConfigTest {

    @Mock KafkaTemplate<Object, Object> kafkaTemplate;

    @Test
    void nonTransientExceptionsAreDeclaredNotRetryable() {
        assertThat(KafkaErrorHandlingConfig.NOT_RETRYABLE).containsExactlyInAnyOrder(
                IllegalStateException.class,
                IllegalArgumentException.class,
                JsonProcessingException.class);
    }

    @Test
    void handlerIsBuiltWithRecovererAndBackoff() {
        DefaultErrorHandler handler = new KafkaErrorHandlingConfig().kafkaErrorHandler(kafkaTemplate);
        assertThat(handler).isNotNull();
    }
}
