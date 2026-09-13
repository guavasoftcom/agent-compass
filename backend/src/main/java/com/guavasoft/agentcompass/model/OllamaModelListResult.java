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

@Schema(name = "OllamaModelListResult", description = "Always 200 — a failed connection is a normal "
        + "outcome for this check, not a server error, so it is reported in the body rather than as "
        + "a 503.")
public record OllamaModelListResult(
        @Schema(description = "Whether Ollama answered") boolean success,

        @Schema(description = "Human-readable outcome, safe to show directly to the user",
                example = "Could not reach Ollama at http://localhost:11434 — is it running?")
        String message,

        @Schema(description = "Models installed on the Ollama server, empty (never null) on failure")
        List<OllamaModelSummary> models) {

    @Schema(name = "OllamaModelSummary", description = "One model installed on the Ollama server, "
            + "with its reported parameter size so the operator can be warned before picking a large "
            + "one.")
    public record OllamaModelSummary(
            @Schema(description = "The model's tag as Ollama identifies it", example = "llama3.1:latest")
            String name,

            @Schema(description = "Ollama's raw parameter-size string, as reported — null when Ollama "
                    + "did not report one", example = "8.0B")
            String parameterSize,

            @Schema(description = "The parsed parameter count in billions, derived from "
                    + "parameterSize — null when parameterSize is null or does not match a recognized "
                    + "shape (a plain \"<number>B\", or a mixture-of-experts \"<number>x<number>B\", "
                    + "whose two numbers are multiplied together)", example = "8.0")
            Double parameterCountBillions) {
    }
}
