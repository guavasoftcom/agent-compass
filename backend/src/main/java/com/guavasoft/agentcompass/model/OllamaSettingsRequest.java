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

@Schema(name = "OllamaSettingsRequest", description = "Body for PUT /api/system/ollama-settings. A "
        + "null/blank field clears that field's override back to the application.yml default; all "
        + "three fields clear independently.")
public record OllamaSettingsRequest(
        @Schema(description = "Base URL to store as an override, or null/blank to clear it",
                example = "http://localhost:11434", nullable = true) String baseUrl,

        @Schema(description = "Model to store as an override, or null/blank to clear it",
                example = "qwen2.5:14b", nullable = true) String model,

        @Schema(description = "Enabled flag to store as an override, or null to clear it back to the "
                + "ollama.enabled application.yml default", nullable = true) Boolean enabled) {
}
