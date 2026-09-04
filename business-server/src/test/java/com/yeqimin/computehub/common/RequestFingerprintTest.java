package com.yeqimin.computehub.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestFingerprintTest {
  @Test void isStableForEquivalentJsonFieldOrder() {
    assertThat(RequestFingerprint.of("{\"b\":2,\"a\":1}"))
        .isEqualTo(RequestFingerprint.of("{\"a\":1,\"b\":2}"));
  }

  @Test void changesWhenPayloadChanges() {
    assertThat(RequestFingerprint.of("{\"a\":1}"))
        .isNotEqualTo(RequestFingerprint.of("{\"a\":2}"));
  }
}
