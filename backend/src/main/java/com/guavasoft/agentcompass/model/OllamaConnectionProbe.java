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

@Schema(name = "OllamaConnectionProbe", description = "Body for POST /api/system/ollama/test-connection "
        + "and POST /api/system/ollama/models. A null/blank field means \"use the currently effective "
        + "value for that field\" rather than clearing anything — neither endpoint writes. There is no "
        + "enabled field: both probes check reachability regardless of whether the feature is currently "
        + "on, so a caller has nothing to pass for it.")
public record OllamaConnectionProbe(
        @Schema(description = "Base URL to probe, or null/blank to use the currently effective value",
                example = "http://localhost:11434", nullable = true) String baseUrl,

        @Schema(description = "Model to probe, or null/blank to use the currently effective value",
                example = "qwen2.5:14b", nullable = true) String model) {

    /**
     * {@code probe}, or an all-null instance when the body was omitted entirely — the shape
     * {@code @RequestBody(required = false)} leaves {@code probe} in on
     * {@code /ollama/test-connection} and {@code /ollama/models}, both of which read "no body" the
     * same as "every field defaulted". Centralizes the null-check so the two controller methods
     * that need it can't drift on what an absent body defaults to.
     */
    public static OllamaConnectionProbe orEmpty(OllamaConnectionProbe probe) {
        return probe == null ? new OllamaConnectionProbe(null, null) : probe;
    }
}
