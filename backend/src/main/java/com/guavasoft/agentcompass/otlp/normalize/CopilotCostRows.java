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
package com.guavasoft.agentcompass.otlp.normalize;

import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.MetricPointEntity;

import java.util.List;

/**
 * What {@link CopilotTelemetryNormalizer#costRowsFrom} derives from a batch of Copilot {@code chat}
 * spans: the two shapes Claude Code's two cost pipelines read.
 *
 * @param apiRequestLogs one {@code api_request} log per usage-bearing model call; the exact per-call
 *     pipeline (Cost page, {@code trace_costs} / {@code span_costs})
 * @param costPoints one {@code claude_code.cost.usage} point per call that cost something, each in a
 *     stream of its own; the counter pipeline (Usage Calendar, Trends, session cost). These are not
 *     persisted yet: they must be saved through {@code OtlpMetricService#persist} so they receive a
 *     {@code value_delta}.
 */
public record CopilotCostRows(List<LogRecordEntity> apiRequestLogs, List<MetricPointEntity> costPoints) {
}
