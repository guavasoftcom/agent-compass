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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.model.IngestHealth;
import com.guavasoft.agentcompass.model.PurgePreview;
import com.guavasoft.agentcompass.model.PurgeTableEstimate;
import com.guavasoft.agentcompass.model.SignalIngest;
import com.guavasoft.agentcompass.model.StorageOverview;
import com.guavasoft.agentcompass.model.SystemBuild;
import com.guavasoft.agentcompass.model.TableStorage;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.service.SystemService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises every {@code /api/system} query against a real Postgres instance.
 *
 * <p>Two things only this test can establish. First, {@code SystemRepository} extends the bare
 * {@code Repository} marker rather than {@code JpaRepository} — nothing at compile time proves
 * Spring Data will build a proxy for it, so booting the context and calling every method is the
 * proof. Second, the queries read {@code pg_catalog}, {@code flyway_schema_history}, and generated
 * columns that a mocked-service slice test never touches; a column that was renamed by a migration
 * would only surface here.
 *
 * <p>Assertions are deliberately about shapes and relations, never absolute sizes. A fresh container
 * has never been autovacuumed, so its planner statistics are meaningless (this is the same reason
 * the production queries use exact {@code COUNT(*)} rather than {@code reltuples}) and its tables
 * are near-empty.
 */
@SpringBootTest
@Testcontainers
class SystemQueryIntegrationTest {

    private static final String ATTR_EVENT_NAME = "event.name";
    private static final String ATTR_TOOL_NAME = "tool_name";
    private static final String EVENT_TOOL_RESULT = "tool_result";
    private static final String TOOL_READ = "Read";

    private static final int SEEDED_RECENT_LOGS = 5;
    private static final int SEEDED_ANCIENT_LOGS = 3;
    private static final int ANCIENT_AGE_DAYS = 400;
    private static final int PURGE_RETENTION_DAYS = 30;
    private static final int EXPECTED_SIGNAL_COUNT = 3;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    LogRecordRepository logRecordRepository;

    @Autowired
    SystemService systemService;

    @Test
    void storageReportsEveryTableWithSizesThatDecomposeExactly() {
        StorageOverview overview = systemService.storageOverview();

        assertThat(overview.tables()).extracting(TableStorage::tableName)
                .contains("log_records", "metric_points", "spans", "flyway_schema_history");
        assertThat(overview.databaseTotalBytes()).isPositive();
        assertThat(overview.measuredAt()).isBefore(Instant.now().plusSeconds(1));

        assertThat(overview.tables()).allSatisfy(table -> {
            assertThat(table.heapBytes() + table.indexBytes() + table.toastBytes())
                    .as("heap + index + toast must equal pg_total_relation_size for %s", table.tableName())
                    .isEqualTo(table.totalBytes());
            assertThat(table.rowCount()).isNotNegative();
            assertThat(table.rowsLastSevenDays()).isLessThanOrEqualTo(table.rowCount());
            assertThat(table.estimatedBytesPerDay()).isNotNegative();
        });
    }

    @Test
    void storageCountsSeededRowsExactlyAndOrdersTheTimeSpan() {
        seedLogRecords();

        TableStorage logRecords = tableNamed(systemService.storageOverview(), "log_records");

        assertThat(logRecords.rowCount()).isEqualTo(SEEDED_RECENT_LOGS + SEEDED_ANCIENT_LOGS);
        assertThat(logRecords.rowsLastSevenDays()).isEqualTo(SEEDED_RECENT_LOGS);
        assertThat(logRecords.oldestTimestamp()).isBeforeOrEqualTo(logRecords.newestTimestamp());
        assertThat(logRecords.estimatedBytesPerDay()).isPositive();
    }

    /** An empty table must report a zero growth estimate, not divide by its zero row count. */
    @Test
    void anEmptyTableEstimatesNoGrowthRatherThanFailing() {
        TableStorage spans = tableNamed(systemService.storageOverview(), "spans");

        assertThat(spans.rowCount()).isZero();
        assertThat(spans.estimatedBytesPerDay()).isZero();
        assertThat(spans.oldestTimestamp()).isNull();
        assertThat(spans.newestTimestamp()).isNull();
    }

    @Test
    void ingestHealthReportsAllThreeSignalsWithNestedWindowsInOrder() {
        seedLogRecords();

        IngestHealth health = systemService.ingestHealth();

        assertThat(health.signals()).hasSize(EXPECTED_SIGNAL_COUNT);
        // Pinned deliberately: UNION ALL guarantees no order, and the live database was observed
        // returning these legs traces-logs-metrics while a small container returned them in written
        // order. The query's ORDER BY is what makes this stable, and this assertion is its guard.
        assertThat(health.signals()).extracting(SignalIngest::signal)
                .containsExactly("logs", "metrics", "traces");
        assertThat(health.signals()).extracting(SignalIngest::tableName)
                .containsExactly("log_records", "metric_points", "spans");

        assertThat(health.signals()).allSatisfy(signal -> {
            assertThat(signal.rowsLastHour()).isLessThanOrEqualTo(signal.rowsLastDay());
            assertThat(signal.rowsLastDay()).isLessThanOrEqualTo(signal.rowsLastWeek());
            assertThat(signal.nameCardinalityLabel()).isNotBlank();
        });

        SignalIngest logs = signalNamed(health, "logs");
        assertThat(logs.rowsLastWeek()).isEqualTo(SEEDED_RECENT_LOGS);
        assertThat(logs.nameCardinality()).isEqualTo(1);
        assertThat(logs.newestReceivedAt()).isNotNull();
        assertThat(logs.seriesCardinality()).as("only metrics have stream identity").isNull();
        assertThat(signalNamed(health, "metrics").seriesCardinality()).isNotNull();
    }

    @Test
    void buildReportsPostgresVersionAndTheFullMigrationHistory() {
        SystemBuild build = systemService.systemBuild();

        assertThat(build.postgresVersion()).startsWith("16");
        assertThat(build.javaVersion()).isNotBlank();
        assertThat(build.applicationVersion()).isNotBlank();
        assertThat(build.migrations()).isNotEmpty();
        assertThat(build.migrations()).allSatisfy(migration -> {
            assertThat(migration.success()).as("a failed migration would have aborted startup").isTrue();
            assertThat(migration.script()).isNotBlank();
            assertThat(migration.installedOn()).isNotNull();
            assertThat(migration.executionTimeMillis()).isNotNegative();
        });
        assertThat(build.migrations().get(0).installedRank())
                .as("migrations come back newest first")
                .isEqualTo(build.migrations().size());
    }

    @Test
    void purgePreviewCountsOnlyRowsOlderThanTheCutoff() {
        seedLogRecords();

        PurgePreview preview = systemService.purgePreview(PURGE_RETENTION_DAYS);

        assertThat(preview.retentionDays()).isEqualTo(PURGE_RETENTION_DAYS);
        assertThat(preview.cutoff()).isBefore(Instant.now());
        assertThat(preview.tables()).extracting(PurgeTableEstimate::tableName)
                .containsExactlyInAnyOrder("log_records", "metric_points", "spans");

        PurgeTableEstimate logRecords = purgeEstimateFor(preview, "log_records");
        assertThat(logRecords.rowsToDelete()).isEqualTo(SEEDED_ANCIENT_LOGS);
        assertThat(logRecords.totalRows()).isEqualTo(SEEDED_RECENT_LOGS + SEEDED_ANCIENT_LOGS);
        assertThat(logRecords.timestampColumn()).isEqualTo("timestamp");
        assertThat(logRecords.sharePercent()).isBetween(0.0, 100.0);
        assertThat(preview.totalRowsToDelete()).isEqualTo(SEEDED_ANCIENT_LOGS);

        assertThat(purgeEstimateFor(preview, "spans").timestampColumn())
                .as("spans has no timestamp column")
                .isEqualTo("start_timestamp");

        assertThat(preview.tables()).allSatisfy(table ->
                assertThat(table.rowsToDelete()).isLessThanOrEqualTo(table.totalRows()));
    }

    /**
     * The contract the endpoint exists to keep: it renders a DELETE without running one. Asserting
     * the row count is unchanged afterwards is what stops a future refactor from "helpfully"
     * executing the script it just built.
     */
    @Test
    void purgePreviewRendersDeleteStatementsWithoutDeletingAnything() {
        seedLogRecords();
        long rowCountBefore = logRecordRepository.count();

        PurgePreview preview = systemService.purgePreview(PURGE_RETENTION_DAYS);

        assertThat(preview.sql())
                .contains("DELETE FROM log_records AS candidate")
                .contains("DELETE FROM metric_points AS candidate")
                .contains("DELETE FROM spans AS candidate")
                .contains("dormant_sessions_for_purge")
                .contains("VACUUM");
        assertThat(logRecordRepository.count())
                .as("a preview must not delete rows")
                .isEqualTo(rowCountBefore);
    }

    @Test
    void effectiveConfigurationRendersEveryTuningPropertyFromTheRunningContext() {
        assertThat(systemService.effectiveConfiguration().propertyCount()).isPositive();
        assertThat(systemService.effectiveConfiguration().groups()).isNotEmpty();
    }

    private void seedLogRecords() {
        logRecordRepository.deleteAll();
        Instant now = Instant.now();
        for (int index = 0; index < SEEDED_RECENT_LOGS; index++) {
            logRecordRepository.save(logRecord(now.minus(index + 1L, ChronoUnit.HOURS)));
        }
        for (int index = 0; index < SEEDED_ANCIENT_LOGS; index++) {
            logRecordRepository.save(logRecord(now.minus(ANCIENT_AGE_DAYS + index, ChronoUnit.DAYS)));
        }
    }

    private static LogRecordEntity logRecord(Instant timestamp) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_EVENT_NAME, EVENT_TOOL_RESULT);
        attributes.put(ATTR_TOOL_NAME, TOOL_READ);

        LogRecordEntity logRecord = new LogRecordEntity();
        logRecord.setTimestamp(timestamp);
        logRecord.setReceivedAt(timestamp);
        logRecord.setAttributes(attributes);
        return logRecord;
    }

    private static TableStorage tableNamed(StorageOverview overview, String tableName) {
        return overview.tables().stream()
                .filter(table -> table.tableName().equals(tableName))
                .findFirst()
                .orElseThrow();
    }

    private static SignalIngest signalNamed(IngestHealth health, String signal) {
        return health.signals().stream()
                .filter(entry -> entry.signal().equals(signal))
                .findFirst()
                .orElseThrow();
    }

    private static PurgeTableEstimate purgeEstimateFor(PurgePreview preview, String tableName) {
        List<PurgeTableEstimate> tables = preview.tables();
        return tables.stream()
                .filter(table -> table.tableName().equals(tableName))
                .findFirst()
                .orElseThrow();
    }
}
