package com.poc.sap.article.bootstrap.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Manejo de errores del consumo Kafka del dominio Article:
 * reintentos con backoff exponencial (3 intentos, 1s inicial, multiplicador 2)
 * y publicacion en dead-letter topic ({@code <topic>-dlt}) al agotarlos.
 */
@Configuration
public class KafkaErrorHandlingConfig {
    /**
     * Excepciones que no merecen reintento: transicion ilegal de la maquina de
     * estados, mensaje malformado u operacion desconocida. El resto (Mongo, ES,
     * SAP caidos, circuito abierto) SI se reintenta.
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
        // Un fallo NO transitorio va directo a la DLT: reintentarlo con backoff solo
        // retrasa su llegada y anade ruido (auditoria C5/B1).
        handler.addNotRetryableExceptions(NOT_RETRYABLE.toArray(new Class[0]));
        return handler;
    }
}
