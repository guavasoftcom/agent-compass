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

@Schema(description = "Agent profiles present in telemetry plus the one a request resolves to when it "
        + "names none, so the picker can mark the default without hardcoding a profile name.")
public record AgentProfilesResponse(
        @Schema(description = "The configured primary profile (tuning.primary-agent-profile)",
                example = "claude-code") String primaryAgentProfile,

        @Schema(description = "Every profile with at least one row, newest first") List<AgentProfileUsage> profiles) {
}
