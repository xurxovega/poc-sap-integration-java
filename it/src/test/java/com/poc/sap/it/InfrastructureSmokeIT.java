package com.poc.sap.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test de la infraestructura de integracion (TECH.md §10, OPS-010 AC-2).
 * Verifica que las dependencias externas (Redpanda, Mongo) arrancan via
 * Testcontainers. Se deshabilita si no hay Docker disponible.
 *
 * <p>Se activa con {@code -Ddocker.available=true} (lo establece el usuario o
 * un detector automatico). En CI sin Docker se omite sin fallo.
 *
 * <p>OPS-010: el broker se sustituye de Confluent Kafka por Redpanda
 * (Testcontainers {@code redpanda} module, pin a la misma versión que
 * compose/K8s: {@code v25.3.9}). El modulo {@code redpanda} de Testcontainers
 * arranca la imagen de un solo nodo en modo dev.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class InfrastructureSmokeIT {

    /**
     * OPS-010: Redpanda sustituye a Confluent Kafka. Mismo wire (Kafka 3.x),
     * un solo contenedor, sin ZooKeeper. La imagen se mantiene pinada a la
     * version declarada en {@code docs/sdd/common/broker-de-mensajeria.md}.
     */
    @Container
    static final RedpandaContainer redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v25.3.9");

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Test
    void redpandaAndMongoAreReachable() {
        assertThat(redpanda.getBootstrapServers()).contains(":");
        assertThat(mongo.getReplicaSetUrl()).startsWith("mongodb://");
    }
}
