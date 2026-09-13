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

@Schema(name = "OllamaConnectionTestResult", description = "Always 200 — a failed connection is a "
        + "normal outcome for this check, not a server error, so it is reported in the body rather "
        + "than as a 503.")
public record OllamaConnectionTestResult(
        @Schema(description = "Whether Ollama answered") boolean success,

        @Schema(description = "Human-readable outcome, safe to show directly to the user",
                example = "Could not reach Ollama at http://localhost:11434 — is it running?")
        String message) {
}
