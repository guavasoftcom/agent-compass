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
package com.guavasoft.agentcompass.otlp.model;

import java.util.Map;

/**
 * The facts every record under one OTLP resource block shares: that block's decoded resource
 * attributes, and the agent profile resolved from them.
 *
 * <p>Exists so the three mappers can thread both down to their per-record {@code toEntity} without
 * growing a sixth parameter on an already-wide signature. The pairing is not incidental — the
 * profile is <em>derived from</em> these attributes (see
 * {@code AgentProfileResolver}), so carrying them separately invites resolving the profile at the
 * wrong level, which is the one mistake that matters here: resolution must happen once per resource
 * block, not once per record.
 *
 * @param resourceAttributes the block's decoded resource attributes, stored on every row as jsonb
 * @param agentProfile       the coding agent that emitted this block, never null
 */
public record ResourceContext(Map<String, Object> resourceAttributes, String agentProfile) {
}
