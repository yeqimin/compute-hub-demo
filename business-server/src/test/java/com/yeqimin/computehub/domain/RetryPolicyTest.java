package com.yeqimin.computehub.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RetryPolicyTest {
  @Test void usesTwoFourEightSecondBackoff(){assertThat(RetryPolicy.delaySeconds(1)).isEqualTo(2);assertThat(RetryPolicy.delaySeconds(2)).isEqualTo(4);assertThat(RetryPolicy.delaySeconds(3)).isEqualTo(8);}
  @Test void stopsAfterThirdFailure(){assertThat(RetryPolicy.exhausted(3)).isTrue();assertThat(RetryPolicy.exhausted(2)).isFalse();}
}
