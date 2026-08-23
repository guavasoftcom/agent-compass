package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Whether telemetry is still arriving, per OTLP signal. A stale newestReceivedAt "
        + "means the agent stopped exporting or the collector cannot reach this server — the dashboard "
        + "pages themselves would keep rendering the last data they have without saying so.")
public record IngestHealth(
        @Schema(description = "One entry per signal: logs, metrics, traces") List<SignalIngest> signals,

        @Schema(description = "When these figures were read", example = "2026-08-23T11:47:31Z")
        Instant measuredAt) {
}
