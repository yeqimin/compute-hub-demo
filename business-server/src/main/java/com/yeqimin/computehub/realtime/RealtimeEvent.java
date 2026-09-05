package com.yeqimin.computehub.realtime;

public record RealtimeEvent(
    long id,
    Long tenantId,
    String type,
    String aggregateType,
    long aggregateId,
    String payload) {}
