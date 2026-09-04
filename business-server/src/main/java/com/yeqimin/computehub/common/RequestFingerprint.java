package com.yeqimin.computehub.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class RequestFingerprint {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private RequestFingerprint() {}

  public static String of(String json) {
    try {
      JsonNode node = MAPPER.readTree(json);
      String canonical = MAPPER.writeValueAsString(normalize(node));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON payload", e);
    }
  }

  private static Object normalize(JsonNode node) {
    if (node.isObject()) {
      Map<String,Object> sorted = new TreeMap<>();
      node.fields().forEachRemaining(e -> sorted.put(e.getKey(), normalize(e.getValue())));
      return sorted;
    }
    if (node.isArray()) {
      List<Object> values = new ArrayList<>();
      node.forEach(value -> values.add(normalize(value)));
      return values;
    }
    return node;
  }
}
