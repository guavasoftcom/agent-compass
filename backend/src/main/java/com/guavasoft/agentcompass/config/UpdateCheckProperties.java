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
package com.guavasoft.agentcompass.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the "is there a newer release?" check reaches GitHub.
 *
 * <p>Like {@link OllamaProperties}, deliberately NOT part of {@link TuningProperties} /
 * {@link TuningPropertyCatalog}: this is a "how do I reach a dependency" setting that mirrors into
 * no SQL and names no OTLP attribute key.
 *
 * <p><b>This is the only request the application makes to a third party</b> — everything else is
 * the local database and the operator's own Ollama — which is why it is switchable at runtime from
 * the Settings page ({@code update_check_settings}, resolved by {@code UpdateCheckService}) and why
 * nothing is sent beyond an unauthenticated {@code GET} for the repository's latest release.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "update-check")
public class UpdateCheckProperties {

    /**
     * Whether the check runs when the Settings page has never been used to say otherwise. On by
     * default; an operator turns it off from the Settings page, or here for a locked-down
     * deployment with no outbound access.
     */
    private boolean enabled = true;

    /** Base URL of the GitHub REST API. Overridable so a test can point at a stub server. */
    private String apiBaseUrl = "https://api.github.com";

    /** {@code owner/name} of the repository whose latest release is compared against. */
    private String repository = "guavasoftcom/agent-compass";

    /**
     * How long a successful lookup is reused. Unauthenticated GitHub calls are limited to 60 an
     * hour per IP, and a release is cut by hand a few times a month, so hours is fine.
     */
    private Duration cacheTtl = Duration.ofHours(6);

    /**
     * How long a failed lookup (offline, rate-limited, repository private) is remembered before the
     * next attempt. Shorter than {@link #cacheTtl} so a transient outage clears itself, but not
     * zero — an unreachable network would otherwise be retried, and wait out its timeout, on every
     * page load.
     */
    private Duration failureRetryDelay = Duration.ofMinutes(15);

    /** Connect timeout — fail fast when there is no route out. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** Read timeout — the response is a few hundred bytes, so anything slower is a stuck server. */
    private Duration readTimeout = Duration.ofSeconds(5);
}
