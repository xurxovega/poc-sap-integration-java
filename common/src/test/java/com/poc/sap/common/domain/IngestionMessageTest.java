package com.poc.sap.common.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests de invariantes de {@link IngestionMessage} (TECH.md §6).
 */
class IngestionMessageTest {

    @Test
    void buildsWithValidFields() {
        IngestionMessage m = new IngestionMessage(
                "C-1", "customer", OperationType.CREATE,
                IngestionOrigin.CDC, "hash-1", "{}");
        assertThat(m.entityId()).isEqualTo("C-1");
        assertThat(m.domain()).isEqualTo("customer");
        assertThat(m.operation()).isEqualTo(OperationType.CREATE);
        assertThat(m.origin()).isEqualTo(IngestionOrigin.CDC);
        assertThat(m.payloadHash()).isEqualTo("hash-1");
        assertThat(m.payload()).isEqualTo("{}");
    }

    @Test
    void rejectsBlankEntityId() {
        assertThatThrownBy(() -> new IngestionMessage(
                "", "customer", OperationType.CREATE,
                IngestionOrigin.CDC, "hash", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId");
    }

    @Test
    void rejectsBlankDomain() {
        assertThatThrownBy(() -> new IngestionMessage(
                "C-1", " ", OperationType.CREATE,
                IngestionOrigin.CDC, "hash", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domain");
    }

    @Test
    void rejectsNullOperation() {
        assertThatThrownBy(() -> new IngestionMessage(
                "C-1", "customer", null,
                IngestionOrigin.CDC, "hash", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
    }

    @Test
    void rejectsNullOrigin() {
        assertThatThrownBy(() -> new IngestionMessage(
                "C-1", "customer", OperationType.CREATE,
                null, "hash", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("origin");
    }

    /**
     * AC-4 (contrato-mensaje-de-cambio.md): el mensaje fino no lleva ni hash ni
     * payload. El hash lo calcula el consumidor sobre el snapshot que relee del
     * legacy, asi que dejar de exigirlo es parte del contrato, no una relajacion.
     */
    @Test
    void acceptsThinMessageWithoutHashAndWithoutPayload() {
        IngestionMessage m = new IngestionMessage(
                "C-1", "customer", OperationType.UPDATE,
                IngestionOrigin.CDC, null, null);
        assertThat(m.payloadHash()).isNull();
        assertThat(m.payload()).isNull();
        assertThat(m.entityId()).isEqualTo("C-1");
    }

    /** AC-4: un hash en blanco se normaliza a ausente; no hay "hash vacio". */
    @Test
    void normalizesBlankPayloadHashToNull() {
        IngestionMessage m = new IngestionMessage(
                "C-1", "customer", OperationType.CREATE,
                IngestionOrigin.CDC, "  ", "{}");
        assertThat(m.payloadHash()).isNull();
    }

    /** AC-4: el mensaje antiguo con hash y payload sigue siendo valido. */
    @Test
    void stillAcceptsTheLegacyMessageWithPayload() {
        IngestionMessage m = new IngestionMessage(
                "C-1", "customer", OperationType.CREATE,
                IngestionOrigin.CDC, "hash-1", "{\"id\":\"C-1\"}");
        assertThat(m.payloadHash()).isEqualTo("hash-1");
        assertThat(m.payload()).isEqualTo("{\"id\":\"C-1\"}");
    }

    /** AC-4: constructor corto del mensaje fino, sin hash ni payload. */
    @Test
    void thinFactoryBuildsMessageWithoutDataFields() {
        IngestionMessage m = IngestionMessage.thin(
                "C-1", "customer", OperationType.UPDATE, IngestionOrigin.CDC);
        assertThat(m.payloadHash()).isNull();
        assertThat(m.payload()).isNull();
    }

    @Test
    void operationTypeEnumHasThreeValues() {
        assertThat(OperationType.values()).hasSize(3);
        assertThat(OperationType.values()).containsExactly(
                OperationType.CREATE, OperationType.UPDATE, OperationType.DELETE);
    }

    @Test
    void ingestionOriginEnumHasThreeValues() {
        assertThat(IngestionOrigin.values()).hasSize(3);
        assertThat(IngestionOrigin.values()).containsExactly(
                IngestionOrigin.CDC, IngestionOrigin.KAFKA, IngestionOrigin.REST);
    }
}