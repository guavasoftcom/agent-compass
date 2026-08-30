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
package com.guavasoft.agentcompass.otlp.util;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helpers for decoding OTLP common types into Java values
 * suitable for storing in Postgres jsonb columns.
 */
public final class OtlpAttributeUtils {

  private static final String HEX_BYTE_FORMAT = "%02x";
  private static final int HEX_CHARS_PER_BYTE = 2;
  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private OtlpAttributeUtils() {
  }

  public static Map<String, Object> toMap(List<KeyValue> keyValues) {
    Map<String, Object> map = new HashMap<>();
    for (KeyValue keyValue : keyValues) {
      map.put(keyValue.getKey(), unwrap(keyValue.getValue()));
    }
    return map;
  }

  public static Object unwrap(AnyValue anyValue) {
    return switch (anyValue.getValueCase()) {
      case STRING_VALUE -> anyValue.getStringValue();
      case BOOL_VALUE -> anyValue.getBoolValue();
      case INT_VALUE -> anyValue.getIntValue();
      case DOUBLE_VALUE -> anyValue.getDoubleValue();
      case ARRAY_VALUE -> anyValue.getArrayValue().getValuesList().stream()
          .map(OtlpAttributeUtils::unwrap)
          .toList();
      case KVLIST_VALUE -> toMap(anyValue.getKvlistValue().getValuesList());
      case BYTES_VALUE -> anyValue.getBytesValue().toByteArray();
      default -> null;
    };
  }

  public static String flattenToString(AnyValue anyValue) {
    Object raw = unwrap(anyValue);
    return raw == null ? null : raw.toString();
  }

  public static Instant nanosToInstant(long nanos) {
    long seconds = nanos / NANOS_PER_SECOND;
    long nanoOfSecond = nanos % NANOS_PER_SECOND;
    return Instant.ofEpochSecond(seconds, nanoOfSecond);
  }

  public static String toHexOrNull(ByteString bytes) {
    if (bytes == null || bytes.isEmpty()) {
      return null;
    }
    StringBuilder hexBuilder = new StringBuilder(bytes.size() * HEX_CHARS_PER_BYTE);
    for (byte byteValue : bytes.toByteArray()) {
      hexBuilder.append(String.format(HEX_BYTE_FORMAT, byteValue & 0xff));
    }
    return hexBuilder.toString();
  }
}
