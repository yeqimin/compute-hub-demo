package com.yeqimin.computehub.engine;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CallbackSignerTest {
  @Test void signsAndVerifiesTimestampPlusBody() {
    var signer = new CallbackSigner("demo-secret");
    var signature = signer.sign("1720000000", "{\"eventId\":\"evt-1\"}");
    assertThat(signer.verify("1720000000", "{\"eventId\":\"evt-1\"}", signature)).isTrue();
    assertThat(signer.verify("1720000000", "{}", signature)).isFalse();
  }
}
