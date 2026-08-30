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
import java.util.List;

@Schema(description = "Whether telemetry is still arriving, per OTLP signal. A stale newestReceivedAt "
        + "means the agent stopped exporting or the collector cannot reach this server — the dashboard "
        + "pages themselves would keep rendering the last data they have without saying so.")
public record IngestHealth(
        @Schema(description = "One entry per signal: logs, metrics, traces") List<SignalIngest> signals,

        @Schema(description = "When these figures were read", example = "2026-08-23T11:47:31Z")
        Instant measuredAt) {
}
