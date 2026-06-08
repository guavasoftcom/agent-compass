package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Window-level session statistics for the Sessions page stat cards. Computed "
        + "over every session in the window (not just the visible page) so the KPIs stay stable as "
        + "the user pages or re-sorts the grid. Percentiles use percentile_cont, matching the "
        + "linear-interpolation semantics the dashboard previously computed client-side.")
public record SessionKpis(
        @Schema(description = "Number of distinct sessions in the window", example = "128") long totalSessions,

        @Schema(description = "Median (P50) per-session cost in USD", example = "1.42") double medianCostUsd,

        @Schema(description = "P95 per-session cost in USD", example = "9.87") double p95CostUsd,

        @Schema(description = "Median per-session burn rate in USD per active minute, over sessions "
                + "with non-zero active time", example = "0.123") double medianCostPerActiveMinuteUsd,

        @Schema(description = "New-session count per evenly-spaced window bucket (sessions counted in "
                + "the bucket they first appeared), for the Total-sessions card sparkline. Bucket "
                + "counts sum to totalSessions.", example = "[0,1,3,2,5]") List<Long> sessionsTrend) {
}
