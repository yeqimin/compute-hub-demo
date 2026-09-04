package com.yeqimin.computehub.domain;

import java.util.Map;
import java.util.Set;

public final class InstanceStateMachine {
  private static final Map<String, Set<String>> TRANSITIONS = Map.of(
      "REQUESTED", Set.of("DISPATCHING", "FAILED"),
      "DISPATCHING", Set.of("RUNNING", "FAILED", "UNKNOWN"),
      "UNKNOWN", Set.of("DISPATCHING", "RUNNING", "FAILED"),
      "RUNNING", Set.of(),
      "FAILED", Set.of()
  );

  private InstanceStateMachine() {}

  public static boolean canTransition(String from, String to) {
    return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
  }
}
