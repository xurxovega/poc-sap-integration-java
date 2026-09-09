package com.poc.sap.common.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorStateRecoveryTest {

    private final SyncStateMachine machine = new SyncStateMachine();

    @Test
    void errorAllowsRecover() {
        assertThat(machine.canTransition(SyncState.ERROR, SyncState.RECEIVED)).isTrue();
    }

    @Test
    void communicationErrorAllowsRetrySending() {
        assertThat(machine.canTransition(SyncState.COMMUNICATION_ERROR, SyncState.SENDING_SAP)).isTrue();
    }

    @Test
    void communicationErrorAllowsRetryFetching() {
        assertThat(machine.canTransition(SyncState.COMMUNICATION_ERROR, SyncState.FETCHING)).isTrue();
    }

    @Test
    void sapErrorAllowsRetrySending() {
        assertThat(machine.canTransition(SyncState.SAP_ERROR, SyncState.SENDING_SAP)).isTrue();
    }

    @Test
    void receivedCannotGoDirectlyToSentSap() {
        assertThat(machine.canTransition(SyncState.RECEIVED, SyncState.SENT_SAP)).isFalse();
    }

    /**
     * SENT_SAP cierra el ciclo pero admite re-entrada con un evento nuevo, y por
     * el estado de entrada de cada pipeline: RECEIVED el agregado, VALIDATING una
     * linea de feature (sdd/common/maquina-de-estados.md R-3, R-4).
     */
    @Test
    void sentSapIsTerminalButAllowsResync() {
        assertThat(machine.isTerminal(SyncState.SENT_SAP)).isTrue();
        assertThat(machine.nextStates(SyncState.SENT_SAP))
                .containsExactlyInAnyOrder(SyncState.RECEIVED, SyncState.VALIDATING);
    }

    @Test
    void invalidIsTerminal() {
        assertThat(machine.isTerminal(SyncState.INVALID)).isTrue();
    }

    @Test
    void errorTerminalFalse() {
        assertThat(machine.isTerminal(SyncState.ERROR)).isFalse();
    }

    @Test
    void nextStatesOf_VALIDATE_containsValidAndInvalid() {
        assertThat(machine.nextStates(SyncState.VALIDATING))
                .contains(SyncState.VALID, SyncState.INVALID);
    }
}