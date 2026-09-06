package com.yeqimin.computehub.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yeqimin.computehub.engine.MockEngineCallbackClient.CallbackEvent;
import com.yeqimin.computehub.proto.InstanceOperation;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MockEngineCallbackClientTest {
  private static final String SECRET = "callback-contract-secret";

  private final BlockingQueue<CapturedRequest> requests = new ArrayBlockingQueue<>(1);
  private HttpServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/callback", this::capture);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void sendsSignedCallbackBodyWithoutLeakingDestinationUrl() throws Exception {
    String callbackUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
    CallbackEvent event = new CallbackEvent(
        "ENGEVT-command-1-STOPPED",
        "command-1",
        InstanceOperation.STOP,
        "SUCCEEDED",
        "STOPPED",
        "STOPPED",
        "eng-existing",
        "operation complete",
        callbackUrl);

    new MockEngineCallbackClient(SECRET).send(event);

    CapturedRequest request = requests.poll(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
    assertThat(request).isNotNull();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.contentType()).isEqualTo("application/json");
    JsonNode body = new ObjectMapper().readTree(request.body());
    assertThat(body.path("eventId").asText()).isEqualTo("ENGEVT-command-1-STOPPED");
    assertThat(body.path("commandId").asText()).isEqualTo("command-1");
    assertThat(body.path("operation").asText()).isEqualTo("STOP");
    assertThat(body.path("result").asText()).isEqualTo("SUCCEEDED");
    assertThat(body.path("instanceState").asText()).isEqualTo("STOPPED");
    assertThat(body.path("status").asText()).isEqualTo("STOPPED");
    assertThat(body.has("callbackUrl")).isFalse();
    assertThat(request.timestamp()).matches("\\d+");
    assertThat(request.signature()).isEqualTo(sign(request.timestamp(), request.body()));
  }

  private void capture(HttpExchange exchange) {
    try (exchange) {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(new CapturedRequest(
          exchange.getRequestMethod(),
          exchange.getRequestHeaders().getFirst("Content-Type"),
          exchange.getRequestHeaders().getFirst("X-Engine-Timestamp"),
          exchange.getRequestHeaders().getFirst("X-Engine-Signature"),
          body));
      exchange.sendResponseHeaders(200, 0);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static String sign(String timestamp, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(
        mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
  }

  private record CapturedRequest(
      String method, String contentType, String timestamp, String signature, String body) {}
}
