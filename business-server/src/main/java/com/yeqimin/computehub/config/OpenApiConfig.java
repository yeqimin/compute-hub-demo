package com.yeqimin.computehub.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
  @Bean OpenAPI computeHubApi(){return new OpenAPI().info(new Info().title("ComputeHub API").version("1.0.0").description("算力调度平台业务 API").contact(new Contact().name("yeqimin")));}
}
