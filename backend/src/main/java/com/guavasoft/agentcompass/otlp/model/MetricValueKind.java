package com.guavasoft.agentcompass.otlp.model;

public enum MetricValueKind {
  DOUBLE("double"),
  LONG("long"),
  UNKNOWN("unknown");

  private final String key;

  MetricValueKind(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
