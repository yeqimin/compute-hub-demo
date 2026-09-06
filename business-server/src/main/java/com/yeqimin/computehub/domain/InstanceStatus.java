package com.yeqimin.computehub.domain;

public enum InstanceStatus {
  REQUESTED, CREATING, RUNNING, STOPPING, STOPPED,
  STARTING, RESTARTING, DELETING, DELETED, FAILED,
  DELETE_FAILED, UNKNOWN
}
