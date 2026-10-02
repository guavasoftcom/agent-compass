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
package com.guavasoft.agentcompass.otlp.mapper;

import com.guavasoft.agentcompass.config.TuningProperties;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Resolves which coding agent emitted an OTLP batch, from that batch's <em>resource</em> attributes.
 *
 * <p>Called once per {@code ResourceLogs} / {@code ResourceMetrics} / {@code ResourceSpans} block by
 * each of the three mappers — not once per record. The emitting agent is a property of the exporting
 * process, so every record under one resource shares it, and resolving per record would repeat a map
 * lookup millions of times for one answer.
 *
 * <p><b>Why the resource block rather than per-record {@code attributes}.</b> Two reasons. A span
 * carries no per-record equivalent of {@code service.name}, so there would be nothing to read for
 * one third of the signals; and the emitting agent genuinely is process-level identity, not a fact
 * about an individual datapoint.
 *
 * <p><b>Why an unmatched resource is {@code unknown} rather than the primary profile.</b> Defaulting
 * unrecognized telemetry into Claude Code's profile would fold a second agent's figures into Claude
 * Code's rollups — the exact blending the hard partition exists to prevent, and the resulting numbers
 * would be wrong rather than merely mixed, since the two agents measure cost and tokens incomparably.
 * Stamping it {@code unknown} keeps the rows stored and queryable while leaving every existing
 * aggregation untouched. See {@link TuningProperties#getUnknownAgentProfile()}.
 *
 * <p>Matching is exact and case-sensitive. {@code service.name} is a lowercase-by-convention
 * identifier chosen by the exporter, so folding case would invent equivalences the operator did not
 * configure; an unexpected spelling should surface as {@code unknown} telemetry to investigate rather
 * than be quietly absorbed into a profile.
 */
@Component
@RequiredArgsConstructor
public class AgentProfileResolver {

  private final TuningProperties tuningProperties;

  /**
   * Resolves the agent profile for one OTLP resource block.
   *
   * @param resourceAttributes the block's decoded resource attributes; may be null or empty, which
   *                           both resolve to the unknown profile
   * @return the configured profile name for this resource, never null
   */
  public String resolve(Map<String, Object> resourceAttributes) {
    if (resourceAttributes == null) {
      return tuningProperties.getUnknownAgentProfile();
    }
    Object identifyingValue = resourceAttributes.get(tuningProperties.getAgentProfileResourceAttribute());
    if (identifyingValue == null) {
      return tuningProperties.getUnknownAgentProfile();
    }
    String resolvedProfile = tuningProperties.getAgentProfileResolution().get(String.valueOf(identifyingValue));
    return resolvedProfile == null ? tuningProperties.getUnknownAgentProfile() : resolvedProfile;
  }
}
