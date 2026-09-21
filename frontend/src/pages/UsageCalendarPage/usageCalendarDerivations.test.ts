/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
import { describe, expect, it } from 'vitest';
import type { UsageCalendarDay } from './usageCalendarApi';
import {
  HEAT_FLOOR_PERCENT,
  HEAT_SPAN_PERCENT,
  addDays,
  buildHeatPercents,
  buildHourlySeries,
  buildKpiCards,
  buildKpiSeries,
  buildLinesChanged,
  buildModelMix,
  comparablePriorDays,
  endOfLocalDay,
  formatActiveTime,
  formatPeriodLabel,
  fromDateKey,
  indexDaysByDateKey,
  isActiveDay,
  monthGridDates,
  percentChange,
  periodRange,
  previousPeriodRange,
  shiftAnchor,
  startOfWeek,
  toDateKey,
  totalsForPeriod,
} from './usageCalendarDerivations';

const makeDay = (date: string, overrides: Partial<UsageCalendarDay> = {}): UsageCalendarDay => ({
  date,
  costUsd: 0,
  tokens: 0,
  skillCalls: 0,
  subagentCalls: 0,
  sessions: 0,
  activeSeconds: 0,
  linesAdded: 0,
  linesRemoved: 0,
  commits: 0,
  pullRequests: 0,
  decisionsAccepted: 0,
  decisionsRejected: 0,
  costByModel: [],
  ...overrides,
});

// 2026-09-19 is a Saturday, so its Sunday-first week runs Sep 13-19.
const SATURDAY_SEPTEMBER_NINETEENTH = new Date(2026, 8, 19);

describe('period arithmetic', () => {
  it('starts the week on Sunday, including for a Sunday and a Saturday', () => {
    expect(toDateKey(startOfWeek(new Date(2026, 8, 20)))).toBe('2026-09-20');
    expect(toDateKey(startOfWeek(new Date(2026, 8, 19)))).toBe('2026-09-13');
    expect(toDateKey(startOfWeek(new Date(2026, 8, 16)))).toBe('2026-09-13');
  });

  it('covers a whole month, with the exclusive end on the 1st of the next', () => {
    const september = periodRange('month', new Date(2026, 8, 20));
    expect(september.days).toHaveLength(30);
    expect(toDateKey(september.start)).toBe('2026-09-01');
    expect(toDateKey(september.endExclusive)).toBe('2026-10-01');
    expect(periodRange('month', new Date(2026, 1, 10)).days).toHaveLength(28);
    expect(periodRange('month', new Date(2028, 1, 10)).days).toHaveLength(29);
  });

  it('covers Sunday to Saturday for a week', () => {
    const week = periodRange('week', SATURDAY_SEPTEMBER_NINETEENTH);
    expect(week.days.map(toDateKey)).toEqual([
      '2026-09-13',
      '2026-09-14',
      '2026-09-15',
      '2026-09-16',
      '2026-09-17',
      '2026-09-18',
      '2026-09-19',
    ]);
    expect(toDateKey(week.endExclusive)).toBe('2026-09-20');
  });

  it('shifts months by calendar month and weeks by seven days, across a year boundary', () => {
    expect(toDateKey(shiftAnchor('month', new Date(2026, 0, 15), -1))).toBe('2025-12-01');
    expect(toDateKey(shiftAnchor('month', new Date(2026, 11, 31), 1))).toBe('2027-01-01');
    expect(toDateKey(shiftAnchor('week', new Date(2026, 8, 20), -1))).toBe('2026-09-13');
    expect(toDateKey(shiftAnchor('week', new Date(2026, 11, 28), 1))).toBe('2027-01-04');
  });

  it('finds the previous period', () => {
    const previousMonth = previousPeriodRange('month', new Date(2026, 8, 20));
    expect(toDateKey(previousMonth.start)).toBe('2026-08-01');
    expect(previousMonth.days).toHaveLength(31);
    const previousWeek = previousPeriodRange('week', SATURDAY_SEPTEMBER_NINETEENTH);
    expect(toDateKey(previousWeek.start)).toBe('2026-09-06');
  });

  it('does not drift off midnight across a daylight-saving change', () => {
    // US spring-forward 2026-03-08: adding a day by milliseconds would land at 01:00 or 23:00.
    const beforeChange = new Date(2026, 2, 7);
    const afterChange = addDays(beforeChange, 2);
    expect(afterChange.getHours()).toBe(0);
    expect(toDateKey(afterChange)).toBe('2026-03-09');
    const marchDays = periodRange('month', new Date(2026, 2, 1)).days;
    expect(marchDays).toHaveLength(31);
    expect(marchDays.every((date) => date.getHours() === 0)).toBe(true);
  });

  it('round-trips a date key and ends a day at its last millisecond', () => {
    expect(toDateKey(fromDateKey('2026-09-05'))).toBe('2026-09-05');
    const endOfDay = endOfLocalDay(new Date(2026, 8, 5));
    expect(endOfDay.getMilliseconds()).toBe(999);
    expect(toDateKey(endOfDay)).toBe('2026-09-05');
    expect(toDateKey(new Date(endOfDay.getTime() + 1))).toBe('2026-09-06');
  });
});

describe('monthGridDates', () => {
  it('always yields six Sunday-first weeks that contain the whole month', () => {
    const grid = monthGridDates(new Date(2026, 8, 20));
    expect(grid).toHaveLength(42);
    // September 2026 starts on a Tuesday, so the grid opens on Sunday Aug 30.
    expect(toDateKey(grid[0])).toBe('2026-08-30');
    expect(grid[0].getDay()).toBe(0);
    expect(grid.map(toDateKey)).toContain('2026-09-30');
    expect(toDateKey(grid[41])).toBe('2026-10-10');
  });

  it('opens on the 1st itself when the month starts on a Sunday', () => {
    // February 2026 starts on a Sunday.
    expect(toDateKey(monthGridDates(new Date(2026, 1, 10))[0])).toBe('2026-02-01');
  });
});

describe('formatPeriodLabel', () => {
  it('labels a month', () => {
    expect(formatPeriodLabel('month', new Date(2026, 8, 20))).toBe('September 2026');
  });

  it('labels a week inside one month with the month once', () => {
    expect(formatPeriodLabel('week', SATURDAY_SEPTEMBER_NINETEENTH)).toBe('Sep 13 – 19, 2026');
  });

  it('labels a week that straddles two months with both', () => {
    expect(formatPeriodLabel('week', new Date(2026, 8, 2))).toBe('Aug 30 – Sep 5, 2026');
  });
});

describe('isActiveDay', () => {
  it('is false for an all-zero row and for no row', () => {
    expect(isActiveDay(makeDay('2026-09-01'))).toBe(false);
    expect(isActiveDay(undefined)).toBe(false);
    expect(isActiveDay(null)).toBe(false);
  });

  it('is true when any figure moved', () => {
    expect(isActiveDay(makeDay('2026-09-01', { costUsd: 0.01 }))).toBe(true);
    expect(isActiveDay(makeDay('2026-09-01', { subagentCalls: 1 }))).toBe(true);
    expect(isActiveDay(makeDay('2026-09-01', { activeSeconds: 30 }))).toBe(true);
  });
});

describe('buildHeatPercents', () => {
  const periodDays = periodRange('week', SATURDAY_SEPTEMBER_NINETEENTH).days;
  const days = indexDaysByDateKey([
    makeDay('2026-09-13', { costUsd: 10, tokens: 100, skillCalls: 9 }),
    makeDay('2026-09-14', { costUsd: 30, tokens: 100, skillCalls: 1 }),
    makeDay('2026-09-15', { costUsd: 20, tokens: 500, skillCalls: 5 }),
    makeDay('2026-09-16'),
  ]);

  it('spreads active days between the floor and floor + span by the chosen metric', () => {
    const byCost = buildHeatPercents(periodDays, days, 'cost', SATURDAY_SEPTEMBER_NINETEENTH);
    expect(byCost.get('2026-09-13')).toBeCloseTo(HEAT_FLOOR_PERCENT);
    expect(byCost.get('2026-09-14')).toBeCloseTo(HEAT_FLOOR_PERCENT + HEAT_SPAN_PERCENT);
    expect(byCost.get('2026-09-15')).toBeCloseTo(HEAT_FLOOR_PERCENT + HEAT_SPAN_PERCENT / 2);

    const bySkills = buildHeatPercents(periodDays, days, 'skills', SATURDAY_SEPTEMBER_NINETEENTH);
    expect(bySkills.get('2026-09-13')).toBeCloseTo(HEAT_FLOOR_PERCENT + HEAT_SPAN_PERCENT);
    expect(bySkills.get('2026-09-14')).toBeCloseTo(HEAT_FLOOR_PERCENT);
  });

  it('leaves inactive and missing days out, so they render the plain surface', () => {
    const byTokens = buildHeatPercents(periodDays, days, 'tokens', SATURDAY_SEPTEMBER_NINETEENTH);
    expect(byTokens.has('2026-09-16')).toBe(false);
    expect(byTokens.has('2026-09-17')).toBe(false);
  });

  it('ignores future days when normalizing, and never tints them', () => {
    const futureBig = indexDaysByDateKey([
      makeDay('2026-09-13', { costUsd: 10 }),
      makeDay('2026-09-18', { costUsd: 1000 }),
    ]);
    const heat = buildHeatPercents(periodDays, futureBig, 'cost', new Date(2026, 8, 14));
    expect(heat.has('2026-09-18')).toBe(false);
    // Alone among the elapsed days, so it sits at the floor rather than being squashed by the future value.
    expect(heat.get('2026-09-13')).toBeCloseTo(HEAT_FLOOR_PERCENT);
  });

  it('is empty when nothing in the period was active', () => {
    expect(buildHeatPercents(periodDays, new Map(), 'cost', SATURDAY_SEPTEMBER_NINETEENTH).size).toBe(0);
  });
});

describe('totalsForPeriod and comparablePriorDays', () => {
  const currentDays = periodRange('week', SATURDAY_SEPTEMBER_NINETEENTH).days;
  const today = new Date(2026, 8, 15); // Tuesday: three days elapsed of the week.
  const days = indexDaysByDateKey([
    makeDay('2026-09-13', { costUsd: 10, tokens: 100, skillCalls: 1, sessions: 1 }),
    makeDay('2026-09-14'),
    makeDay('2026-09-15', { costUsd: 5, tokens: 50, skillCalls: 2, activeSeconds: 60 }),
    // Data on a future day (clock skew) must not count.
    makeDay('2026-09-17', { costUsd: 999, tokens: 999, skillCalls: 99, sessions: 9 }),
  ]);

  it('sums only elapsed days and counts active days against the elapsed total', () => {
    expect(totalsForPeriod(currentDays, days, today)).toEqual({
      costUsd: 15,
      tokens: 150,
      skillCalls: 3,
      activeDays: 2,
      elapsedDays: 3,
    });
  });

  it('reports zero elapsed days for a period wholly in the future', () => {
    const futureWeek = periodRange('week', new Date(2026, 9, 5)).days;
    expect(totalsForPeriod(futureWeek, days, today).elapsedDays).toBe(0);
  });

  it('trims the prior period to the days the current one has elapsed', () => {
    const priorDays = previousPeriodRange('week', SATURDAY_SEPTEMBER_NINETEENTH).days;
    expect(comparablePriorDays(priorDays, 3).map(toDateKey)).toEqual([
      '2026-09-06',
      '2026-09-07',
      '2026-09-08',
    ]);
    expect(comparablePriorDays(priorDays, 7)).toHaveLength(7);
    // A longer current month than the prior one never reaches past the prior's own length.
    expect(comparablePriorDays(priorDays, 31)).toHaveLength(7);
  });
});

describe('buildKpiCards', () => {
  const dailyFigures = { costUsd: 2, tokens: 100, skillCalls: 1, sessions: 1 };
  const filledDays = (dates: Date[]) =>
    indexDaysByDateKey(dates.map((date) => makeDay(toDateKey(date), dailyFigures)));

  it('compares equal day counts when the prior month is shorter than the current one', () => {
    // March 2026 has 31 days, all elapsed; February 2026 has 28. Identical daily usage must read as no
    // change, not as the +10.7% that dividing 31 days by 28 would produce.
    const march = periodRange('month', new Date(2026, 2, 15));
    const february = previousPeriodRange('month', new Date(2026, 2, 15));
    expect(march.days).toHaveLength(31);
    expect(february.days).toHaveLength(28);
    const cards = buildKpiCards(
      'month',
      march.days,
      february.days,
      filledDays(march.days),
      filledDays(february.days),
      new Date(2026, 3, 2),
    );
    cards.forEach((card) => {
      expect(card.change).toEqual({ label: '0.0%', direction: 'up' });
    });
  });

  it('keeps the headline on the whole elapsed period, not the trimmed comparison', () => {
    const march = periodRange('month', new Date(2026, 2, 15));
    const february = previousPeriodRange('month', new Date(2026, 2, 15));
    const [cost, , , activeDays] = buildKpiCards(
      'month',
      march.days,
      february.days,
      filledDays(march.days),
      filledDays(february.days),
      new Date(2026, 3, 2),
    );
    expect(cost.value).toBe('$62');
    expect(activeDays.value).toBe('31');
    expect(activeDays.valueSuffix).toBe('/ 31');
  });

  it('still trims the prior period when the current one is only part elapsed', () => {
    const march = periodRange('month', new Date(2026, 2, 15));
    const february = previousPeriodRange('month', new Date(2026, 2, 15));
    // Ten days into March against all of February: only the first ten days of each are compared.
    const cards = buildKpiCards(
      'month',
      march.days,
      february.days,
      filledDays(march.days.slice(0, 10)),
      filledDays(february.days),
      new Date(2026, 2, 10),
    );
    expect(cards[0].change).toEqual({ label: '0.0%', direction: 'up' });
  });
});

describe('percentChange', () => {
  it('reports the absolute change with its direction', () => {
    expect(percentChange(112.4, 100)).toEqual({ label: '12.4%', direction: 'up' });
    expect(percentChange(87.6, 100)).toEqual({ label: '12.4%', direction: 'down' });
    expect(percentChange(100, 100)).toEqual({ label: '0.0%', direction: 'up' });
  });

  it('is null when there is no prior to compare against', () => {
    expect(percentChange(50, 0)).toBeNull();
    expect(percentChange(50, -1)).toBeNull();
    expect(percentChange(50, Number.NaN)).toBeNull();
  });
});

describe('buildKpiSeries', () => {
  const periodDays = periodRange('week', SATURDAY_SEPTEMBER_NINETEENTH).days;
  const days = indexDaysByDateKey([
    makeDay('2026-09-13', { costUsd: 4 }),
    makeDay('2026-09-15', { costUsd: 6 }),
  ]);

  it('gives one value per day and flags today and the first future day', () => {
    const series = buildKpiSeries(periodDays, days, new Date(2026, 8, 15), (day) => day.costUsd);
    expect(series.values).toEqual([4, 0, 6, 0, 0, 0, 0]);
    expect(series.todayIndex).toBe(2);
    expect(series.firstFutureIndex).toBe(3);
  });

  it('has no future index once the whole period has elapsed', () => {
    const series = buildKpiSeries(periodDays, days, new Date(2026, 8, 25), (day) => day.costUsd);
    expect(series.todayIndex).toBeUndefined();
    expect(series.firstFutureIndex).toBeUndefined();
  });

  it('has no today index when today is before the period', () => {
    const series = buildKpiSeries(periodDays, days, new Date(2026, 8, 1), (day) => day.costUsd);
    expect(series.todayIndex).toBeUndefined();
    expect(series.firstFutureIndex).toBe(0);
    expect(series.values.every((value) => value === 0)).toBe(true);
  });
});

describe('buildModelMix', () => {
  it('splits a day by model in the order given, sharing 100 between them', () => {
    const mix = buildModelMix(
      makeDay('2026-09-14', {
        costUsd: 40,
        costByModel: [
          { model: 'claude-opus-4', costUsd: 30 },
          { model: 'claude-sonnet-4', costUsd: 10 },
        ],
      }),
    );
    expect(mix.map((segment) => segment.model)).toEqual(['claude-opus-4', 'claude-sonnet-4']);
    expect(mix.map((segment) => segment.percent)).toEqual([75, 25]);
    expect(mix.map((segment) => segment.colorIndex)).toEqual([0, 1]);
  });

  it('is empty when the day has no model spend, so the cell draws no bar', () => {
    expect(buildModelMix(makeDay('2026-09-14'))).toEqual([]);
    expect(
      buildModelMix(makeDay('2026-09-14', { costByModel: [{ model: 'claude-opus-4', costUsd: 0 }] })),
    ).toEqual([]);
  });

  it('skips a zero-spend model without leaving a gap in the color ranking', () => {
    const mix = buildModelMix(
      makeDay('2026-09-14', {
        costByModel: [
          { model: 'claude-opus-4', costUsd: 5 },
          { model: 'claude-haiku-3-5', costUsd: 0 },
          { model: 'claude-sonnet-4', costUsd: 5 },
        ],
      }),
    );
    expect(mix.map((segment) => segment.colorIndex)).toEqual([0, 1]);
  });
});

describe('buildHourlySeries', () => {
  it('is null when the rollup carried no hourly buckets, so the cell draws no chart', () => {
    expect(buildHourlySeries(makeDay('2026-09-14'))).toBeNull();
    expect(buildHourlySeries(makeDay('2026-09-14', { hourly: null }))).toBeNull();
    expect(buildHourlySeries(makeDay('2026-09-14', { hourly: [] }))).toBeNull();
  });

  it('turns the buckets into four 24-long series, one value per hour', () => {
    const series = buildHourlySeries(
      makeDay('2026-09-14', {
        hourly: [
          { hour: 9, costUsd: 1.5, tokens: 1_000, skillCalls: 1, activeSeconds: 600 },
          { hour: 14, costUsd: 4, tokens: 9_000, skillCalls: 3, activeSeconds: 1_800 },
        ],
      }),
    );
    expect(series?.cost).toHaveLength(24);
    expect(series?.cost[9]).toBe(1.5);
    expect(series?.cost[14]).toBe(4);
    expect(series?.tokens[14]).toBe(9_000);
    expect(series?.skills[14]).toBe(3);
    expect(series?.activity[9]).toBe(600);
    expect(series?.cost[0]).toBe(0);
  });

  it('places each value by its own hour, not by its position, and ignores an hour out of range', () => {
    const series = buildHourlySeries(
      makeDay('2026-09-14', {
        hourly: [
          { hour: 20, costUsd: 2, tokens: 0, skillCalls: 0, activeSeconds: 0 },
          { hour: 3, costUsd: 1, tokens: 0, skillCalls: 0, activeSeconds: 0 },
          { hour: 24, costUsd: 99, tokens: 0, skillCalls: 0, activeSeconds: 0 },
          { hour: -1, costUsd: 99, tokens: 0, skillCalls: 0, activeSeconds: 0 },
        ],
      }),
    );
    expect(series?.cost[3]).toBe(1);
    expect(series?.cost[20]).toBe(2);
    expect(series?.cost.reduce((sum, value) => sum + value, 0)).toBe(3);
  });
});

describe('buildLinesChanged', () => {
  it('splits added against removed, the two shares summing to 100', () => {
    const linesChanged = buildLinesChanged(makeDay('2026-09-14', { linesAdded: 300, linesRemoved: 100 }));
    expect(linesChanged).toEqual({ added: 300, removed: 100, addedPercent: 75, removedPercent: 25 });
  });

  it('is all-added or all-removed when only one side changed', () => {
    expect(buildLinesChanged(makeDay('2026-09-14', { linesAdded: 12 }))?.addedPercent).toBe(100);
    expect(buildLinesChanged(makeDay('2026-09-14', { linesRemoved: 12 }))?.removedPercent).toBe(100);
  });

  it('is null when no line changed, so the cell draws no bar rather than an invented 50/50 one', () => {
    expect(buildLinesChanged(makeDay('2026-09-14'))).toBeNull();
  });
});

describe('formatActiveTime', () => {
  it('formats hours and minutes, or minutes alone', () => {
    expect(formatActiveTime(7860)).toBe('2h 11m');
    expect(formatActiveTime(2700)).toBe('45m');
    expect(formatActiveTime(0)).toBe('0m');
    expect(formatActiveTime(3600)).toBe('1h 0m');
  });
});
