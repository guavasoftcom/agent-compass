package com.guavasoft.agentcompass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TelemetryApplication {

  public static void main(String[] args) {
    SpringApplication.run(TelemetryApplication.class, args);
  }
}
