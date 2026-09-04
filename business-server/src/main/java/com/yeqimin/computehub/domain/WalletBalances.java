package com.yeqimin.computehub.domain;

public record WalletBalances(long availableCent,long frozenCent){
  public WalletBalances{if(availableCent<0||frozenCent<0)throw new IllegalArgumentException("Wallet balance cannot be negative");}
  public WalletBalances freeze(long amount){positive(amount);if(availableCent<amount)throw new IllegalArgumentException("Insufficient balance");return new WalletBalances(availableCent-amount,Math.addExact(frozenCent,amount));}
  public WalletBalances unfreeze(long amount){positive(amount);if(frozenCent<amount)throw new IllegalArgumentException("Insufficient frozen balance");return new WalletBalances(Math.addExact(availableCent,amount),frozenCent-amount);}
  public WalletBalances deduct(long amount){positive(amount);if(frozenCent<amount)throw new IllegalArgumentException("Insufficient frozen balance");return new WalletBalances(availableCent,frozenCent-amount);}
  private static void positive(long amount){if(amount<=0)throw new IllegalArgumentException("Amount must be positive");}
}
