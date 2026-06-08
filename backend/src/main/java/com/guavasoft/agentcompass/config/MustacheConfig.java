package com.guavasoft.agentcompass.config;

import com.samskivert.mustache.Mustache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MustacheConfig {

  @Bean
  Mustache.Compiler mustacheCompiler() {
    // We emit markdown, not HTML — disable HTML escaping so backticks, angle
    // brackets, and ampersands pass through intact.
    return Mustache.compiler().escapeHTML(false).nullValue("");
  }
}
