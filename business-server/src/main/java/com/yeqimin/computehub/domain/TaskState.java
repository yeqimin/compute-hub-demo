package com.yeqimin.computehub.domain;

public enum TaskState {
  PENDING, PUBLISHED, PROCESSING, RETRY_WAIT, WAITING_CALLBACK,
  SUCCEEDED, FAILED, UNKNOWN, DEAD
}
