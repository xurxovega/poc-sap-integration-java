package com.poc.sap.common.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests de invariantes de {@link IngestionMessage} (SPEC.md §4).
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

    @Test
    void rejectsBlankPayloadHash() {
        assertThatThrownBy(() -> new IngestionMessage(
                "C-1", "customer", OperationType.CREATE,
                IngestionOrigin.CDC, "", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadHash");
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