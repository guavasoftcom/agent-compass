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
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.model.PurgePreview;
import com.guavasoft.agentcompass.model.PurgeResult;
import com.guavasoft.agentcompass.model.PurgeTableEstimate;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;
import com.guavasoft.agentcompass.service.SystemService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@code DELETE /api/system/telemetry} against a real Postgres.
 *
 * <p>Two guarantees are under test here, both about avoiding silent data damage:
 *
 * <ul>
 *   <li><b>No partial sessions.</b> {@link #purgeNeverTouchesASessionThatIsStillActiveInAnySignal()}
 *       and {@link #purgeGatesOnActivityAcrossAllThreeSignalsNotJustOneTable()} are the tests for
 *       this — a session's rows are deleted whole or not at all, across log_records, metric_points,
 *       and spans together, because {@code session.id} is common to all three and a purge that only
 *       looked at row age would split a session straddling the cutoff (0 sessions do at the default
 *       30-day window on the live database; 21 do at 7 days, accounting for 604k rows).
 *   <li><b>No counter spikes.</b>
 *       {@link #purgeKeepsALiveStreamsPredecessorSoTheNextEmissionIsNotASpike()} is the original
 *       protection: {@code value_delta} is computed at ingest against a row's predecessor in the same
 *       stream and falls back to zero when there is none, so deleting a stream's last surviving row
 *       makes its next emission record the whole cumulative counter as one increment. This survives
 *       even inside a session judged dormant and otherwise eligible for deletion — see
 *       {@link #purgeStillKeepsAStreamMarkerInsideAFullyDormantSession()} — because session dormancy
 *       is strong evidence a process has exited but not a proof of it. No concrete mechanism for a
 *       purged session id to re-emit has been confirmed (it is specifically not known to be
 *       {@code claude --resume}, which this project separately measured minting a disjoint,
 *       never-before-seen session id for what it emits), but none has been ruled out either, so the
 *       marker closes the question rather than assumes it.
 * </ul>
 */
@SpringBootTest
@Testcontainers
class SystemPurgeIntegrationTest {

    private static final String TOKEN_METRIC = "claude_code.token.usage";
    private static final String SESSION_ID_ATTRIBUTE = "session.id";
    private static final String EVENT_NAME_ATTRIBUTE = "event.name";
    private static final String TOOL_RESULT_EVENT = "tool_result";

    private static final int RETENTION_DAYS = 30;
    private static final int ANCIENT_AGE_DAYS = 60;
    private static final int OLD_AGE_DAYS = 45;
    private static final int RECENT_AGE_DAYS = 2;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    LogRecordRepository logRecordRepository;

    @Autowired
    MetricPointRepository metricPointRepository;

    @Autowired
    SpanRepository spanRepository;

    @Autowired
    SystemService systemService;

    @BeforeEach
    void clearTelemetry() {
        logRecordRepository.deleteAll();
        metricPointRepository.deleteAll();
        spanRepository.deleteAll();
    }

    @Test
    void purgeRemovesSessionlessRowsOlderThanTheCutoffAndKeepsNewerOnes() {
        // No session.id on any of these — exercises the plain row-age fallback, the only path
        // available before session-level gating existed.
        logRecordRepository.save(logRecord(daysAgo(ANCIENT_AGE_DAYS)));
        logRecordRepository.save(logRecord(daysAgo(OLD_AGE_DAYS)));
        logRecordRepository.save(logRecord(daysAgo(RECENT_AGE_DAYS)));
        spanRepository.save(span(daysAgo(ANCIENT_AGE_DAYS)));
        spanRepository.save(span(daysAgo(RECENT_AGE_DAYS)));

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        assertThat(result.retentionDays()).isEqualTo(RETENTION_DAYS);
        assertThat(result.totalRowsDeleted()).isEqualTo(3);
        assertThat(logRecordRepository.count()).isEqualTo(1);
        assertThat(spanRepository.count()).isEqualTo(1);
        assertThat(result.tables()).extracting("tableName")
                .contains("log_records", "metric_points", "spans");
        assertThat(result.completedAt()).isNotNull();
        assertThat(result.durationMillis()).isNotNegative();
    }

    /**
     * The core guarantee this whole feature exists for. A session with one old log record and one
     * recent one (or recent activity in a completely different signal) is judged active — because
     * {@code session.id} is common to logs, metrics, and traces — and none of its rows are removed,
     * including the ancient one that a plain {@code timestamp < cutoff} delete would have taken.
     */
    @Test
    void purgeNeverTouchesASessionThatIsStillActiveInAnySignal() {
        String sessionId = "straddling-session";
        logRecordRepository.save(logRecord(daysAgo(ANCIENT_AGE_DAYS), sessionId));
        logRecordRepository.save(logRecord(daysAgo(RECENT_AGE_DAYS), sessionId));

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        assertThat(result.totalRowsDeleted()).isZero();
        assertThat(logRecordRepository.count())
                .as("the 60-day-old row must survive because its session is still active")
                .isEqualTo(2);
    }

    /**
     * The same guarantee, but proving it crosses signals: a session's metric points are all old
     * enough to look dormant if metric_points were the only table checked, but a recent log record
     * under the same session.id keeps them all — the whole point of a cross-signal identifier.
     */
    @Test
    void purgeGatesOnActivityAcrossAllThreeSignalsNotJustOneTable() {
        String sessionId = "cross-signal-session";
        ingestMetricPoint(sessionId, 100L, daysAgo(ANCIENT_AGE_DAYS));
        ingestMetricPoint(sessionId, 250L, daysAgo(OLD_AGE_DAYS));
        logRecordRepository.save(logRecord(daysAgo(RECENT_AGE_DAYS), sessionId));

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        assertThat(result.totalRowsDeleted()).isZero();
        assertThat(metricPointRepository.count())
                .as("metric_points alone look dormant, but the session's log activity keeps them all")
                .isEqualTo(2);
        assertThat(logRecordRepository.count()).isEqualTo(1);
    }

    /**
     * A session with no activity anywhere, in any signal, for the whole retention window is deleted
     * in full across every table — logs, spans, and (subject to the stream marker below) metrics.
     */
    @Test
    void purgeDeletesADormantSessionWhollyAcrossAllThreeTables() {
        String sessionId = "fully-dormant-session";
        logRecordRepository.save(logRecord(daysAgo(ANCIENT_AGE_DAYS), sessionId));
        spanRepository.save(span(daysAgo(ANCIENT_AGE_DAYS), sessionId));

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        assertThat(result.totalRowsDeleted()).isEqualTo(2);
        assertThat(logRecordRepository.count()).isZero();
        assertThat(spanRepository.count()).isZero();
    }

    /**
     * The original protection, still intact. A stream whose rows are all older than the cutoff keeps
     * its newest one. When that stream emits again — which Claude Code does for the life of the
     * process, long after the session that created it went quiet — the new row's delta is computed
     * against that surviving marker rather than falling back to zero.
     */
    @Test
    void purgeKeepsALiveStreamsPredecessorSoTheNextEmissionIsNotASpike() {
        String sessionId = "live-stream";
        ingestMetricPoint(sessionId, 100L, daysAgo(ANCIENT_AGE_DAYS));
        ingestMetricPoint(sessionId, 250L, daysAgo(OLD_AGE_DAYS));

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        // Only the older of the two went; the newest row survived as the stream's marker.
        assertThat(result.preservedRows()).isEqualTo(1);
        assertThat(metricPointRepository.count()).isEqualTo(1);
        assertThat(metricPointRepository.findAll().get(0).getValueLong()).isEqualTo(250L);

        // The stream emits again after the purge, as a long-lived Claude Code process would.
        ingestMetricPoint(sessionId, 400L, Instant.now());

        List<MetricPointEntity> remaining = metricPointRepository.findAll().stream()
                .sorted((left, right) -> left.getTimestamp().compareTo(right.getTimestamp()))
                .toList();
        assertThat(remaining).hasSize(2);
        assertThat(remaining.get(1).getValueDelta())
                .as("delta must be 400-250, not the whole cumulative 400 — a lost predecessor "
                        + "would book the entire counter as one increment")
                .isEqualTo(150.0);
    }

    /**
     * The belt-and-suspenders case: a session judged fully dormant (eligible for deletion) still
     * keeps the newest row of each of its metric streams, exactly as if it were merely a dormant
     * stream rather than a dormant session. Session dormancy is strong evidence the underlying
     * process exited, not a proof of it — no confirmed mechanism reactivates a purged session id,
     * but none has been ruled out either, so the marker is kept regardless rather than trusting the
     * assumption.
     */
    @Test
    void purgeStillKeepsAStreamMarkerInsideAFullyDormantSession() {
        String sessionId = "dormant-but-marked";
        ingestMetricPoint(sessionId, 100L, daysAgo(ANCIENT_AGE_DAYS));
        ingestMetricPoint(sessionId, 250L, daysAgo(OLD_AGE_DAYS));
        logRecordRepository.save(logRecord(daysAgo(ANCIENT_AGE_DAYS), sessionId));

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        assertThat(metricPointRepository.count())
                .as("the session is fully dormant and eligible, but its stream's newest row survives")
                .isEqualTo(1);
        assertThat(metricPointRepository.findAll().get(0).getValueLong()).isEqualTo(250L);
        assertThat(logRecordRepository.count())
                .as("log_records has no cross-row arithmetic, so the dormant session's log is fully gone")
                .isZero();
        assertThat(result.preservedRows()).isGreaterThanOrEqualTo(1);
    }

    /** A still-active session's metric points are kept in full — not just their newest row. */
    @Test
    void purgeKeepsEveryPointOfAStillActiveSessionRegardlessOfAge() {
        String sessionId = "recent-stream";
        ingestMetricPoint(sessionId, 100L, daysAgo(ANCIENT_AGE_DAYS));
        ingestMetricPoint(sessionId, 250L, daysAgo(OLD_AGE_DAYS));
        ingestMetricPoint(sessionId, 400L, daysAgo(RECENT_AGE_DAYS));

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        assertThat(result.totalRowsDeleted()).isZero();
        assertThat(metricPointRepository.count())
                .as("the session is active, so even its 60-day-old point is protected, not just a marker")
                .isEqualTo(3);
    }

    /** Distinct dormant sessions are protected independently — one marker each, not one overall. */
    @Test
    void purgeKeepsOneMarkerPerDormantSession() {
        ingestMetricPoint("dormant-one", 100L, daysAgo(ANCIENT_AGE_DAYS));
        ingestMetricPoint("dormant-one", 250L, daysAgo(OLD_AGE_DAYS));
        ingestMetricPoint("dormant-two", 10L, daysAgo(ANCIENT_AGE_DAYS));
        ingestMetricPoint("dormant-two", 20L, daysAgo(OLD_AGE_DAYS));

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        assertThat(result.preservedRows()).isEqualTo(2);
        assertThat(metricPointRepository.count()).isEqualTo(2);
    }

    /** The preview must promise exactly what the purge delivers. */
    @Test
    void previewPredictsWhatThePurgeActuallyDeletes() {
        ingestMetricPoint("dormant-stream", 100L, daysAgo(ANCIENT_AGE_DAYS));
        ingestMetricPoint("dormant-stream", 250L, daysAgo(OLD_AGE_DAYS));
        logRecordRepository.save(logRecord(daysAgo(ANCIENT_AGE_DAYS)));
        logRecordRepository.save(logRecord(daysAgo(RECENT_AGE_DAYS)));

        PurgePreview preview = systemService.purgePreview(RETENTION_DAYS);
        PurgeTableEstimate metricEstimate = preview.tables().stream()
                .filter(table -> table.tableName().equals("metric_points"))
                .findFirst()
                .orElseThrow();
        assertThat(metricEstimate.preservedRows()).isEqualTo(1);
        assertThat(metricEstimate.rowsToDelete()).isEqualTo(1);

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        assertThat(result.totalRowsDeleted()).isEqualTo(preview.totalRowsToDelete());
    }

    /**
     * The preview also predicts whole-session protection correctly, not just the stream marker.
     */
    @Test
    void previewPredictsAStraddlingSessionIsFullyProtected() {
        String sessionId = "straddling-in-preview";
        logRecordRepository.save(logRecord(daysAgo(ANCIENT_AGE_DAYS), sessionId));
        logRecordRepository.save(logRecord(daysAgo(RECENT_AGE_DAYS), sessionId));

        PurgePreview preview = systemService.purgePreview(RETENTION_DAYS);
        PurgeTableEstimate logEstimate = preview.tables().stream()
                .filter(table -> table.tableName().equals("log_records"))
                .findFirst()
                .orElseThrow();

        assertThat(logEstimate.rowsToDelete()).isZero();
        assertThat(logEstimate.preservedRows()).isEqualTo(1);
    }

    /** The generated script must apply the same session gating and stream-marker protection. */
    @Test
    void thePreviewScriptCarriesSessionGatingAndTheStreamMarkerProtection() {
        ingestMetricPoint("dormant-stream", 100L, daysAgo(ANCIENT_AGE_DAYS));

        String sql = systemService.purgePreview(RETENTION_DAYS).sql();

        assertThat(sql).contains("dormant_sessions_for_purge");
        assertThat(sql).contains("jsonb_exists");
        assertThat(sql).contains("DELETE FROM log_records AS candidate");
        assertThat(sql).contains("DELETE FROM spans AS candidate");
        assertThat(sql).contains("DELETE FROM metric_points AS candidate");
        assertThat(sql).contains("newer.stream_id = candidate.stream_id");
        assertThat(sql).contains("BEGIN;");
        assertThat(sql).contains("COMMIT;");
    }

    @Test
    void purgeRefusesWithoutTheExactConfirmationPhrase() {
        logRecordRepository.save(logRecord(daysAgo(ANCIENT_AGE_DAYS)));

        assertThatThrownBy(() -> systemService.purge(RETENTION_DAYS, "purge"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PURGE");
        assertThatThrownBy(() -> systemService.purge(RETENTION_DAYS, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> systemService.purge(RETENTION_DAYS, ""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(logRecordRepository.count())
                .as("a refused purge must not delete anything")
                .isEqualTo(1);
    }

    /** Deleting nothing is a normal outcome, not an error. */
    @Test
    void purgeOnAnEmptyWindowReportsZeroRatherThanFailing() {
        logRecordRepository.save(logRecord(daysAgo(RECENT_AGE_DAYS)));

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        assertThat(result.totalRowsDeleted()).isZero();
        assertThat(logRecordRepository.count()).isEqualTo(1);
        assertThat(result.reclaimSpaceSql()).contains("VACUUM FULL");
    }

    /**
     * A DELETE marks space reusable rather than returning it, so the reported sizes are expected to
     * be close. Pinned because the UI tells the operator so, and a change here would make that copy
     * wrong.
     */
    @Test
    void purgeReportsDatabaseSizeOnBothSidesOfTheDelete() {
        logRecordRepository.save(logRecord(daysAgo(ANCIENT_AGE_DAYS)));

        PurgeResult result = systemService.purge(RETENTION_DAYS, SystemService.PURGE_CONFIRMATION_PHRASE);

        assertThat(result.databaseTotalBytesBefore()).isPositive();
        assertThat(result.databaseTotalBytesAfter()).isPositive();
    }

    /**
     * Seeds one metric point the way ingest does — insert, then recompute deltas for the new id —
     * so {@code value_delta} is produced by the real query rather than written by the test.
     */
    private void ingestMetricPoint(String sessionId, long cumulativeValue, Instant timestamp) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(SESSION_ID_ATTRIBUTE, sessionId);

        MetricPointEntity metricPoint = new MetricPointEntity();
        metricPoint.setMetricName(TOKEN_METRIC);
        metricPoint.setTimestamp(timestamp);
        metricPoint.setReceivedAt(timestamp);
        metricPoint.setValueLong(cumulativeValue);
        metricPoint.setValueKind("long");
        metricPoint.setAttributes(attributes);

        MetricPointEntity saved = metricPointRepository.saveAndFlush(metricPoint);
        List<Long> ids = List.of(saved.getId());
        metricPointRepository.lockStreamsForIngest(ids);
        metricPointRepository.recomputeValueDeltas(ids);
    }

    /** A log record with no {@code session.id} at all — exercises the sessionless fallback. */
    private static LogRecordEntity logRecord(Instant timestamp) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(EVENT_NAME_ATTRIBUTE, TOOL_RESULT_EVENT);

        LogRecordEntity logRecord = new LogRecordEntity();
        logRecord.setTimestamp(timestamp);
        logRecord.setReceivedAt(timestamp);
        logRecord.setAttributes(attributes);
        return logRecord;
    }

    private static LogRecordEntity logRecord(Instant timestamp, String sessionId) {
        LogRecordEntity logRecord = logRecord(timestamp);
        logRecord.getAttributes().put(SESSION_ID_ATTRIBUTE, sessionId);
        return logRecord;
    }

    /** A span with no {@code session.id} at all — exercises the sessionless fallback. */
    private static SpanEntity span(Instant startTimestamp) {
        // trace_id is varchar(32) and span_id varchar(16) — fixed-width hex, not a readable label.
        SpanEntity span = new SpanEntity();
        span.setTraceId("%032x".formatted(startTimestamp.toEpochMilli()));
        span.setSpanId("%016x".formatted(startTimestamp.toEpochMilli()));
        span.setName("claude_code.tool.execution");
        span.setStartTimestamp(startTimestamp);
        span.setEndTimestamp(startTimestamp.plusSeconds(1));
        span.setReceivedAt(startTimestamp);
        span.setAttributes(new HashMap<>());
        return span;
    }

    private static SpanEntity span(Instant startTimestamp, String sessionId) {
        SpanEntity span = span(startTimestamp);
        span.getAttributes().put(SESSION_ID_ATTRIBUTE, sessionId);
        return span;
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }
}
