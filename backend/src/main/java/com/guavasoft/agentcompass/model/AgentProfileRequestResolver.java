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

import org.springframework.stereotype.Component;

import com.guavasoft.agentcompass.config.TuningProperties;

import lombok.RequiredArgsConstructor;

/**
 * Resolves a request's raw {@code agentProfile} query parameter to the profile a read should
 * actually be scoped to — the read-side half of the hard partition documented on
 * {@link TuningProperties#getPrimaryAgentProfile()}.
 *
 * <p>Deliberately not the same class as the ingest-side
 * {@link com.guavasoft.agentcompass.otlp.mapper.AgentProfileResolver}: that one resolves a
 * {@code service.name} resource attribute to a profile at ingest, defaulting an unmatched resource
 * to {@code unknown}; this one defaults an <em>absent request parameter</em> to the configured
 * primary profile. Same concept, different input, different caller, and opposite "unmatched"
 * behavior — folding them into one class would make one of the two methods read as dead code to
 * whichever caller didn't need it.
 *
 * <p>Lives in {@code model/}, not {@code otlp/mapper/}, because every caller is a web controller
 * resolving a bound {@code @ModelAttribute} field, not an OTLP ingest path. A
 * {@code @ModelAttribute}-bound record's compact constructor cannot reach a configured default —
 * there is no DI during Spring's data binding — so resolution happens here, one layer up, as a
 * normal {@code @Component} a controller injects like any service.
 */
@Component
@RequiredArgsConstructor
public class AgentProfileRequestResolver {

    private final TuningProperties tuningProperties;

    /**
     * Returns {@code requested} when it is non-blank, else the configured
     * {@link TuningProperties#getPrimaryAgentProfile()}. Never returns {@code null} — unlike
     * {@code repositoryUrl}, an absent agent profile must resolve to one specific agent, never to
     * "every agent."
     */
    public String resolve(String requested) {
        return requested != null && !requested.isBlank() ? requested : tuningProperties.getPrimaryAgentProfile();
    }
}
