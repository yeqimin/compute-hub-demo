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
  void reschedulesClaimableProcessingCommandsAfterTheirLeaseExpires() {
    assertThat(CommandRecoveryPolicy.action("PROCESSING", "SUCCESS"))
        .isEqualTo(CommandRecoveryPolicy.Action.RESCHEDULE);
  }

  @Test
  void neverRedeliversCompletedCommandsAfterRestart() {
    assertThat(CommandRecoveryPolicy.action("RUNNING", "SUCCESS"))
        .isEqualTo(CommandRecoveryPolicy.Action.NONE);
    assertThat(CommandRecoveryPolicy.action("FAILED", "FAIL"))
        .isEqualTo(CommandRecoveryPolicy.Action.NONE);
    assertThat(CommandRecoveryPolicy.action("RUNNING", "TIMEOUT"))
        .isEqualTo(CommandRecoveryPolicy.Action.NONE);
  }
}
