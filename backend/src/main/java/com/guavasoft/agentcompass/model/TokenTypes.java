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

/**
 * The four values of the {@code type} attribute on the canonical token-usage counter
 * ({@code tuning.token-usage-metric}).
 *
 * <p>These are the actual spellings Claude Code emits (camelCase, not snake_case). They live here
 * so the dashboard services that read them and the Copilot normalizer that writes them cannot
 * drift apart: a canonical point whose type is spelled differently is silently ignored by every
 * token rollup.
 */
public final class TokenTypes {

  /** Prompt tokens that were neither read from nor written to the prompt cache. */
  public static final String INPUT = "input";

  /** Tokens the model generated. */
  public static final String OUTPUT = "output";

  /** Prompt tokens written to the prompt cache. */
  public static final String CACHE_CREATION = "cacheCreation";

  /** Prompt tokens served from the prompt cache. */
  public static final String CACHE_READ = "cacheRead";

  private TokenTypes() {
  }
}
