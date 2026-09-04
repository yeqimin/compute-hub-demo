package com.yeqimin.computehub.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandRecoveryPolicyTest {
  @Test
  void reschedulesAcceptedCommandsAfterRestart() {
    assertThat(CommandRecoveryPolicy.action("ACCEPTED", "SUCCESS"))
        .isEqualTo(CommandRecoveryPolicy.Action.RESCHEDULE);
  }

  @Test
  void redeliversFinalCallbacksButPreservesTimeoutForReconciliation() {
    assertThat(CommandRecoveryPolicy.action("RUNNING", "SUCCESS"))
        .isEqualTo(CommandRecoveryPolicy.Action.REDELIVER_CALLBACK);
    assertThat(CommandRecoveryPolicy.action("FAILED", "FAIL"))
        .isEqualTo(CommandRecoveryPolicy.Action.REDELIVER_CALLBACK);
    assertThat(CommandRecoveryPolicy.action("RUNNING", "TIMEOUT"))
        .isEqualTo(CommandRecoveryPolicy.Action.NONE);
  }
}
