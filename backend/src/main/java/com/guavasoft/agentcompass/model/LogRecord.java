package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "LogRecord", description = "One persisted OTLP log record, shaped for the dashboard grid")
public class LogRecord {

    @Schema(description = "Database primary key", example = "42")
    private Long id;

    @Schema(description = "Wall-clock timestamp of the log record (UTC)", example = "2026-05-12T17:30:00Z")
    private Instant timestamp;

    @Schema(description = "OTLP severity number (1-24). 1-4 trace, 5-8 debug, 9-12 info, 13-16 warn, 17-20 error, 21-24 fatal.",
            example = "17")
    private Integer severityNumber;

    @Schema(description = "Free-form severity label as emitted by the agent", example = "ERROR")
    private String severityText;

    @Schema(description = "Log body, flattened to a string regardless of the underlying AnyValue kind",
            example = "Edit failed: old_string not unique")
    private String body;

    @Schema(description = "Instrumentation scope name", example = "claude-code.events")
    private String scopeName;

    @Schema(description = "Hex-encoded OTLP trace ID for cross-signal correlation, if present",
            example = "0102030405060708090a0b0c0d0e0f10")
    private String traceId;

    @Schema(description = "Hex-encoded OTLP span ID for cross-signal correlation, if present", example = "1112131415161718")
    private String spanId;

    @Schema(description = "Log-record attributes (free-form key/value bag, stored as jsonb)",
            example = "{\"event.name\":\"tool_result\",\"tool\":\"Edit\"}")
    private Map<String, Object> attributes;

    @Schema(description = "Resource attributes inherited from the OTLP ResourceLogs envelope",
            example = "{\"service.name\":\"claude-code\"}")
    private Map<String, Object> resourceAttributes;
}
