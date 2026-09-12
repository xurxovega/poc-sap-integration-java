package com.poc.sap.common.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.List;

/**
 * Manejo de errores del consumo Kafka, comun a todos los dominios (plan Fase 7:
 * antes una copia identica por app): reintentos con backoff exponencial
 * (3 intentos, 1 s inicial, x2) y publicacion en {@code <topic>-dlt} al agotarlos.
 * Las apps lo cargan al escanear {@code com.poc.sap.common}.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    /**
     * Excepciones que no merecen reintento: transicion ilegal de la maquina de
     * estados, mensaje malformado u operacion desconocida. El resto (Mongo, ES,
     * SAP caidos, circuito abierto) SI se reintenta (auditoria C5/B1).
     */
    public static final List<Class<? extends Exception>> NOT_RETRYABLE = List.of(
            IllegalStateException.class,
            IllegalArgumentException.class,
            JsonProcessingException.class);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        var backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxAttempts(3);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NOT_RETRYABLE.toArray(new Class[0]));
        return handler;
    }
}
