package com.guavasoft.agentcompass.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Agent Compass API",
        version = "0.0.1",
        description = "OTLP/HTTP ingest, dashboard JSON, and markdown tuning report endpoints."))
public class OpenApiConfig {
}
