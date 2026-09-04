package com.yeqimin.computehub.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class CallbackSigner {
  private final byte[] secret;

  public CallbackSigner(String secret) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  public String sign(String timestamp, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot sign callback", e);
    }
  }

  public boolean verify(String timestamp, String body, String signature) {
    if (signature == null) return false;
    return MessageDigest.isEqual(sign(timestamp, body).getBytes(StandardCharsets.UTF_8),
        signature.getBytes(StandardCharsets.UTF_8));
  }
}
