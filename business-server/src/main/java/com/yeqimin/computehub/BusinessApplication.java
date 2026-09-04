package com.yeqimin.computehub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.yeqimin.computehub.persistence")
public class BusinessApplication {
  public static void main(String[] args) { SpringApplication.run(BusinessApplication.class, args); }
}
