package com.poc.sap.common.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncStateMachineTest {

    private final SyncStateMachine machine = new SyncStateMachine();

    @Test
    void happyPathReachesSentSap() {
        assertThat(machine.transition(SyncState.RECEIVED, SyncState.FETCHING)).isEqualTo(SyncState.FETCHING);
        assertThat(machine.transition(SyncState.FETCHING, SyncState.VALIDATING)).isEqualTo(SyncState.VALIDATING);
        assertThat(machine.transition(SyncState.VALIDATING, SyncState.VALID)).isEqualTo(SyncState.VALID);
        assertThat(machine.transition(SyncState.VALID, SyncState.INDEXING)).isEqualTo(SyncState.INDEXING);
        assertThat(machine.transition(SyncState.INDEXING, SyncState.INDEXED)).isEqualTo(SyncState.INDEXED);
        assertThat(machine.transition(SyncState.INDEXED, SyncState.SENDING_SAP)).isEqualTo(SyncState.SENDING_SAP);
        assertThat(machine.transition(SyncState.SENDING_SAP, SyncState.SENT_SAP)).isEqualTo(SyncState.SENT_SAP);
        assertThat(machine.isTerminal(SyncState.SENT_SAP)).isTrue();
    }

    @Test
    void invalidBranchIsTerminal() {
        machine.transition(SyncState.VALIDATING, SyncState.INVALID);
        assertThat(machine.isTerminal(SyncState.INVALID)).isTrue();
    }

    @Test
    void initialTransitionAllowsReceivedAndValidating() {
        assertThat(machine.canTransition(null, SyncState.RECEIVED)).isTrue();
        assertThat(machine.canTransition(null, SyncState.VALIDATING)).isTrue();
        assertThat(machine.transition(null, SyncState.RECEIVED)).isEqualTo(SyncState.RECEIVED);
    }

    @Test
    void initialTransitionRejectsOtherStates() {
        assertThat(machine.canTransition(null, SyncState.SENT_SAP)).isFalse();
        assertThat(machine.canTransition(null, SyncState.INDEXING)).isFalse();
    }

    @Test
    void terminalStatesAllowResyncToReceived() {
        assertThat(machine.canTransition(SyncState.SENT_SAP, SyncState.RECEIVED)).isTrue();
        assertThat(machine.canTransition(SyncState.INVALID, SyncState.RECEIVED)).isTrue();
    }

    @Test
    void sendingSapAllowsInvalidWhenFeatureFailsValidation() {
        assertThat(machine.canTransition(SyncState.SENDING_SAP, SyncState.INVALID)).isTrue();
    }

    @Test
    void validAllowsDirectSentSapWhenNoRealChanges() {
        assertThat(machine.canTransition(SyncState.VALID, SyncState.SENT_SAP)).isTrue();
    }

    @Test
    void rejectsInvalidTransition() {
        assertThatThrownBy(() -> machine.transition(SyncState.RECEIVED, SyncState.SENT_SAP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Transicion no permitida");
    }

    @Test
    void errorStatesCanRecover() {
        assertThat(machine.canTransition(SyncState.ERROR, SyncState.RECEIVED)).isTrue();
        assertThat(machine.canTransition(SyncState.COMMUNICATION_ERROR, SyncState.SENDING_SAP)).isTrue();
        assertThat(machine.canTransition(SyncState.SAP_ERROR, SyncState.SENDING_SAP)).isTrue();
    }

    @Test
    void nextStatesOfReceivedAreFetchingAndError() {
        assertThat(machine.nextStates(SyncState.RECEIVED))
                .containsExactlyInAnyOrder(SyncState.FETCHING, SyncState.ERROR);
    }

    @Test
    void syncStateOfCodeRoundtrips() {
        for (SyncState s : SyncState.values()) {
            assertThat(SyncState.ofCode(s.code())).isEqualTo(s);
        }
    }

    @Test
    void syncStateOfCodeUnknownThrows() {
        assertThatThrownBy(() -> SyncState.ofCode(999))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
