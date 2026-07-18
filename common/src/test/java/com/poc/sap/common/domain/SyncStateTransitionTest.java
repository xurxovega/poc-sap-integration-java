package com.poc.sap.common.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

class SyncStateTransitionTest {

    @Test
    void buildsWithDefaultsTimestamp() {
        SyncStateTransition t = new SyncStateTransition(
                "C-1", "customer", null, SyncState.RECEIVED, "cdc", "hash123", null);
        assertThat(t.entityId()).isEqualTo("C-1");
        assertThat(t.domain()).isEqualTo("customer");
        assertThat(t.from()).isNull();
        assertThat(t.to()).isEqualTo(SyncState.RECEIVED);
        assertThat(t.origin()).isEqualTo("cdc");
        assertThat(t.payloadHash()).isEqualTo("hash123");
        assertThat(t.timestamp()).isNotNull();
    }

    @Test
    void rejectsBlankEntityId() {
        assertThatThrownBy(() -> new SyncStateTransition(
                "", "customer", null, SyncState.RECEIVED, "cdc", "h", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullTo() {
        assertThatThrownBy(() -> new SyncStateTransition(
                "C-1", "customer", null, null, "cdc", "h", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
