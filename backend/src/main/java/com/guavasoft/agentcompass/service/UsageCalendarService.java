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

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.UsageCalendarDaily;
import com.guavasoft.agentcompass.model.UsageCalendarDay;
import com.guavasoft.agentcompass.model.UsageCalendarHour;
import com.guavasoft.agentcompass.model.UsageCalendarModelCost;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Backs the Usage Calendar page ({@code GET /api/usage/calendar/daily}): one row per local calendar
 * day carrying cost, tokens, skill and subagent calls, sessions, active time and the four "all
 * metrics" figures the day drawer shows.
 *
 * <p><b>One rollup, not per-metric calls.</b> A month view would otherwise fire a windowed request per
 * metric per day. The existing trend endpoints cannot stand in: each derives its own bucket width from
 * the window length rather than accepting one, so a day-wide bucket is not something a caller can ask
 * them for, and a fixed 86400 s {@code date_bin} could not express a local midnight anyway. Five
 * grouped queries (three over {@code metric_points}, two over {@code log_records}) cover the whole range;
 * asking for hourly buckets (the week view) adds two more, the counters and the skill invocations again
 * split by local hour.
 *
 * <p><b>Day boundaries are the caller's local ones.</b> The frontend sends the IANA zone and the
 * instants of the first and one-past-last local midnight; the queries bucket with
 * {@code timestamp AT TIME ZONE zone}, so a daylight-saving day is 23 or 25 hours rather than being
 * forced to 24.
 *
 * <p><b>Counter pipeline for cost and tokens, on purpose.</b> They read the cumulative counters
 * ({@code SUM(value_delta)}) like the Tokens and Sessions pages, not the {@code api_request} log
 * figures the Cost page uses, because sessions, active time, lines of code and decisions exist only
 * as counters — one source for every column of a day means the columns reconcile with each other. The
 * cost here will therefore read a little off the Cost page's total for the same span; see the
 * two-pipelines note in AGENTS.md. Never blend the two in one figure.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UsageCalendarService {

  // Values of the `type` attribute on the lines-of-code counter, and of the decision attribute on the
  // edit-decision counter (the attribute's own name is TuningProperties#decisionAttribute). Claude Code's
  // emission shape, documented on TuningProperties.
  private static final String LINES_ADDED_VALUE = "added";
  private static final String LINES_REMOVED_VALUE = "removed";
  private static final String DECISION_ACCEPTED_VALUE = "accept";
  private static final String DECISION_REJECTED_VALUE = "reject";

  // Column positions in the two metric_points rollups (after the leading day column).
  private static final int DAY_COLUMN = 0;
  private static final int COST_COLUMN = 1;
  private static final int TOKENS_COLUMN = 2;
  private static final int ACTIVE_SECONDS_COLUMN = 3;
  private static final int COMMITS_COLUMN = 4;
  private static final int PULL_REQUESTS_COLUMN = 5;
  private static final int SESSIONS_COLUMN = 6;
  private static final int LINES_ADDED_COLUMN = 1;
  private static final int LINES_REMOVED_COLUMN = 2;
  private static final int DECISIONS_ACCEPTED_COLUMN = 3;
  private static final int DECISIONS_REJECTED_COLUMN = 4;
  private static final int MODEL_COLUMN = 1;
  private static final int MODEL_COST_COLUMN = 2;
  private static final int LOG_COUNT_COLUMN = 1;

  // Column positions in the two hourly rollups: (day, hour, ...). The hour column follows the day one.
  private static final int HOUR_COLUMN = 1;
  private static final int HOURLY_COST_COLUMN = 2;
  private static final int HOURLY_TOKENS_COLUMN = 3;
  private static final int HOURLY_ACTIVE_SECONDS_COLUMN = 4;
  private static final int HOURLY_LOG_COUNT_COLUMN = 2;
  private static final int HOURS_PER_DAY = 24;

  // What the Tokens and Cost pages call a model row that carries no model attribute.
  private static final String UNKNOWN_MODEL = "unknown";

  private final MetricPointRepository metricPointRepository;
  private final LogRecordRepository logRecordRepository;
  private final TuningProperties tuningProperties;

  /**
   * @param includeHourly whether to also fill each day's 24 hour-of-day buckets. Two more grouped
   *     queries (the counters and the skill invocations again, split by hour), so only the week view
   *     asks for it; without it each day's {@code hourly} is null.
   */
  public UsageCalendarDaily daily(
      Instant from, Instant to, String timeZone, String repositoryUrl, boolean includeHourly) {
    if (!to.isAfter(from)) {
      throw new IllegalArgumentException("to must be after from");
    }
    ZoneId zone = resolveZone(timeZone);
    String zoneId = zone.getId();

    Map<LocalDate, DayTotals> totalsByDay = new TreeMap<>();
    LocalDate firstDay = from.atZone(zone).toLocalDate();
    // `to` is exclusive, so the last day wanted is the one containing the instant just before it.
    LocalDate lastDay = to.minusNanos(1).atZone(zone).toLocalDate();
    for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
      totalsByDay.put(day, new DayTotals());
    }

    collectCounterTotals(totalsByDay, from, to, zoneId, repositoryUrl);
    collectAttributeSplitTotals(totalsByDay, from, to, zoneId, repositoryUrl);
    collectCostByModel(totalsByDay, from, to, zoneId, repositoryUrl);
    collectSkillInvocations(totalsByDay, from, to, zoneId, repositoryUrl);
    collectSubagentDispatches(totalsByDay, from, to, zoneId, repositoryUrl);
    if (includeHourly) {
      collectHourlyCounterTotals(totalsByDay, from, to, zoneId, repositoryUrl);
      collectHourlySkillInvocations(totalsByDay, from, to, zoneId, repositoryUrl);
    }

    List<UsageCalendarDay> days = new ArrayList<>(totalsByDay.size());
    totalsByDay.forEach((day, totals) -> days.add(totals.toDay(day, includeHourly)));
    return new UsageCalendarDaily(days);
  }

  private void collectHourlyCounterTotals(
      Map<LocalDate, DayTotals> totalsByDay, Instant from, Instant to, String zoneId, String repositoryUrl) {
    List<Object[]> rows = metricPointRepository.aggregateHourlyCounterTotals(
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getTokenUsageMetric(),
        tuningProperties.getActiveTimeMetric(),
        from,
        to,
        zoneId,
        repositoryUrl);
    for (Object[] row : rows) {
      HourTotals hourTotals = hourTotalsFor(totalsByDay, row);
      if (hourTotals == null) {
        continue;
      }
      hourTotals.costUsd = asDouble(row[HOURLY_COST_COLUMN]);
      hourTotals.tokens = Math.round(asDouble(row[HOURLY_TOKENS_COLUMN]));
      hourTotals.activeSeconds = Math.round(asDouble(row[HOURLY_ACTIVE_SECONDS_COLUMN]));
    }
  }

  private void collectHourlySkillInvocations(
      Map<LocalDate, DayTotals> totalsByDay, Instant from, Instant to, String zoneId, String repositoryUrl) {
    List<Object[]> rows = logRecordRepository.aggregateHourlySkillInvocations(
        tuningProperties.getSkillEventName(),
        tuningProperties.getSkillNameAttribute(),
        tuningProperties.getPromptIdAttribute(),
        tuningProperties.getAgentNameAttribute(),
        from,
        to,
        zoneId,
        repositoryUrl);
    for (Object[] row : rows) {
      HourTotals hourTotals = hourTotalsFor(totalsByDay, row);
      if (hourTotals != null) {
        hourTotals.skillCalls = ((Number) row[HOURLY_LOG_COUNT_COLUMN]).longValue();
      }
    }
  }

  // The (day, hour) bucket a grouped row belongs to, or null when either falls outside what was asked
  // for -- the same skip-what-is-not-there rule the daily collectors apply to a day.
  private static HourTotals hourTotalsFor(Map<LocalDate, DayTotals> totalsByDay, Object[] row) {
    DayTotals totals = totalsByDay.get(LocalDate.parse((String) row[DAY_COLUMN]));
    int hour = ((Number) row[HOUR_COLUMN]).intValue();
    if (totals == null || hour < 0 || hour >= HOURS_PER_DAY) {
      return null;
    }
    return totals.hours[hour];
  }

  private void collectCounterTotals(
      Map<LocalDate, DayTotals> totalsByDay, Instant from, Instant to, String zoneId, String repositoryUrl) {
    List<Object[]> rows = metricPointRepository.aggregateDailyCounterTotals(
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getTokenUsageMetric(),
        tuningProperties.getActiveTimeMetric(),
        tuningProperties.getCommitCountMetric(),
        tuningProperties.getPullRequestCountMetric(),
        from,
        to,
        zoneId,
        repositoryUrl);
    for (Object[] row : rows) {
      DayTotals totals = totalsByDay.get(LocalDate.parse((String) row[DAY_COLUMN]));
      if (totals == null) {
        continue;
      }
      totals.costUsd = asDouble(row[COST_COLUMN]);
      totals.tokens = Math.round(asDouble(row[TOKENS_COLUMN]));
      totals.activeSeconds = Math.round(asDouble(row[ACTIVE_SECONDS_COLUMN]));
      totals.commits = Math.round(asDouble(row[COMMITS_COLUMN]));
      totals.pullRequests = Math.round(asDouble(row[PULL_REQUESTS_COLUMN]));
      totals.sessions = ((Number) row[SESSIONS_COLUMN]).longValue();
    }
  }

  private void collectAttributeSplitTotals(
      Map<LocalDate, DayTotals> totalsByDay, Instant from, Instant to, String zoneId, String repositoryUrl) {
    List<Object[]> rows = metricPointRepository.aggregateDailyAttributeSplitTotals(
        tuningProperties.getLinesOfCodeMetric(),
        tuningProperties.getCodeEditDecisionMetric(),
        tuningProperties.getTokenTypeAttribute(),
        tuningProperties.getDecisionAttribute(),
        LINES_ADDED_VALUE,
        LINES_REMOVED_VALUE,
        DECISION_ACCEPTED_VALUE,
        DECISION_REJECTED_VALUE,
        from,
        to,
        zoneId,
        repositoryUrl);
    for (Object[] row : rows) {
      DayTotals totals = totalsByDay.get(LocalDate.parse((String) row[DAY_COLUMN]));
      if (totals == null) {
        continue;
      }
      totals.linesAdded = Math.round(asDouble(row[LINES_ADDED_COLUMN]));
      totals.linesRemoved = Math.round(asDouble(row[LINES_REMOVED_COLUMN]));
      totals.decisionsAccepted = Math.round(asDouble(row[DECISIONS_ACCEPTED_COLUMN]));
      totals.decisionsRejected = Math.round(asDouble(row[DECISIONS_REJECTED_COLUMN]));
    }
  }

  // Rows arrive day-ascending and spend-descending within a day, so appending keeps each day's list
  // largest-first without a sort here.
  private void collectCostByModel(
      Map<LocalDate, DayTotals> totalsByDay, Instant from, Instant to, String zoneId, String repositoryUrl) {
    List<Object[]> rows = metricPointRepository.aggregateDailyCostByModel(
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getModelAttribute(),
        from,
        to,
        zoneId,
        repositoryUrl);
    for (Object[] row : rows) {
      DayTotals totals = totalsByDay.get(LocalDate.parse((String) row[DAY_COLUMN]));
      if (totals == null) {
        continue;
      }
      String model = row[MODEL_COLUMN] == null ? UNKNOWN_MODEL : (String) row[MODEL_COLUMN];
      totals.costByModel.add(new UsageCalendarModelCost(model, asDouble(row[MODEL_COST_COLUMN])));
    }
  }

  private void collectSkillInvocations(
      Map<LocalDate, DayTotals> totalsByDay, Instant from, Instant to, String zoneId, String repositoryUrl) {
    List<Object[]> rows = logRecordRepository.aggregateDailySkillInvocations(
        tuningProperties.getSkillEventName(),
        tuningProperties.getSkillNameAttribute(),
        tuningProperties.getPromptIdAttribute(),
        tuningProperties.getAgentNameAttribute(),
        from,
        to,
        zoneId,
        repositoryUrl);
    for (Object[] row : rows) {
      DayTotals totals = totalsByDay.get(LocalDate.parse((String) row[DAY_COLUMN]));
      if (totals != null) {
        totals.skillCalls = ((Number) row[LOG_COUNT_COLUMN]).longValue();
      }
    }
  }

  private void collectSubagentDispatches(
      Map<LocalDate, DayTotals> totalsByDay, Instant from, Instant to, String zoneId, String repositoryUrl) {
    List<Object[]> rows = logRecordRepository.aggregateDailySubagentDispatches(
        tuningProperties.getToolEventName(),
        tuningProperties.getSubagentToolName(),
        from,
        to,
        zoneId,
        repositoryUrl);
    for (Object[] row : rows) {
      DayTotals totals = totalsByDay.get(LocalDate.parse((String) row[DAY_COLUMN]));
      if (totals != null) {
        totals.subagentCalls = ((Number) row[LOG_COUNT_COLUMN]).longValue();
      }
    }
  }

  // A zone Java cannot resolve is a client error, not a server one. The resolved id (not the raw
  // string) is what reaches SQL, so an unrecognised value never gets as far as Postgres.
  private static ZoneId resolveZone(String timeZone) {
    try {
      return ZoneId.of(timeZone);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("Unknown time zone: " + timeZone);
    }
  }

  private static double asDouble(Object value) {
    return value == null ? 0.0 : ((Number) value).doubleValue();
  }

  /** Mutable accumulator for one day while the four queries fill it in. Zero until a query says otherwise. */
  private static final class DayTotals {
    private double costUsd;
    private long tokens;
    private long skillCalls;
    private long subagentCalls;
    private long sessions;
    private long activeSeconds;
    private long linesAdded;
    private long linesRemoved;
    private long commits;
    private long pullRequests;
    private long decisionsAccepted;
    private long decisionsRejected;
    private final List<UsageCalendarModelCost> costByModel = new ArrayList<>();
    private final HourTotals[] hours = new HourTotals[HOURS_PER_DAY];

    DayTotals() {
      for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
        hours[hour] = new HourTotals();
      }
    }

    UsageCalendarDay toDay(LocalDate day, boolean includeHourly) {
      return new UsageCalendarDay(
          day, costUsd, tokens, skillCalls, subagentCalls, sessions, activeSeconds,
          linesAdded, linesRemoved, commits, pullRequests, decisionsAccepted, decisionsRejected,
          List.copyOf(costByModel), includeHourly ? hourlyBuckets() : null);
    }

    private List<UsageCalendarHour> hourlyBuckets() {
      List<UsageCalendarHour> buckets = new ArrayList<>(HOURS_PER_DAY);
      for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
        HourTotals hourTotals = hours[hour];
        buckets.add(new UsageCalendarHour(
            hour, hourTotals.costUsd, hourTotals.tokens, hourTotals.skillCalls, hourTotals.activeSeconds));
      }
      return List.copyOf(buckets);
    }
  }

  /** Mutable accumulator for one local hour of a day. Zero until a query says otherwise. */
  private static final class HourTotals {
    private double costUsd;
    private long tokens;
    private long skillCalls;
    private long activeSeconds;
  }
}
