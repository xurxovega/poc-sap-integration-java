package com.poc.sap.customer.bootstrap;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ConfigurationCondition;

/**
 * Cuando esta disponible el adaptador de lectura del Business Partner
 * (decision D-18, spec {@code docs/sdd/common/upsert-idempotente-sap.md} §3).
 *
 * <p>Hasta ahora dependia solo de {@code sap.odata.read.enabled}, que por defecto
 * esta en {@code false}. Desde que la verificacion previa es <b>obligatoria</b>
 * para escribir el agregado por OData, el lector tiene que estar activo tambien
 * cuando lo este el escritor ({@code sap.odata.customer.enabled}): un interruptor
 * de solo lectura no puede apagar la unica pieza que evita duplicar el alta.
 *
 * <p>{@code @ConditionalOnProperty} no sabe hacer un OR, de ahi esta clase.
 */
public class BusinessPartnerReadEnabled extends AnyNestedCondition {

    public BusinessPartnerReadEnabled() {
        super(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
    }

    /** Lectura pedida explicitamente (consulta de BPs, PRD-9). */
    @ConditionalOnProperty(name = "sap.odata.read.enabled", havingValue = "true")
    static class ReadExplicitlyEnabled {
    }

    /** Escritura del agregado por OData: el lookup previo la necesita. */
    @ConditionalOnProperty(name = "sap.odata.customer.enabled", havingValue = "true")
    static class WritingTheAggregateOverOData {
    }
}
