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
package com.guavasoft.agentcompass.service;

import org.junit.jupiter.api.Test;

import com.guavasoft.agentcompass.service.TraceAnalysisAnswer.Finding;
import com.guavasoft.agentcompass.service.TraceAnalysisAnswer.Findings;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceAnalysisFindingsMergeTest {

    @Test
    void aSingleWindowIsReturnedUnchanged() {
        Findings onlyWindow = new Findings(
                List.of(new Finding("Directed start", "went straight to call 1", null)),
                List.of(new Finding("Wrong instrument", "call 3 ran find", "use Glob")),
                "instruction");

        Findings merged = TraceAnalysisFindingsMerge.merge(List.of(onlyWindow));

        assertThat(merged).isSameAs(onlyWindow);
    }

    @Test
    void allEmptyWindowsMergeToAnEmptyResultRatherThanThrowing() {
        Findings firstWindow = new Findings(List.of(), List.of(), null);
        Findings secondWindow = new Findings(null, null, null);

        Findings merged = TraceAnalysisFindingsMerge.merge(List.of(firstWindow, secondWindow));

        assertThat(merged.wentWell()).isEmpty();
        assertThat(merged.wentWrong()).isEmpty();
        assertThat(merged.requestKind()).isNull();
    }

    @Test
    void disjointCitationsBothSurvive() {
        Finding firstFault = new Finding("Redundant work", "calls 3 and 4 re-read the same file", "read once");
        Finding secondFault = new Finding("Redundant work", "calls 40 and 41 re-read the same file", "read once");
        Findings firstWindow = new Findings(List.of(), List.of(firstFault), "instruction");
        Findings secondWindow = new Findings(List.of(), List.of(secondFault), "instruction");

        Findings merged = TraceAnalysisFindingsMerge.merge(List.of(firstWindow, secondWindow));

        assertThat(merged.wentWrong()).containsExactly(firstFault, secondFault);
    }

    @Test
    void intersectingCitationsWithTheSameLabelDedupToOne() {
        Finding firstFault = new Finding("Redundant work", "calls 3 and 4 re-read the same file", "read once");
        Finding secondFault =
                new Finding("Redundant work", "call 4 was already covered by an earlier read", "read once");
        Findings firstWindow = new Findings(List.of(), List.of(firstFault), "instruction");
        Findings secondWindow = new Findings(List.of(), List.of(secondFault), "instruction");

        Findings merged = TraceAnalysisFindingsMerge.merge(List.of(firstWindow, secondWindow));

        assertThat(merged.wentWrong()).containsExactly(firstFault);
    }

    @Test
    void uncitedFindingsDedupByLabelOnly() {
        Finding firstFault = new Finding("Wrong instrument", "used Bash where Grep would have worked", "use Grep");
        Finding secondFault =
                new Finding("Wrong instrument", "used Bash again where Grep would have worked", "use Grep");
        Findings firstWindow = new Findings(List.of(), List.of(firstFault), "instruction");
        Findings secondWindow = new Findings(List.of(), List.of(secondFault), "instruction");

        Findings merged = TraceAnalysisFindingsMerge.merge(List.of(firstWindow, secondWindow));

        assertThat(merged.wentWrong()).containsExactly(firstFault);
    }

    @Test
    void distinctUncitedFindingsWithDifferentLabelsBothSurvive() {
        Finding firstFault = new Finding("Wrong instrument", "used Bash where Grep would have worked", "use Grep");
        Finding secondFault = new Finding("Poor cache reuse", "cache reads stayed below the usual median", null);
        Findings firstWindow = new Findings(List.of(), List.of(firstFault), "instruction");
        Findings secondWindow = new Findings(List.of(), List.of(secondFault), "instruction");

        Findings merged = TraceAnalysisFindingsMerge.merge(List.of(firstWindow, secondWindow));

        assertThat(merged.wentWrong()).containsExactly(firstFault, secondFault);
    }

    @Test
    void wentWellIsDedupedByLabelAndCappedAtTwo() {
        Finding first = new Finding("Directed start", "went straight to the named file", null);
        Finding second = new Finding("Directed start", "went straight to the named file again", null);
        Finding third = new Finding("Earned dispatch", "the subagent kept a long search out of context", null);
        Finding fourth = new Finding("Healthy recovery", "recovered cleanly from the rejected call", null);
        Findings firstWindow = new Findings(List.of(first, third), List.of(), "instruction");
        Findings secondWindow = new Findings(List.of(second, fourth), List.of(), "instruction");

        Findings merged = TraceAnalysisFindingsMerge.merge(List.of(firstWindow, secondWindow));

        assertThat(merged.wentWell()).containsExactly(first, third);
    }

    @Test
    void mergedFaultsAreOrderedRoundRobinAcrossWindows() {
        Finding firstWindowFirstFault = new Finding("First window A", "detail a1", "fix a1");
        Finding firstWindowSecondFault = new Finding("First window B", "detail a2", "fix a2");
        Finding secondWindowFirstFault = new Finding("Second window A", "detail b1", "fix b1");
        Finding secondWindowSecondFault = new Finding("Second window B", "detail b2", "fix b2");
        Findings firstWindow =
                new Findings(List.of(), List.of(firstWindowFirstFault, firstWindowSecondFault), "instruction");
        Findings secondWindow =
                new Findings(List.of(), List.of(secondWindowFirstFault, secondWindowSecondFault), "instruction");

        Findings merged = TraceAnalysisFindingsMerge.merge(List.of(firstWindow, secondWindow));

        assertThat(merged.wentWrong()).containsExactly(
                firstWindowFirstFault, secondWindowFirstFault, firstWindowSecondFault, secondWindowSecondFault);
    }

    @Test
    void requestKindTiesAreBrokenByTheFirstWindow() {
        Findings firstWindow = new Findings(List.of(), List.of(), "question");
        Findings secondWindow = new Findings(List.of(), List.of(), "instruction");

        Findings merged = TraceAnalysisFindingsMerge.merge(List.of(firstWindow, secondWindow));

        assertThat(merged.requestKind()).isEqualTo("question");
    }

    @Test
    void requestKindIsDecidedByMajorityAcrossWindows() {
        Findings firstWindow = new Findings(List.of(), List.of(), "instruction");
        Findings secondWindow = new Findings(List.of(), List.of(), "question");
        Findings thirdWindow = new Findings(List.of(), List.of(), "instruction");

        Findings merged = TraceAnalysisFindingsMerge.merge(List.of(firstWindow, secondWindow, thirdWindow));

        assertThat(merged.requestKind()).isEqualTo("instruction");
    }
}
