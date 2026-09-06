package com.yeqimin.computehub.domain;

import java.util.Map;
import java.util.Set;

import static com.yeqimin.computehub.domain.InstanceStatus.CREATING;
import static com.yeqimin.computehub.domain.InstanceStatus.DELETED;
import static com.yeqimin.computehub.domain.InstanceStatus.DELETING;
import static com.yeqimin.computehub.domain.InstanceStatus.DELETE_FAILED;
import static com.yeqimin.computehub.domain.InstanceStatus.FAILED;
import static com.yeqimin.computehub.domain.InstanceStatus.RESTARTING;
import static com.yeqimin.computehub.domain.InstanceStatus.REQUESTED;
import static com.yeqimin.computehub.domain.InstanceStatus.RUNNING;
import static com.yeqimin.computehub.domain.InstanceStatus.STARTING;
import static com.yeqimin.computehub.domain.InstanceStatus.STOPPED;
import static com.yeqimin.computehub.domain.InstanceStatus.STOPPING;

public final class InstanceStateMachine {
  private static final Map<String, Set<String>> TRANSITIONS = Map.of(
      "REQUESTED", Set.of("DISPATCHING", "FAILED"),
      "DISPATCHING", Set.of("RUNNING", "FAILED", "UNKNOWN"),
      "UNKNOWN", Set.of("DISPATCHING", "RUNNING", "FAILED"),
      "RUNNING", Set.of(),
      "FAILED", Set.of()
  );

  private InstanceStateMachine() {}

  public static TransitionPlan begin(InstanceStatus current, InstanceOperation operation) {
    return switch (operation) {
      case CREATE -> current == REQUESTED
          ? new TransitionPlan(current, CREATING, RUNNING, operation)
          : illegalOperation();
      case STOP -> current == RUNNING
          ? new TransitionPlan(current, STOPPING, STOPPED, operation)
          : illegalOperation();
      case START -> current == STOPPED
          ? new TransitionPlan(current, STARTING, RUNNING, operation)
          : illegalOperation();
      case RESTART -> current == RUNNING
          ? new TransitionPlan(current, RESTARTING, RUNNING, operation)
          : illegalOperation();
      case DELETE -> Set.of(RUNNING, STOPPED, FAILED, DELETE_FAILED).contains(current)
          ? new TransitionPlan(current, DELETING, DELETED, operation)
          : illegalOperation();
      case RECONCILE -> illegalOperation();
    };
  }

  private static TransitionPlan illegalOperation() {
    throw new IllegalStateException("illegal lifecycle operation");
  }

  public static InstanceStatus success(TransitionPlan plan) {
    return plan.target();
  }

  public static InstanceStatus failure(TransitionPlan plan) {
    return switch (plan.operation()) {
      case CREATE -> FAILED;
      case DELETE -> DELETE_FAILED;
      case START, STOP, RESTART -> plan.previous();
      case RECONCILE -> throw new IllegalStateException("illegal lifecycle operation");
    };
  }

  public static boolean canTransition(String from, String to) {
    return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
  }
}
