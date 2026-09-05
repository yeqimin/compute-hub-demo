package com.yeqimin.computehub.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static com.yeqimin.computehub.domain.InstanceOperation.*;
import static com.yeqimin.computehub.domain.InstanceStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstanceStateMachineTest {
  @ParameterizedTest
  @CsvSource({
      "REQUESTED,CREATE,CREATING,RUNNING",
      "RUNNING,STOP,STOPPING,STOPPED",
      "STOPPED,START,STARTING,RUNNING",
      "RUNNING,RESTART,RESTARTING,RUNNING",
      "RUNNING,DELETE,DELETING,DELETED",
      "DELETE_FAILED,DELETE,DELETING,DELETED"
  })
  void lifecycleTransitions(String current, String operation, String executing, String target) {
    TransitionPlan plan = InstanceStateMachine.begin(
        InstanceStatus.valueOf(current), InstanceOperation.valueOf(operation));

    assertThat(plan.previous()).isEqualTo(InstanceStatus.valueOf(current));
    assertThat(plan.operation()).isEqualTo(InstanceOperation.valueOf(operation));
    assertThat(plan.executing()).isEqualTo(InstanceStatus.valueOf(executing));
    assertThat(InstanceStateMachine.success(plan)).isEqualTo(InstanceStatus.valueOf(target));
  }

  @ParameterizedTest
  @CsvSource({
      "REQUESTED,CREATE,FAILED",
      "RUNNING,STOP,RUNNING",
      "STOPPED,START,STOPPED",
      "RUNNING,RESTART,RUNNING",
      "RUNNING,DELETE,DELETE_FAILED",
      "DELETE_FAILED,DELETE,DELETE_FAILED"
  })
  void lifecycleFailuresRestoreTheOperationSpecificStableState(
      String current, String operation, String expected) {
    TransitionPlan plan = InstanceStateMachine.begin(
        InstanceStatus.valueOf(current), InstanceOperation.valueOf(operation));

    assertThat(InstanceStateMachine.failure(plan)).isEqualTo(InstanceStatus.valueOf(expected));
  }

  @ParameterizedTest
  @CsvSource({
      "UNKNOWN,CREATE",
      "UNKNOWN,START",
      "UNKNOWN,STOP",
      "UNKNOWN,RESTART",
      "UNKNOWN,DELETE",
      "UNKNOWN,RECONCILE",
      "DELETED,CREATE",
      "DELETED,START",
      "DELETED,STOP",
      "DELETED,RESTART",
      "DELETED,DELETE",
      "DELETED,RECONCILE"
  })
  void rejectsLifecycleActionsFromUnknownAndDeleted(String current, String operation) {
    assertThatThrownBy(() -> InstanceStateMachine.begin(
        InstanceStatus.valueOf(current), InstanceOperation.valueOf(operation)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("illegal lifecycle operation");
  }

  @Test
  void cannotStartRunningInstance() {
    assertThatThrownBy(() -> InstanceStateMachine.begin(RUNNING, START))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("illegal lifecycle operation");
  }
}
