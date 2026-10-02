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
 * The two agent-profile names this application has to name in code rather than read from
 * configuration.
 *
 * <p>Profile names are otherwise operator-configurable (see {@code TuningProperties}' agent-profile
 * group) and nothing should hardcode one. These two exist because they are referenced from places
 * that cannot read configuration: {@link #CLAUDE_CODE} is the value {@code V37} backfills every
 * pre-existing row to, and {@link #UNKNOWN} is the compile-time field default on the three signal
 * entities.
 */
public final class AgentProfiles {

  /**
   * The only agent that wrote to this database before profiles existed, and what {@code V37}
   * backfills every historical row to. Also the default {@code primary-agent-profile}, i.e. what a
   * read resolves to when a request names none.
   */
  public static final String CLAUDE_CODE = "claude-code";

  /**
   * Telemetry whose emitting agent could not be identified. Deliberately a real, queryable profile
   * rather than null: such rows stay stored and inspectable while sitting outside every
   * Claude-Code-scoped aggregation.
   *
   * <p>This is also the compile-time default of the entities' {@code agentProfile} field, which is a
   * <em>structural</em> guarantee and not the same thing as the configurable ingest fallback the
   * resolver applies. The point of defaulting here rather than leaving the field null is that the
   * one outcome the partition must never produce is <em>mis-crediting</em> one agent's rows to
   * another; a field that defaults to "unidentified" cannot do that, whereas one defaulting to
   * {@link #CLAUDE_CODE} silently could. The database column is still {@code NOT NULL} with no
   * default of its own, so an insert path that bypasses the entities entirely — raw SQL, a
   * hand-written migration — still fails loudly rather than guessing.
   */
  public static final String UNKNOWN = "unknown";

  private AgentProfiles() {
  }
}
