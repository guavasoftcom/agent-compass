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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One newline-delimited chunk of Ollama's {@code POST /api/generate} response. Every call this
 * application makes is a streaming one (see {@link OllamaClient}), so a whole answer arrives as a
 * sequence of these — {@code response} carrying the next fragment of text and {@code done} true on
 * the final chunk. A non-streaming answer is a single chunk with {@code done} already true, which
 * the same parser reads without a special case.
 *
 * <p>{@code error} is Ollama's in-band failure channel: a run that dies partway (a model unloaded,
 * an out-of-memory abort) reports it as a field on an otherwise-200 chunk rather than as an HTTP
 * error status, so a parser that only watches the status code reports a truncated answer as a
 * complete one.
 *
 * <p><b>{@code done} is a {@link Boolean}, not a {@code boolean}</b>, precisely because of that error
 * chunk: it carries {@code error} alone and no {@code done} at all, and Jackson passes {@code null}
 * for an absent record component — which a primitive rejects outright with a
 * {@code MismatchedInputException}. Declared primitive, the parser died on the very chunk that
 * exists to explain why the run died. Read it as {@code Boolean.TRUE.equals(...)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OllamaGenerateResponse(String model, String response, Boolean done, String error) {
}
