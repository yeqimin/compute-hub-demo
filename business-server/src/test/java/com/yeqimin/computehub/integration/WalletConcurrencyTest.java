package com.yeqimin.computehub.integration;

import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class WalletConcurrencyTest {
  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8").withDatabaseName("test");

  @Test void concurrentChargesNeverMakeAvailableBalanceNegative() throws Exception {
    try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement()){
      s.execute("CREATE TABLE wallet(id BIGINT PRIMARY KEY, available BIGINT NOT NULL CHECK(available>=0))");s.execute("INSERT INTO wallet VALUES(1,100)");
    }
    try(var pool=Executors.newFixedThreadPool(10)){
      List<Future<Integer>> results=new ArrayList<>();
      for(int i=0;i<10;i++)results.add(pool.submit(()->{try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var p=c.prepareStatement("UPDATE wallet SET available=available-20 WHERE id=1 AND available>=20")){return p.executeUpdate();}}));
      int accepted=0;for(var result:results)accepted+=result.get();assertThat(accepted).isEqualTo(5);
    }
    try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement();var rs=s.executeQuery("SELECT available FROM wallet WHERE id=1")){rs.next();assertThat(rs.getLong(1)).isZero();}
  }
}
