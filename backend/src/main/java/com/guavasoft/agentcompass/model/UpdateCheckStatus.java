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
import java.time.Instant;

@Schema(name = "UpdateCheckStatus", description = "Whether a newer release than the running one has "
        + "been published on GitHub. When the check is switched off nothing is sent anywhere and "
        + "every field but enabled and currentVersion is empty. When it could not complete "
        + "(offline, rate-limited, a development build) that is reported in message, not as an "
        + "HTTP error.")
public record UpdateCheckStatus(
        @Schema(description = "Whether the check is switched on, from the Settings page override or the "
                + "update-check.enabled default") boolean enabled,

        @Schema(description = "The running version with any -SNAPSHOT suffix removed, or 'dev' for a "
                + "build with no build-info.properties", example = "2.7.1") String currentVersion,

        @Schema(description = "Newest published release, or null when the check is off or failed",
                example = "2.8.0", nullable = true) String latestVersion,

        @Schema(description = "True only when latestVersion is strictly newer than currentVersion")
        boolean updateAvailable,

        @Schema(description = "Release notes page for latestVersion", nullable = true) String releaseUrl,

        @Schema(description = "When latestVersion was published", nullable = true) Instant publishedAt,

        @Schema(description = "When this answer was last fetched from GitHub. Cached for hours, so this "
                + "is usually not 'now'.", nullable = true) Instant checkedAt,

        @Schema(description = "Why there is no answer, in words fit to show the operator; null when "
                + "there is one or the check is off", nullable = true) String message) {
}
