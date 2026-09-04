package com.yeqimin.computehub.config;

import com.yeqimin.computehub.persistence.AuthMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DemoDataInitializer implements ApplicationRunner {
  private final AuthMapper mapper; private final PasswordEncoder encoder;
  public DemoDataInitializer(AuthMapper mapper, PasswordEncoder encoder) { this.mapper=mapper; this.encoder=encoder; }
  @Override public void run(ApplicationArguments args) {
    mapper.initializePassword("admin", encoder.encode("Admin@123"));
    mapper.initializePassword("tenant_admin", encoder.encode("Tenant@123"));
    mapper.initializePassword("viewer", encoder.encode("Viewer@123"));
  }
}
