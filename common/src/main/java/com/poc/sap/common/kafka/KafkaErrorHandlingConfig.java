package com.poc.sap.common.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.sap.SapCircuitOpenException;
import com.poc.sap.common.sap.SapLookupUnavailableException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Manejo de errores del consumo Kafka, comun a todos los dominios (plan Fase 7:
 * antes una copia identica por app): reintentos con backoff exponencial
 * (3 intentos, 1 s inicial, x2) y publicacion en {@code <topic>-dlt} al agotarlos.
 * Las apps lo cargan al escanear {@code com.poc.sap.common}.
 *
 * <p>La clasificacion de excepciones es EXPLICITA en las dos direcciones
 * (OVERVIEW.md §5; ADR-0011): lo reintentable declarado gana sobre la herencia,
 * de modo que un refactor que cambiara la jerarquia de
 * {@link ConcurrentTransitionException} se pone rojo en vez de mandar mensajes
 * recuperables a la DLT en silencio (auditoria 2B-2, anexo 05 §5).
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

    /**
     * Reintentables DECLARADAS. Hasta la revision 3 lo eran por omision y nadie
     * lo probaba (anexo 05 §5): una colision entre instancias
     * ({@link ConcurrentTransitionException}) y el circuito abierto hacia SAP
     * ({@link SapCircuitOpenException}, R-3 de resiliencia-cliente-sap.md) son
     * fallos transitorios que se recuperan releyendo y reintentando.
     *
     * <p>{@link SapLookupUnavailableException} (R-3 de upsert-idempotente-sap.md)
     * es el tercero: la verificacion previa no concluyo, asi que <b>no se toco
     * SAP</b> y reentregar el mensaje es seguro; ademas es lo unico que puede
     * resolverlo cuando SAP vuelva.
     */
    public static final List<Class<? extends Exception>> RETRYABLE = List.of(
            ConcurrentTransitionException.class,
            SapCircuitOpenException.class,
            SapLookupUnavailableException.class);

    /** Backoff normal: {@code backoffMs}, x2, hasta {@code maxAttempts} intentos. */
    public static ExponentialBackOff standardBackOff(long backoffMs, int maxAttempts) {
        var backOff = new ExponentialBackOff(backoffMs, 2.0);
        backOff.setMaxAttempts(maxAttempts);
        return backOff;
    }

    /**
     * Backoff del circuito abierto: arranca en la ventana de apertura del circuit
     * breaker ({@code sap.client.circuit-breaker.wait-duration-open-ms}) y crece
     * mas despacio (x1,5). Con el backoff normal (1+2+4 s) el mensaje llegaba a la
     * DLT con SAP todavia caido.
     */
    public static ExponentialBackOff circuitOpenBackOff(long circuitOpenBackoffMs, int maxAttempts) {
        var backOff = new ExponentialBackOff(circuitOpenBackoffMs, 1.5);
        backOff.setMaxAttempts(maxAttempts);
        return backOff;
    }

    /** Elige el backoff segun la excepcion: el largo solo para el circuito abierto. */
    public static BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> backOffFunction(
            BackOff standard, BackOff circuitOpen) {
        return (record, ex) -> ex instanceof SapCircuitOpenException ? circuitOpen : standard;
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${app.kafka.retry.backoff-ms:1000}") long backoffMs,
            @Value("${app.kafka.retry.max-attempts:3}") int maxAttempts,
            @Value("${app.kafka.retry.circuit-open-backoff-ms:30000}") long circuitOpenBackoffMs) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        var backOff = standardBackOff(backoffMs, maxAttempts);
        var circuitOpen = circuitOpenBackOff(circuitOpenBackoffMs, maxAttempts);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NOT_RETRYABLE.toArray(new Class[0]));
        // Declarado gana sobre la herencia: si manana ConcurrentTransitionException
        // heredara de IllegalStateException, seguiria reintentandose.
        handler.addRetryableExceptions(RETRYABLE.toArray(new Class[0]));
        handler.setBackOffFunction(backOffFunction(backOff, circuitOpen));
        return handler;
    }
}
