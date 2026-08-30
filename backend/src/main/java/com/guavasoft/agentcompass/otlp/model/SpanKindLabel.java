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

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public enum SpanKindLabel {
  INTERNAL(Span.SpanKind.SPAN_KIND_INTERNAL, "internal"),
  SERVER(Span.SpanKind.SPAN_KIND_SERVER, "server"),
  CLIENT(Span.SpanKind.SPAN_KIND_CLIENT, "client"),
  PRODUCER(Span.SpanKind.SPAN_KIND_PRODUCER, "producer"),
  CONSUMER(Span.SpanKind.SPAN_KIND_CONSUMER, "consumer");

  private static final Map<Span.SpanKind, SpanKindLabel> BY_PROTO_KIND = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(
          kindLabel -> kindLabel.protoKind, kindLabel -> kindLabel));

  private final Span.SpanKind protoKind;
  private final String label;

  SpanKindLabel(Span.SpanKind protoKind, String label) {
    this.protoKind = protoKind;
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static String labelFor(Span.SpanKind protoKind) {
    SpanKindLabel mapped = BY_PROTO_KIND.get(protoKind);
    return mapped == null ? null : mapped.label();
  }
}
