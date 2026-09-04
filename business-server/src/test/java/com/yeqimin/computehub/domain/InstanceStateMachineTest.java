package com.yeqimin.computehub.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InstanceStateMachineTest {
  @Test void allowsSuccessAfterDispatching() {
    assertThat(InstanceStateMachine.canTransition("DISPATCHING", "RUNNING")).isTrue();
  }

  @Test void acceptsLateSuccessFromUnknown() {
    assertThat(InstanceStateMachine.canTransition("UNKNOWN", "RUNNING")).isTrue();
  }

  @Test void rejectsTerminalStateRegression() {
    assertThat(InstanceStateMachine.canTransition("RUNNING", "REQUESTED")).isFalse();
  }
}
