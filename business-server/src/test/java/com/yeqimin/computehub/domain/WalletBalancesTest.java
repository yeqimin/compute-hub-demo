package com.yeqimin.computehub.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WalletBalancesTest {
  @Test void freezesAvailableMoneyWithoutChangingTotal(){var next=new WalletBalances(10_000,0).freeze(2_500);assertThat(next.availableCent()).isEqualTo(7_500);assertThat(next.frozenCent()).isEqualTo(2_500);}
  @Test void rejectsFreezeBeyondAvailableMoney(){assertThatThrownBy(()->new WalletBalances(100,0).freeze(101)).isInstanceOf(IllegalArgumentException.class);}
  @Test void unfreezesMoneyAfterExplicitFailure(){assertThat(new WalletBalances(7_500,2_500).unfreeze(2_500)).isEqualTo(new WalletBalances(10_000,0));}
  @Test void deductsOnlyFromFrozenMoney(){assertThat(new WalletBalances(7_500,2_500).deduct(2_500)).isEqualTo(new WalletBalances(7_500,0));}
}
