package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Arrival statistics for one OTLP signal. newestReceivedAt is read off the "
        + "newest-by-timestamp row rather than as MAX(received_at): received_at carries no index on any "
        + "of the three tables, so the aggregate is a sequential scan (measured 349 ms) while the ordered "
        + "lookup rides the timestamp index (0.026 ms). Measured ingest lag on live data is ~2 ms with no "
        + "late-arrival skew, so the two agree in practice.")
public record SignalIngest(
        @Schema(description = "OTLP signal name", example = "metrics",
                allowableValues = {"logs", "metrics", "traces"}) String signal,

        @Schema(description = "Table backing this signal", example = "metric_points") String tableName,

        @Schema(description = "Timestamp of the newest row, or null when the table is empty. This is the "
                + "event time reported by the agent, not the time it reached this server.",
                example = "2026-08-23T11:47:29Z") Instant newestTimestamp,

        @Schema(description = "received_at of that same newest row — when this server persisted it. The "
                + "gap against newestTimestamp is ingest lag.", example = "2026-08-23T11:47:29Z")
        Instant newestReceivedAt,

        @Schema(description = "Rows with a timestamp in the last hour", example = "1842") long rowsLastHour,

        @Schema(description = "Rows with a timestamp in the last 24 hours", example = "44219")
        long rowsLastDay,

        @Schema(description = "Rows with a timestamp in the last seven days", example = "331520")
        long rowsLastWeek,

        @Schema(description = "Distinct values of this signal's name column, all time", example = "8")
        long nameCardinality,

        @Schema(description = "Which column nameCardinality counted, so the UI can label it",
                example = "metric_name") String nameCardinalityLabel,

        @Schema(description = "Distinct metric streams (metric_points.stream_id, the stored md5 of "
                + "metric name plus the full attribute set). Null for logs and traces, which have no "
                + "stream identity.", example = "4454") Long seriesCardinality) {
}
