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
package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The per-request point list behind the Metrics distribution scatter plot. The frontend plots every
 * point and computes p50/p95/p99 itself, so no bucketing or percentile math travels in the payload.
 *
 * <p>Built from the exact per-call {@code api_request} log records (the "request" pipeline), not the
 * cumulative counters behind {@link MetricSeries}. The two pipelines do not reconcile, so these points
 * will not add up to {@code MetricSeries.sum}; each figure names its source rather than blending them.
 */
@Schema(name = "MetricDistribution",
        description = "Per-request points of one metric (tokens or cost) over a window, ascending by "
                + "timestamp and capped to the newest 2,000 requests. Sourced from the exact per-call "
                + "api_request logs, so it deliberately does NOT reconcile with the counter-based "
                + "MetricSeries.sum.")
public record MetricDistribution(
        @Schema(description = "One point per request, oldest first; only the server-chosen exemplars carry "
                + "a traceId")
        List<DistributionPoint> points) {
}
