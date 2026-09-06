package com.yeqimin.computehub.engine;

final class CommandRecoveryPolicy {
  enum Action { RESCHEDULE, NONE }

  private CommandRecoveryPolicy() {}

  static Action action(String status, String scenario) {
    if ("ACCEPTED".equals(status) || "PROCESSING".equals(status)) {
      return Action.RESCHEDULE;
    }
    return Action.NONE;
  }
}
