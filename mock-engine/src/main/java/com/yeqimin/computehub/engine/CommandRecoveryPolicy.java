package com.yeqimin.computehub.engine;

final class CommandRecoveryPolicy {
  enum Action { RESCHEDULE, REDELIVER_CALLBACK, NONE }

  private CommandRecoveryPolicy() {}

  static Action action(String status, String scenario) {
    if ("ACCEPTED".equals(status)) return Action.RESCHEDULE;
    if (("RUNNING".equals(status) || "FAILED".equals(status)) && !"TIMEOUT".equals(scenario)) {
      return Action.REDELIVER_CALLBACK;
    }
    return Action.NONE;
  }
}
