package com.poc.sap.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test de la infraestructura de integracion (TECH.md §10).
 * Verifica que las dependencias externas (Kafka, Mongo) arrancan via
 * Testcontainers. Se deshabilita si no hay Docker disponible.
 *
 * <p>Se activa con {@code -Ddocker.available=true} (lo establece el usuario o
 * un detector automatico). En CI sin Docker se omite sin fallo.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class InfrastructureSmokeIT {

    /**
     * {@code org.testcontainers.containers.KafkaContainer(String)} esta
     * deprecado y en Testcontainers 1.21 duplica el nombre de la imagen
     * ("cp-kafka:confluentinc/cp-kafka:7.7.1"), asi que nunca arrancaba.
     */
    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Test
    void kafkaAndMongoAreReachable() {
        assertThat(kafka.getBootstrapServers()).contains(":");
        assertThat(mongo.getReplicaSetUrl()).startsWith("mongodb://");
    }
}