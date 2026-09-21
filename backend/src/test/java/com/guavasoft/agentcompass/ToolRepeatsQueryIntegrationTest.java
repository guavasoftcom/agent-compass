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
package com.guavasoft.agentcompass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.model.ToolRepeatStat;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.service.LogService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@code aggregateToolRepeatsInRange}'s repository scoping against a real Postgres
 * instance -- the per-session consecutive-run detection this query does (windowing over
 * {@code ROW_NUMBER()} islands) is the trickiest of {@link LogService}'s tool-activity
 * aggregations to reason about by inspection alone, per backend/CLAUDE.md's Data section on
 * native SQL in this repository.
 */
@SpringBootTest
@Testcontainers
class ToolRepeatsQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.NAME);

    @Autowired
    LogRecordRepository logRecordRepository;

    @Autowired
    LogService logService;

    private static final String ATTR_EVENT_NAME = "event.name";
    private static final String ATTR_TOOL_NAME = "tool_name";
    private static final String ATTR_TOOL_INPUT = "tool_input";
    private static final String ATTR_REPOSITORY_URL = "vcs.repository.url.full";

    private static final String EVENT_TOOL_RESULT = "tool_result";
    private static final String TOOL_EDIT = "Edit";

    private static final String REPOSITORY_A = "https://github.com/guavasoftcom/coding-agent-tuning";
    private static final String REPOSITORY_B = "https://github.com/guavasoftcom/spring-batch-dashboard";

    private Instant windowStart;
    private Instant windowEnd;

    @BeforeEach
    void seed() {
        logRecordRepository.deleteAll();
        windowStart = Instant.now().minus(60, ChronoUnit.MINUTES);
        windowEnd = Instant.now();

        // Three consecutive Edits on the same file, in one session -- an unattributed
        // (repository_url NULL) run of length 3.
        saveEdit(60, "session-unattributed", "/repo/src/foo.ts", null);
        saveEdit(70, "session-unattributed", "/repo/src/foo.ts", null);
        saveEdit(80, "session-unattributed", "/repo/src/foo.ts", null);
    }

    @Test
    void repositoryUrlNullBehavesIdenticallyToBeforeRepositoryAttributionExisted() {
        List<ToolRepeatStat> rows = logService.aggregateToolRepeatsInRange(windowStart, windowEnd, null);

        ToolRepeatStat editRun = singleRowFor(rows, TOOL_EDIT, "/repo/src/foo.ts");
        assertThat(editRun.maxRunLength()).isEqualTo(3L);
        assertThat(editRun.sessions()).isEqualTo(1L);
    }

    @Test
    void aRowCarryingTheVcsAttributeIsScopedToItsOwnRepositoryAndExcludedFromAnotherRepositorysWindow() {
        // A separate four-call run on REPOSITORY_A, and a separate two-call run on REPOSITORY_B,
        // each its own session so the runs don't merge with each other or with seed()'s
        // unattributed run.
        saveEdit(140, "session-repo-a", "/repo/src/bar.ts", REPOSITORY_A);
        saveEdit(150, "session-repo-a", "/repo/src/bar.ts", REPOSITORY_A);
        saveEdit(160, "session-repo-a", "/repo/src/bar.ts", REPOSITORY_A);
        saveEdit(170, "session-repo-a", "/repo/src/bar.ts", REPOSITORY_A);

        saveEdit(240, "session-repo-b", "/repo/src/baz.ts", REPOSITORY_B);
        saveEdit(250, "session-repo-b", "/repo/src/baz.ts", REPOSITORY_B);

        List<ToolRepeatStat> scopedToRepositoryA =
                logService.aggregateToolRepeatsInRange(windowStart, windowEnd, REPOSITORY_A);
        List<ToolRepeatStat> scopedToRepositoryB =
                logService.aggregateToolRepeatsInRange(windowStart, windowEnd, REPOSITORY_B);
        List<ToolRepeatStat> unscoped = logService.aggregateToolRepeatsInRange(windowStart, windowEnd, null);

        // Scoped to A: only A's four-call run is visible -- not B's run, and not the
        // unattributed run seed() writes (repository_url NULL is excluded once :repositoryUrl is
        // bound to a real value).
        ToolRepeatStat runA = singleRowFor(scopedToRepositoryA, TOOL_EDIT, "/repo/src/bar.ts");
        assertThat(runA.maxRunLength()).isEqualTo(4L);
        assertThat(scopedToRepositoryA).noneMatch(row -> "/repo/src/baz.ts".equals(row.scope()));
        assertThat(scopedToRepositoryA).noneMatch(row -> "/repo/src/foo.ts".equals(row.scope()));

        // Scoped to B: only B's two-call run is visible.
        ToolRepeatStat runB = singleRowFor(scopedToRepositoryB, TOOL_EDIT, "/repo/src/baz.ts");
        assertThat(runB.maxRunLength()).isEqualTo(2L);

        // Unscoped: all three runs are visible.
        assertThat(unscoped).extracting(ToolRepeatStat::scope)
                .contains("/repo/src/foo.ts", "/repo/src/bar.ts", "/repo/src/baz.ts");
    }

    private static ToolRepeatStat singleRowFor(List<ToolRepeatStat> rows, String tool, String scope) {
        return rows.stream()
                .filter(row -> tool.equals(row.tool()) && scope.equals(row.scope()))
                .findFirst()
                .orElseThrow();
    }

    private void saveEdit(int offsetSeconds, String sessionId, String filePath, String repositoryUrl) {
        Instant timestamp = windowStart.plusSeconds(offsetSeconds);
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(timestamp);
        entity.setObservedTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setScopeName("claude_code.tools");
        entity.setBody("tool result");

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_EVENT_NAME, EVENT_TOOL_RESULT);
        attributes.put(ATTR_TOOL_NAME, TOOL_EDIT);
        attributes.put("session.id", sessionId);
        attributes.put(ATTR_TOOL_INPUT, "{\"file_path\":\"" + filePath + "\"}");
        if (repositoryUrl != null) {
            attributes.put(ATTR_REPOSITORY_URL, repositoryUrl);
        }
        entity.setAttributes(attributes);
        entity.setResourceAttributes(Map.of("service.name", "claude-code"));
        logRecordRepository.save(entity);
    }
}
