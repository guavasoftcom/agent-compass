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
package com.guavasoft.agentcompass.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for Ollama's {@code POST /api/generate}. {@code stream: true} always — see
 * {@link OllamaClient} for why there is no buffered variant.
 *
 * <p><b>{@code options.num_ctx} is not optional here, despite being optional in Ollama's API.</b>
 * Ollama applies its own default context window (4096 tokens on current releases, 2048 on older
 * ones) when the field is absent, and a prompt longer than that window is <i>silently truncated
 * from the left</i> — no error, no warning, no field in the response saying it happened. The
 * trace-analysis prompt puts its instructions and trace summary at the top, so left-truncation
 * drops exactly the part that tells the model what to do and keeps the raw trace tail, producing a
 * plausible-looking but unanchored answer. Sending an explicit {@code num_ctx} sized to
 * {@code ollama.context-tokens} is what keeps {@code ollama.max-prompt-chars} an actually
 * enforceable budget rather than a number this application believes while Ollama quietly ignores
 * it.
 *
 * <p>{@code format} carries a JSON Schema when {@code ollama.structured-output} is on, constraining
 * the answer to that shape; it is {@code null} — and omitted from the body entirely, hence
 * {@link JsonInclude} — on the default prose path. An Ollama old enough not to know the field
 * ignores it rather than rejecting the request, so the caller has to detect that from the answer it
 * gets back; see {@code OllamaProperties#structuredOutput}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OllamaGenerateRequest(
        String model,
        String prompt,
        boolean stream,
        OllamaGenerateOptions options,
        Object format) {

    /** Per-call model options. Only the context window is set; everything else stays Ollama's default. */
    public record OllamaGenerateOptions(@JsonProperty("num_ctx") int numCtx) {
    }
}
