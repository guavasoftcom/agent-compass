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
package com.guavasoft.agentcompass.update;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code major.minor.patch} release number, comparable numerically.
 *
 * <p>Numeric rather than textual because {@code "2.10.0"} sorts before {@code "2.9.0"} as a string.
 * Whatever follows the patch number ({@code -SNAPSHOT}, {@code -rc1}, {@code +build}) is ignored: a
 * packaged jar reports {@code X.Y.Z-SNAPSHOT} for release {@code vX.Y.Z} (the release workflow bumps
 * the pom before packaging), so the suffix must not make a running release look older than itself.
 */
public record ReleaseVersion(int major, int minor, int patch) implements Comparable<ReleaseVersion> {

    /** Nine digits at most, so every component fits an {@code int} without a range check. */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^v?(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(?:[-+].*)?$");

    private static final Comparator<ReleaseVersion> NUMERIC_ORDER = Comparator
            .comparingInt(ReleaseVersion::major)
            .thenComparingInt(ReleaseVersion::minor)
            .thenComparingInt(ReleaseVersion::patch);

    /** Empty for anything that is not a release number, including the {@code dev} placeholder. */
    public static Optional<ReleaseVersion> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ReleaseVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))));
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        return NUMERIC_ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
