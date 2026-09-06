package com.yeqimin.computehub.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeqimin.computehub.proto.InstanceOperation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MockEngineCallbackClient {
  private final String secret;
  private final ObjectMapper json;
  private final HttpClient http;

  @Autowired
  public MockEngineCallbackClient(@Value("${engine.callback-secret}") String secret) {
    this(secret, new ObjectMapper(), HttpClient.newHttpClient());
  }

  MockEngineCallbackClient(String secret, ObjectMapper json, HttpClient http) {
    this.secret = secret;
    this.json = json;
    this.http = http;
  }

  public void send(CallbackEvent event) {
    try {
      String body = json.writeValueAsString(event);
      String timestamp = String.valueOf(Instant.now().getEpochSecond());
      HttpRequest request = HttpRequest.newBuilder(URI.create(event.callbackUrl()))
          .header("Content-Type", "application/json")
          .header("X-Engine-Timestamp", timestamp)
          .header("X-Engine-Signature", sign(timestamp, body))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    } catch (Exception ignored) {
      // Command status remains queryable when callback delivery is unavailable.
    }
  }

  private String sign(String timestamp, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(
        mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
  }

  public record CallbackEvent(
      String eventId,
      String commandId,
      InstanceOperation operation,
      String result,
      String instanceState,
      String status,
      String engineInstanceId,
      String message,
      @JsonIgnore String callbackUrl) {}
}
