package com.poc.sap.common.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.sap.SapCircuitOpenException;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.SapLookupUnavailableException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de la {@link KafkaErrorHandlingConfig} (TECH.md §6; compartida por todos los dominios desde la Fase 7). C5/B1 de la
 * auditoria: una transicion ilegal, un mensaje malformado o una operacion
 * desconocida no son fallos transitorios; reintentarlos tres veces con backoff
 * solo retrasa su llegada a la DLT y anade ruido.
 *
 * <p>Las reintentables van DECLARADAS, no por omision (anexo 05 §5,
 * ADR-0011): un refactor que hiciera heredar de IllegalStateException las
 * mandaria a la DLT sin que ningun test se pusiera rojo.
 */
@ExtendWith(MockitoExtension.class)
class KafkaErrorHandlingConfigTest {

    private static final ConsumerRecord<Object, Object> ANY_RECORD =
            new ConsumerRecord<>("outbox.CUSTOMER", 0, 0L, "C-1", "{}");

    @Mock KafkaTemplate<Object, Object> kafkaTemplate;

    @Test
    void nonTransientExceptionsAreDeclaredNotRetryable() {
        assertThat(KafkaErrorHandlingConfig.NOT_RETRYABLE).containsExactlyInAnyOrder(
                IllegalStateException.class,
                IllegalArgumentException.class,
                JsonProcessingException.class);
    }

    /** ADR-0011: la colision entre instancias se reintenta; nunca va a la DLT al primer intento. */
    @Test
    void concurrentTransitionIsDeclaredRetryable() {
        assertThat(KafkaErrorHandlingConfig.RETRYABLE).contains(ConcurrentTransitionException.class);
        assertThat(KafkaErrorHandlingConfig.NOT_RETRYABLE).doesNotContain(ConcurrentTransitionException.class);
    }

    /** sdd/common/resiliencia-cliente-sap.md R-3 / AC-16: el circuito abierto es transitorio. */
    @Test
    void circuitOpenIsDeclaredRetryable() {
        assertThat(KafkaErrorHandlingConfig.RETRYABLE).contains(SapCircuitOpenException.class);
        assertThat(KafkaErrorHandlingConfig.NOT_RETRYABLE).doesNotContain(SapCircuitOpenException.class);
    }

    /**
     * sdd/common/upsert-idempotente-sap.md R-3: si la verificacion previa no
     * concluye, la parte queda en COMMUNICATION_ERROR y <b>no se toco SAP</b>:
     * reentregar el mensaje es seguro y ademas es lo unico que puede resolverlo.
     * Sin declararla, hereda de RuntimeException y solo se reintenta por omision.
     */
    @Test
    void lookupUnavailableIsDeclaredRetryable() {
        assertThat(KafkaErrorHandlingConfig.RETRYABLE).contains(SapLookupUnavailableException.class);
        assertThat(KafkaErrorHandlingConfig.NOT_RETRYABLE).doesNotContain(SapLookupUnavailableException.class);
    }

    /**
     * sdd/common/resiliencia-cliente-sap.md AC-16: con el circuito abierto 30 s, los
     * 1+2+4 s del backoff normal no cubren la ventana y el mensaje llegaria a la DLT
     * con SAP aun caido. El circuito abierto usa su propio backoff, mas largo.
     */
    @Test
    void circuitOpenUsesTheLongerBackOff() {
        ExponentialBackOff standard = KafkaErrorHandlingConfig.standardBackOff(1_000L, 3);
        ExponentialBackOff circuitOpen = KafkaErrorHandlingConfig.circuitOpenBackOff(30_000L, 3);
        var backOffFunction = KafkaErrorHandlingConfig.backOffFunction(standard, circuitOpen);

        BackOff forCircuitOpen = backOffFunction.apply(
                ANY_RECORD, new SapCircuitOpenException(SapDestination.S4_NATIVE, null));
        BackOff forAnythingElse = backOffFunction.apply(
                ANY_RECORD, new ConcurrentTransitionException("customer", "C-1", 3L, null));

        assertThat(forCircuitOpen).isSameAs(circuitOpen);
        assertThat(forAnythingElse).isSameAs(standard);
        assertThat(circuitOpen.getInitialInterval()).isGreaterThan(standard.getInitialInterval());
    }

    @Test
    void handlerIsBuiltWithRecovererAndBackoff() {
        DefaultErrorHandler handler = new KafkaErrorHandlingConfig()
                .kafkaErrorHandler(kafkaTemplate, 1_000L, 3, 30_000L);
        assertThat(handler).isNotNull();
    }
}
