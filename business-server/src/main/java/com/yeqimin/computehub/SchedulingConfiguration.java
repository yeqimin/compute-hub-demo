package com.yeqimin.computehub;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "compute-hub.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration { }
