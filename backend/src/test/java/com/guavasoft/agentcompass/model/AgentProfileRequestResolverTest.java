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

import com.guavasoft.agentcompass.config.TuningProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link AgentProfileRequestResolver} in isolation -- the read-side sibling of
 * {@code AgentProfileResolverTest}, which does the same for the ingest-side resolver.
 */
class AgentProfileRequestResolverTest {

  private final AgentProfileRequestResolver resolver =
      new AgentProfileRequestResolver(new TuningProperties());

  @Test
  void anAbsentProfileResolvesToTheConfiguredPrimary() {
    assertThat(resolver.resolve(null)).isEqualTo(AgentProfiles.CLAUDE_CODE);
  }

  @Test
  void aBlankProfileResolvesToTheConfiguredPrimary() {
    // A query param bound as an empty string (?agentProfile=) is reachable from real requests,
    // unlike a Java-level null, and must resolve the same way -- blank is "absent," not a profile.
    assertThat(resolver.resolve("")).isEqualTo(AgentProfiles.CLAUDE_CODE);
    assertThat(resolver.resolve("   ")).isEqualTo(AgentProfiles.CLAUDE_CODE);
  }

  @Test
  void anExplicitProfilePassesThroughUnchanged() {
    assertThat(resolver.resolve("unknown")).isEqualTo("unknown");
    assertThat(resolver.resolve("some-future-profile")).isEqualTo("some-future-profile");
  }

  @Test
  void readsTheConfiguredPrimaryRatherThanAssumingClaudeCode() {
    TuningProperties overridden = new TuningProperties();
    overridden.setPrimaryAgentProfile("my-profile");
    AgentProfileRequestResolver configuredResolver = new AgentProfileRequestResolver(overridden);

    assertThat(configuredResolver.resolve(null)).isEqualTo("my-profile");
  }
}
