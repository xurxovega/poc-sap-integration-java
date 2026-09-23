package com.poc.sap.common.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec sdd/common/maquina-de-estados.md AC-20/AC-22 (auditoria 2026-09-18 N2).
 * Una colision entre instancias es transitoria y debe poder reintentarse; el
 * mensaje tiene que decir QUE paso para que el log sirva de algo.
 */
class ConcurrentTransitionExceptionTest {

    /** AC-20: el mensaje nombra el estado declarado y el real, y el ciclo en conflicto. */
    @Test
    void messageNamesTheDeclaredAndTheRealState() {
        ConcurrentTransitionException stale = ConcurrentTransitionException.staleHead(
                "customer", "C-1", SyncState.FETCHING, SyncState.RECEIVED, 4L);
        ConcurrentTransitionException foreign = ConcurrentTransitionException.foreignCycle(
                "customer", "C-1", "cyc-A", "cyc-B", 4L);

        assertThat(stale.getMessage()).contains("customer", "C-1", "FETCHING", "RECEIVED");
        assertThat(stale.declaredFrom()).isEqualTo(SyncState.FETCHING);
        assertThat(stale.actualFrom()).isEqualTo(SyncState.RECEIVED);
        assertThat(stale.seq()).isEqualTo(4L);
        assertThat(foreign.getMessage()).contains("cyc-A", "cyc-B");
        assertThat(foreign.expectedCycle()).isEqualTo("cyc-A");
        assertThat(foreign.actualCycle()).isEqualTo("cyc-B");
    }

    /**
     * AC-22: NUNCA extiende IllegalStateException. Esa es la clase declarada no
     * reintentable en KafkaErrorHandlingConfig: heredar de ella seria mandar a la
     * DLT un fallo puramente transitorio.
     */
    @Test
    void isNotAnIllegalStateException() {
        ConcurrentTransitionException e = ConcurrentTransitionException.staleHead(
                "customer", "C-1", SyncState.FETCHING, null, 1L);

        assertThat(e).isInstanceOf(RuntimeException.class).isNotInstanceOf(IllegalStateException.class);
        assertThat(e.domain()).isEqualTo("customer");
        assertThat(e.entityId()).isEqualTo("C-1");
        assertThat(e.getMessage()).contains("ninguno");
    }
}
