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

import java.time.Instant;

@Schema(name = "DistributionPoint",
        description = "One api_request log record in the distribution window: when it happened, its "
                + "value (tokens or USD) and, for the few server-chosen exemplars only, a trace id.")
public record DistributionPoint(
        @Schema(description = "When the request was logged (ISO-8601)", example = "2026-09-19T08:12:40Z")
        Instant ts,
        @Schema(description = "The request's value: input + output + cache-creation + cache-read tokens for "
                + "the token metric, cost_usd for the cost metric", example = "13180")
        double value,
        @Schema(description = "Real trace id for click-through, set ONLY on the handful of exemplar points "
                + "(the maximum, the requests nearest the p50/p95/p99 values, and a failed request when "
                + "one carries a trace id); null on every other point",
                example = "7b22c0f4a1d94e6bb03a5e8f2c1f019d", nullable = true)
        String traceId,
        @Schema(description = "Span id of the llm_request span this request produced, within traceId, so a "
                + "click-through can land on that span rather than the top of the trace. Set only on "
                + "exemplar points, and null when the request carries no request id or its span has not "
                + "been ingested (the trace id alone is still a valid click-through)",
                example = "00f067aa0ba902b7", nullable = true)
        String spanId) {
}
