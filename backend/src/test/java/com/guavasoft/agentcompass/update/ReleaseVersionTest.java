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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseVersionTest {

    @ParameterizedTest
    @ValueSource(strings = {"2.7.1", "v2.7.1", "2.7.1-SNAPSHOT", "v2.7.1-rc1", "2.7.1+build.5", " v2.7.1 "})
    void parsesTheVersionAndIgnoresPrefixAndSuffix(String text) {
        assertThat(ReleaseVersion.parse(text)).contains(new ReleaseVersion(2, 7, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "", "2.7", "v2", "latest", "2.7.x", "1.2.3.4", "9999999999.0.0"})
    void rejectsAnythingThatIsNotAReleaseNumber(String text) {
        assertThat(ReleaseVersion.parse(text)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(ReleaseVersion.parse(null)).isEmpty();
    }

    /** The reason this is not a string comparison: "2.10.0" sorts before "2.9.0" as text. */
    @Test
    void comparesComponentsNumericallyNotAlphabetically() {
        ReleaseVersion nine = ReleaseVersion.parse("2.9.0").orElseThrow();
        ReleaseVersion ten = ReleaseVersion.parse("2.10.0").orElseThrow();

        assertThat(ten).isGreaterThan(nine);
        assertThat(ReleaseVersion.parse("3.0.0").orElseThrow()).isGreaterThan(ten);
        assertThat(ReleaseVersion.parse("2.7.2").orElseThrow()).isGreaterThan(ReleaseVersion.parse("2.7.1").orElseThrow());
    }

    /** A packaged jar of release vX.Y.Z reports X.Y.Z-SNAPSHOT; it must not read as older than itself. */
    @Test
    void aSnapshotSuffixDoesNotMakeARunningReleaseOlderThanItself() {
        assertThat(ReleaseVersion.parse("2.7.2-SNAPSHOT").orElseThrow())
                .isEqualByComparingTo(ReleaseVersion.parse("v2.7.2").orElseThrow());
    }

    @Test
    void rendersAsPlainDottedNumbers() {
        assertThat(new ReleaseVersion(2, 10, 0)).hasToString("2.10.0");
    }
}
