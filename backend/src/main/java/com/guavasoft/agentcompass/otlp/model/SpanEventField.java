/*
 * Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not,
 * see <https://www.gnu.org/licenses/>.
 */
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
