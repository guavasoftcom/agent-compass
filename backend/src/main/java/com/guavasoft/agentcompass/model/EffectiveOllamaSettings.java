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

@Schema(name = "EffectiveOllamaSettings", description = "The Ollama base URL, model, and "
        + "enabled flag actually in effect right now — a Settings-page override from "
        + "ollama_settings where one is stored, the ollama.base-url/ollama.model/ollama.enabled "
        + "application.yml default otherwise. Each field falls back independently, so a stored "
        + "override can replace just the model while the base URL still reads the default.")
public record EffectiveOllamaSettings(
        @Schema(description = "Effective base URL", example = "http://localhost:11434") String baseUrl,

        @Schema(description = "Effective model", example = "llama3.1") String model,

        @Schema(description = "Whether \"Analyze trace\" is currently enabled") boolean enabled,

        @Schema(description = "True when at least one of baseUrl/model/enabled is a stored override "
                + "rather than the application.yml default") boolean overridden) {
}
