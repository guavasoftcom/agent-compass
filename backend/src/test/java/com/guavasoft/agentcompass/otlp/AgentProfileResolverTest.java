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
package com.guavasoft.agentcompass.otlp;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.AgentProfiles;
import com.guavasoft.agentcompass.otlp.mapper.AgentProfileResolver;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link AgentProfileResolver} in isolation.
 *
 * <p>This exists separately from {@code AgentProfileIngestIntegrationTest} for a specific reason:
 * the entities default their {@code agentProfile} field to {@link AgentProfiles#UNKNOWN} as a
 * structural backstop, so an end-to-end test asserting "an unidentified resource lands as unknown"
 * would pass even if the resolver were never called at all. Testing the resolver directly is what
 * distinguishes "the resolver decided this" from "nothing set it".
 */
class AgentProfileResolverTest {

  private static final String SERVICE_NAME = "service.name";

  private final AgentProfileResolver resolver = new AgentProfileResolver(new TuningProperties());

  @Test
  void resolvesAConfiguredServiceNameToItsProfile() {
    assertThat(resolver.resolve(Map.of(SERVICE_NAME, "claude-code")))
        .isEqualTo(AgentProfiles.CLAUDE_CODE);
  }

  @Test
  void foldsTheDesktopVariantIntoTheSameProfile() {
    // Both values exist in the live database and are the same agent, which is why the resolution
    // map is many-to-one rather than an identity mapping.
    assertThat(resolver.resolve(Map.of(SERVICE_NAME, "claude-code-desktop")))
        .isEqualTo(AgentProfiles.CLAUDE_CODE);
  }

  @Test
  void foldsBothCopilotServiceNamesIntoOneProfile() {
    // A single VS Code window emits from both: the extension as copilot-chat, the agent host as
    // github-copilot. Mapping only one would quarantine half of Copilot's own telemetry.
    assertThat(resolver.resolve(Map.of(SERVICE_NAME, "copilot-chat")))
        .isEqualTo(AgentProfiles.GITHUB_COPILOT);
    assertThat(resolver.resolve(Map.of(SERVICE_NAME, "github-copilot")))
        .isEqualTo(AgentProfiles.GITHUB_COPILOT)
        .isNotEqualTo(AgentProfiles.CLAUDE_CODE);
  }

  @Test
  void quarantinesAnUnconfiguredServiceName() {
    assertThat(resolver.resolve(Map.of(SERVICE_NAME, "some-future-agent")))
        .isEqualTo(AgentProfiles.UNKNOWN)
        .isNotEqualTo(AgentProfiles.CLAUDE_CODE);
  }

  @Test
  void quarantinesAResourceThatNamesNoAgent() {
    assertThat(resolver.resolve(Map.of("os.type", "darwin"))).isEqualTo(AgentProfiles.UNKNOWN);
  }

  @Test
  void quarantinesAbsentResourceAttributes() {
    // A resource block is optional in OTLP, so null is a shape the mappers can genuinely hand over.
    assertThat(resolver.resolve(null)).isEqualTo(AgentProfiles.UNKNOWN);
    assertThat(resolver.resolve(Map.of())).isEqualTo(AgentProfiles.UNKNOWN);
  }

  @Test
  void quarantinesAttributePresentWithANullValue() {
    // OtlpAttributeUtils#unwrap returns null for an AnyValue whose case it does not handle, so a
    // present-but-null entry is reachable from real wire data rather than hypothetical.
    Map<String, Object> resourceAttributes = new HashMap<>();
    resourceAttributes.put(SERVICE_NAME, null);

    assertThat(resolver.resolve(resourceAttributes)).isEqualTo(AgentProfiles.UNKNOWN);
  }

  @Test
  void readsTheConfiguredAttributeKeyRatherThanAssumingServiceName() {
    TuningProperties overridden = new TuningProperties();
    overridden.setAgentProfileResourceAttribute("telemetry.sdk.name");
    overridden.setAgentProfileResolution(Map.of("my-agent", "my-profile"));
    AgentProfileResolver configuredResolver = new AgentProfileResolver(overridden);

    assertThat(configuredResolver.resolve(Map.of("telemetry.sdk.name", "my-agent")))
        .isEqualTo("my-profile");
    // The default key must no longer be consulted once overridden.
    assertThat(configuredResolver.resolve(Map.of(SERVICE_NAME, "my-agent")))
        .isEqualTo(AgentProfiles.UNKNOWN);
  }

  @Test
  void matchingIsCaseSensitiveSoAnUnexpectedSpellingSurfacesRatherThanBeingAbsorbed() {
    assertThat(resolver.resolve(Map.of(SERVICE_NAME, "Claude-Code")))
        .isEqualTo(AgentProfiles.UNKNOWN);
  }
}
