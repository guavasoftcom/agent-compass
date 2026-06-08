package com.guavasoft.agentcompass.otlp.model;

import io.opentelemetry.proto.trace.v1.Span;

import com.guavasoft.agentcompass.otlp.util.OtlpAttributeUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum SpanEventField {
  NAME("name", Span.Event::getName),
  TIMESTAMP("timestamp", event -> OtlpAttributeUtils.nanosToInstant(event.getTimeUnixNano()).toString()),
  ATTRIBUTES("attributes", event -> OtlpAttributeUtils.toMap(event.getAttributesList()));

  private final String key;
  private final Function<Span.Event, Object> extractor;

  SpanEventField(String key, Function<Span.Event, Object> extractor) {
    this.key = key;
    this.extractor = extractor;
  }

  public String key() {
    return key;
  }

  public static Map<String, Object> toJsonMap(Span.Event event) {
    return Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(
        field -> field.key, field -> field.extractor.apply(event)));
  }
}
