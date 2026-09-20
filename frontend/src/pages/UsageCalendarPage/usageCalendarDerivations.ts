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
// Pure, no-React derivations for the Usage Calendar: period arithmetic in the browser's LOCAL
// calendar, the month grid, KPI totals with like-for-like deltas, and the "Color by" heat tint.
// Kept out of the view so the date maths — where every off-by-one lives — is unit-tested.

import type { TokenUsageSummary } from '../../api';
import type { BreakdownRow } from '../../components/BreakdownList/BreakdownList';
import { formatCompact, shortModelName } from '../../lib/format';
import type { UsageCalendarDay } from './usageCalendarApi';

export type CalendarView = 'month' | 'week';
export type ColorByMetric = 'cost' | 'tokens' | 'skills';

export interface ColorByOption {
  value: ColorByMetric;
  label: string;
  /** Index into the shared chart palette; the same index the KPI cards and cell dots use. */
  colorIndex: number;
}

export const COLOR_BY_OPTIONS: ColorByOption[] = [
  { value: 'cost', label: 'Cost', colorIndex: 0 },
  { value: 'tokens', label: 'Tokens', colorIndex: 1 },
  { value: 'skills', label: 'Skills', colorIndex: 2 },
];

/** A period of whole local days: `[start, endExclusive)`, with every day in it. */
export interface PeriodRange {
  start: Date;
  endExclusive: Date;
  days: Date[];
}

const DAYS_PER_WEEK = 7;
const MONTH_GRID_WEEKS = 6;
const MONTH_GRID_CELL_COUNT = DAYS_PER_WEEK * MONTH_GRID_WEEKS;
const MONTH_NAMES = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];
const SHORT_MONTH_LENGTH = 3;
const LAST_MILLISECOND_OF_DAY = { hours: 23, minutes: 59, seconds: 59, milliseconds: 999 };

/** Heat tint mixes this share of the metric color into the surface: floor + normalized * span. */
export const HEAT_FLOOR_PERCENT = 6;
export const HEAT_SPAN_PERCENT = 16;

/** Below this activity a day still counts as "no activity" — a sub-second of active time is noise. */
const ACTIVE_SECONDS_FLOOR = 1;

export const startOfLocalDay = (date: Date): Date =>
  new Date(date.getFullYear(), date.getMonth(), date.getDate());

/** Calendar-day arithmetic via the Date constructor, so a DST day never drifts an hour off midnight. */
export const addDays = (date: Date, dayCount: number): Date =>
  new Date(date.getFullYear(), date.getMonth(), date.getDate() + dayCount);

/** The Sunday on or before `date`. The calendar's week starts on Sunday, as the mockup's does. */
export const startOfWeek = (date: Date): Date => addDays(startOfLocalDay(date), -date.getDay());

const pad = (value: number): string => String(value).padStart(2, '0');

/** Local `YYYY-MM-DD` — the key both the backend's `date` and every lookup here agree on. */
export const toDateKey = (date: Date): string =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;

/** Parses a `YYYY-MM-DD` key back to that local day's midnight. */
export const fromDateKey = (dateKey: string): Date => {
  const [year, month, day] = dateKey.split('-').map(Number);
  return new Date(year, month - 1, day);
};

export const isSameDay = (left: Date, right: Date): boolean => toDateKey(left) === toDateKey(right);

/** Last instant of the local day, matching `WindowSelector`'s inclusive custom-range end. */
export const endOfLocalDay = (date: Date): Date =>
  new Date(
    date.getFullYear(),
    date.getMonth(),
    date.getDate(),
    LAST_MILLISECOND_OF_DAY.hours,
    LAST_MILLISECOND_OF_DAY.minutes,
    LAST_MILLISECOND_OF_DAY.seconds,
    LAST_MILLISECOND_OF_DAY.milliseconds,
  );

const rangeOfDays = (start: Date, dayCount: number): PeriodRange => {
  const days: Date[] = [];
  for (let i = 0; i < dayCount; i += 1) {
    days.push(addDays(start, i));
  }
  return { start, endExclusive: addDays(start, dayCount), days };
};

/** The days the period covers: the anchor's whole month, or the Sunday-to-Saturday week around it. */
export const periodRange = (view: CalendarView, anchor: Date): PeriodRange => {
  if (view === 'month') {
    const start = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
    const dayCount = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 0).getDate();
    return rangeOfDays(start, dayCount);
  }
  return rangeOfDays(startOfWeek(anchor), DAYS_PER_WEEK);
};

/** The anchor that lands one period earlier (`-1`) or later (`1`). Months anchor on the 1st. */
export const shiftAnchor = (view: CalendarView, anchor: Date, direction: -1 | 1): Date =>
  view === 'month'
    ? new Date(anchor.getFullYear(), anchor.getMonth() + direction, 1)
    : addDays(anchor, direction * DAYS_PER_WEEK);

export const previousPeriodRange = (view: CalendarView, anchor: Date): PeriodRange =>
  periodRange(view, shiftAnchor(view, anchor, -1));

/** The 42 dates of a six-week Sunday-first month grid, leading and trailing days included. */
export const monthGridDates = (anchor: Date): Date[] =>
  rangeOfDays(startOfWeek(new Date(anchor.getFullYear(), anchor.getMonth(), 1)), MONTH_GRID_CELL_COUNT)
    .days;

/** Three-letter month, e.g. "Sep". */
export const formatShortMonth = (date: Date): string =>
  MONTH_NAMES[date.getMonth()].slice(0, SHORT_MONTH_LENGTH);

/** Three-letter weekday, e.g. "Tue". */
export const formatShortWeekday = (date: Date): string =>
  date.toLocaleDateString('en-US', { weekday: 'short' });

/** "September 1, 2026". */
export const formatLongDate = (date: Date): string =>
  `${MONTH_NAMES[date.getMonth()]} ${date.getDate()}, ${date.getFullYear()}`;

/** "September 2026" for a month, "Sep 20 – 26, 2026" (or "Aug 30 – Sep 5, 2026") for a week. */
export const formatPeriodLabel = (view: CalendarView, anchor: Date): string => {
  if (view === 'month') {
    return `${MONTH_NAMES[anchor.getMonth()]} ${anchor.getFullYear()}`;
  }
  const { days } = periodRange('week', anchor);
  const first = days[0];
  const last = days[days.length - 1];
  const lastPart =
    first.getMonth() === last.getMonth()
      ? `${last.getDate()}`
      : `${formatShortMonth(last)} ${last.getDate()}`;
  return `${formatShortMonth(first)} ${first.getDate()} – ${lastPart}, ${last.getFullYear()}`;
};

/** Long day title for the drawer, e.g. "Tuesday, September 1, 2026". */
export const formatDayTitle = (date: Date): string =>
  date.toLocaleDateString('en-US', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });

/** Whether anything at all was recorded that day. An all-zero row is a real "no activity" answer. */
export const isActiveDay = (day: UsageCalendarDay | undefined | null): boolean =>
  day != null &&
  (day.costUsd > 0 ||
    day.tokens > 0 ||
    day.skillCalls > 0 ||
    day.subagentCalls > 0 ||
    day.sessions > 0 ||
    day.activeSeconds >= ACTIVE_SECONDS_FLOOR);

export const indexDaysByDateKey = (days: UsageCalendarDay[]): Map<string, UsageCalendarDay> =>
  new Map(days.map((day) => [day.date, day]));

const metricValueOf = (day: UsageCalendarDay, colorBy: ColorByMetric): number => {
  if (colorBy === 'tokens') {
    return day.tokens;
  }
  if (colorBy === 'skills') {
    return day.skillCalls;
  }
  return day.costUsd;
};

/**
 * Heat percent per date key: 6 + normalized * 16, where `normalized` is the day's chosen metric
 * against the min/max over the ACTIVE, NON-FUTURE days of the visible period. A day outside the
 * period, in the future, or with no activity is absent (the cell renders the plain surface).
 * With one active day the range is zero, and that day sits at the floor.
 */
export const buildHeatPercents = (
  periodDays: Date[],
  daysByDateKey: Map<string, UsageCalendarDay>,
  colorBy: ColorByMetric,
  today: Date,
): Map<string, number> => {
  const todayKey = toDateKey(today);
  const measured: { dateKey: string; value: number }[] = [];
  periodDays.forEach((date) => {
    const dateKey = toDateKey(date);
    const day = daysByDateKey.get(dateKey);
    if (dateKey <= todayKey && day != null && isActiveDay(day)) {
      measured.push({ dateKey, value: metricValueOf(day, colorBy) });
    }
  });
  const heatByDateKey = new Map<string, number>();
  if (measured.length === 0) {
    return heatByDateKey;
  }
  const values = measured.map((entry) => entry.value);
  const min = Math.min(...values);
  const range = Math.max(...values) - min || 1;
  measured.forEach(({ dateKey, value }) => {
    heatByDateKey.set(dateKey, HEAT_FLOOR_PERCENT + ((value - min) / range) * HEAT_SPAN_PERCENT);
  });
  return heatByDateKey;
};

export interface PeriodTotals {
  costUsd: number;
  tokens: number;
  skillCalls: number;
  activeDays: number;
  /** Days of the period that are today or earlier — the denominator of "active days". */
  elapsedDays: number;
}

/** Totals over the elapsed days only: a future day has no data yet and must not dilute anything. */
export const totalsForPeriod = (
  periodDays: Date[],
  daysByDateKey: Map<string, UsageCalendarDay>,
  today: Date,
): PeriodTotals => {
  const todayKey = toDateKey(today);
  const totals: PeriodTotals = { costUsd: 0, tokens: 0, skillCalls: 0, activeDays: 0, elapsedDays: 0 };
  periodDays.forEach((date) => {
    const dateKey = toDateKey(date);
    if (dateKey > todayKey) {
      return;
    }
    totals.elapsedDays += 1;
    const day = daysByDateKey.get(dateKey);
    if (day == null) {
      return;
    }
    totals.costUsd += day.costUsd;
    totals.tokens += day.tokens;
    totals.skillCalls += day.skillCalls;
    if (isActiveDay(day)) {
      totals.activeDays += 1;
    }
  });
  return totals;
};

/**
 * The prior period trimmed to as many days as the current one has elapsed, so a half-finished
 * month is compared with the first half of last month rather than all of it (which would show a
 * permanent, meaningless drop mid-month). A fully elapsed period compares against the whole prior.
 * Returns fewer days than asked for when the prior period is shorter (February against a 30-day
 * March), so the caller must trim the current side to the same count before comparing.
 */
export const comparablePriorDays = (priorDays: Date[], currentElapsedDays: number): Date[] =>
  priorDays.slice(0, currentElapsedDays);

export interface PercentChange {
  /** Absolute change, e.g. "12.4%". */
  label: string;
  direction: 'up' | 'down';
}

/** Null when there is nothing to compare against (a zero or missing prior). */
export const percentChange = (current: number, prior: number): PercentChange | null => {
  if (!(prior > 0)) {
    return null;
  }
  const change = ((current - prior) / prior) * 100;
  return { label: `${Math.abs(change).toFixed(1)}%`, direction: change >= 0 ? 'up' : 'down' };
};

export interface KpiSeries {
  values: number[];
  /** Index of today's bar within the period, or undefined when today is not in it. */
  todayIndex: number | undefined;
  /** Index of the first future bar, or undefined when the whole period has elapsed. */
  firstFutureIndex: number | undefined;
}

/** One value per day of the period for a KPI card's bar sparkline; future days read 0. */
export const buildKpiSeries = (
  periodDays: Date[],
  daysByDateKey: Map<string, UsageCalendarDay>,
  today: Date,
  valueOf: (day: UsageCalendarDay) => number,
): KpiSeries => {
  const todayKey = toDateKey(today);
  let todayIndex: number | undefined;
  let firstFutureIndex: number | undefined;
  const values = periodDays.map((date, index) => {
    const dateKey = toDateKey(date);
    if (dateKey === todayKey) {
      todayIndex = index;
    }
    if (dateKey > todayKey) {
      firstFutureIndex ??= index;
      return 0;
    }
    const day = daysByDateKey.get(dateKey);
    return day == null ? 0 : valueOf(day);
  });
  return { values, todayIndex, firstFutureIndex };
};

/** "2h 11m" / "45m" / "0m" for an active-time figure in seconds. */
export const formatActiveTime = (seconds: number): string => {
  const totalMinutes = Math.round(seconds / 60);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;
};

export interface ModelMixSegment {
  model: string;
  /** This model's share of the day's spend, 0-100. */
  percent: number;
  /** Index into the shared chart palette: the model's rank by spend that day, as the drawer ranks by window. */
  colorIndex: number;
}

/**
 * The week cell's "spend by model" bar: one segment per model that spent something, widest first (the
 * backend already sends them that way). Shares are of the models' own sum rather than of `costUsd`, so
 * the bar always fills exactly. Empty when nothing was spent, and the cell then draws no bar at all.
 */
export const buildModelMix = (day: UsageCalendarDay): ModelMixSegment[] => {
  const spending = day.costByModel.filter((entry) => entry.costUsd > 0);
  const total = spending.reduce((sum, entry) => sum + entry.costUsd, 0);
  if (!(total > 0)) {
    return [];
  }
  return spending.map((entry, index) => ({
    model: entry.model,
    percent: (entry.costUsd / total) * 100,
    colorIndex: index,
  }));
};

export const HOURS_PER_DAY = 24;

/** One value per local hour, hour 0 first, for each figure a week cell draws under its heading. */
export interface HourlySeries {
  cost: number[];
  tokens: number[];
  skills: number[];
  /** Active engagement seconds per hour: the "Hourly activity" chart at the foot of the cell. */
  activity: number[];
}

/**
 * A day's hourly buckets as four fixed 24-long series, or null when the rollup carried none (the
 * month view never asks for them, and a cell must not invent a shape). Bucketed by each bucket's own
 * `hour` rather than by position, so a reordered or gappy response still lands every value in its hour.
 * The three per-figure sparklines and the activity chart all read this one result, so the hourly rows
 * are walked once per day, not once per chart.
 */
export const buildHourlySeries = (day: UsageCalendarDay): HourlySeries | null => {
  const buckets = day.hourly;
  if (buckets == null || buckets.length === 0) {
    return null;
  }
  const emptySeries = (): number[] => new Array<number>(HOURS_PER_DAY).fill(0);
  const series: HourlySeries = {
    cost: emptySeries(),
    tokens: emptySeries(),
    skills: emptySeries(),
    activity: emptySeries(),
  };
  buckets.forEach((bucket) => {
    if (bucket.hour < 0 || bucket.hour >= HOURS_PER_DAY) {
      return;
    }
    series.cost[bucket.hour] = bucket.costUsd;
    series.tokens[bucket.hour] = bucket.tokens;
    series.skills[bucket.hour] = bucket.skillCalls;
    series.activity[bucket.hour] = bucket.activeSeconds;
  });
  return series;
};

export interface LinesChanged {
  added: number;
  removed: number;
  /** Share of the day's changed lines that were additions, 0-100; `removedPercent` is the rest. */
  addedPercent: number;
  removedPercent: number;
}

/**
 * The week cell's "lines changed" bar: the day's added / removed proportion. Null when no line changed,
 * so the cell draws no bar at all rather than an empty (or invented 50/50) track — the same rule the
 * spend-by-model bar follows.
 */
export const buildLinesChanged = (day: UsageCalendarDay): LinesChanged | null => {
  const total = day.linesAdded + day.linesRemoved;
  if (!(total > 0)) {
    return null;
  }
  const addedPercent = (day.linesAdded / total) * 100;
  return {
    added: day.linesAdded,
    removed: day.linesRemoved,
    addedPercent,
    removedPercent: 100 - addedPercent,
  };
};

/** "$73" from ten dollars up, "$4.20" below — the calendar cell has room for whole dollars only. */
export const formatCalendarUsd = (usd: number): string =>
  `$${usd.toLocaleString('en-US', {
    minimumFractionDigits: usd < 10 ? 2 : 0,
    maximumFractionDigits: usd < 10 ? 2 : 0,
  })}`;

export type KpiCardId = 'cost' | 'tokens' | 'skills' | 'activeDays';

export interface KpiCardModel {
  id: KpiCardId;
  label: string;
  value: string;
  /** Muted trailing text after the value, e.g. "/ 20" on the active-days card. */
  valueSuffix?: string;
  change: PercentChange | null;
  series: KpiSeries;
  /** Index into the shared chart palette; matches the metric's dot in the calendar cells. */
  colorIndex: number;
}

/** Height of a non-active day's bar on the active-days card, so a quiet day still reads as a day. */
const INACTIVE_DAY_BAR_VALUE = 0.08;

/**
 * The four KPI cards above the calendar. Deltas compare like-for-like: the current period's
 * elapsed days against the same number of days from the start of the prior period.
 */
export const buildKpiCards = (
  view: CalendarView,
  periodDays: Date[],
  priorPeriodDays: Date[],
  daysByDateKey: Map<string, UsageCalendarDay>,
  priorDaysByDateKey: Map<string, UsageCalendarDay>,
  today: Date,
): KpiCardModel[] => {
  const current = totalsForPeriod(periodDays, daysByDateKey, today);
  const comparableDays = comparablePriorDays(priorPeriodDays, current.elapsedDays);
  // Measured "as of" its own last day, so every comparable prior day counts as elapsed.
  const priorAsOf = comparableDays[comparableDays.length - 1] ?? today;
  const prior = totalsForPeriod(comparableDays, priorDaysByDateKey, priorAsOf);
  // A shorter prior month can return fewer days than were asked for, and the current side has to give up
  // the same days or the delta compares, say, 30 days against 28. Elapsed days are a prefix of the period.
  const comparableCurrent =
    comparableDays.length < current.elapsedDays
      ? totalsForPeriod(periodDays.slice(0, comparableDays.length), daysByDateKey, today)
      : current;
  const periodNoun = view === 'month' ? 'this month' : 'this week';
  const series = (valueOf: (day: UsageCalendarDay) => number): KpiSeries =>
    buildKpiSeries(periodDays, daysByDateKey, today, valueOf);
  return [
    {
      id: 'cost',
      label: `Cost ${periodNoun}`,
      value: formatCalendarUsd(current.costUsd),
      change: percentChange(comparableCurrent.costUsd, prior.costUsd),
      series: series((day) => day.costUsd),
      colorIndex: 0,
    },
    {
      id: 'tokens',
      label: `Tokens ${periodNoun}`,
      value: formatCompact(current.tokens),
      change: percentChange(comparableCurrent.tokens, prior.tokens),
      series: series((day) => day.tokens),
      colorIndex: 1,
    },
    {
      id: 'skills',
      label: 'Skill invocations',
      value: String(current.skillCalls),
      change: percentChange(comparableCurrent.skillCalls, prior.skillCalls),
      series: series((day) => day.skillCalls),
      colorIndex: 2,
    },
    {
      id: 'activeDays',
      label: 'Active days',
      value: String(current.activeDays),
      valueSuffix: `/ ${current.elapsedDays}`,
      change: percentChange(comparableCurrent.activeDays, prior.activeDays),
      series: series((day) => (isActiveDay(day) ? 1 : INACTIVE_DAY_BAR_VALUE)),
      colorIndex: 3,
    },
  ];
};

/**
 * "Cost & tokens by model" rows for one day, from the token-usage summary scoped to that day.
 * The bar and percentage are the model's share of tokens; the value pairs its tokens with the
 * cost the same summary attributes to it. Both come from the counter pipeline, so they reconcile
 * with the day's own cost and tokens figures rather than with the Cost page.
 */
export const buildModelRows = (summary: TokenUsageSummary | undefined): BreakdownRow[] => {
  if (summary == null) {
    return [];
  }
  const usdByModel = new Map(summary.cost.byModel.map((share) => [share.model, share.usd]));
  return summary.byModel.map((share) => {
    const usd = usdByModel.get(share.model);
    return {
      label: shortModelName(share.model),
      value: usd == null ? `${share.tokens} tokens` : `${usd} · ${share.tokens}`,
      percentage: share.share,
      colorIndex: share.colorIndex,
    };
  });
};
