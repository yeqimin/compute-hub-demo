package com.yeqimin.computehub.domain;

public final class RetryPolicy {
  private RetryPolicy(){}
  public static int delaySeconds(int failureCount){if(failureCount<1)throw new IllegalArgumentException("failureCount must be positive");return 1<<Math.min(failureCount,3);}
  public static boolean exhausted(int failureCount){return failureCount>=3;}
}
