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
import { getJson } from '../../api/http';

/**
 * One local calendar day from `GET /api/usage/calendar/daily` — mirrors the backend's
 * `UsageCalendarDay` record field-for-field. Every figure is zero on a day where nothing happened;
 * the endpoint returns exactly one row per day in the requested range.
 *
 * Cost and tokens are counter-derived (the Tokens and Sessions pipeline), NOT the `api_request`
 * figures the Cost page reads, so a day's cost here can read slightly off the Cost page for the
 * same span. Never blend the two in one sentence.
 */
export interface UsageCalendarDay {
  /** Local calendar day in the requested time zone, `YYYY-MM-DD`. */
  date: string;
  costUsd: number;
  /** Every token type summed, cache reads included — the Tokens page's total. */
  tokens: number;
  skillCalls: number;
  subagentCalls: number;
  sessions: number;
  activeSeconds: number;
  linesAdded: number;
  linesRemoved: number;
  commits: number;
  pullRequests: number;
  decisionsAccepted: number;
  decisionsRejected: number;
  /** The day's spend split by model, largest first; empty on a day with no cost. Sums to `costUsd`. */
  costByModel: UsageCalendarModelCost[];
  /**
   * The day split into 24 local hour-of-day buckets, hour 0 first. Present only when the request asked
   * for `granularity: 'hourly'` (the week view); null or absent otherwise.
   */
  hourly?: UsageCalendarHour[] | null;
}

/** One local hour of a day; mirrors the backend's `UsageCalendarHour`. The 24 of a day sum to its figures. */
export interface UsageCalendarHour {
  /** Local hour of day, 0-23. */
  hour: number;
  costUsd: number;
  tokens: number;
  skillCalls: number;
  activeSeconds: number;
}

/** One model's counter-derived spend on one day; mirrors the backend's `UsageCalendarModelCost`. */
export interface UsageCalendarModelCost {
  /** Raw model id; `'unknown'` when a cost point carried none. */
  model: string;
  costUsd: number;
}

interface UsageCalendarDailyResponse {
  days: UsageCalendarDay[];
}

/** Half-open `[from, to)` range of whole local days, plus the zone that defines a day. */
export interface UsageCalendarDailyParams {
  /** ISO-8601 instant of the first local midnight. */
  from: string;
  /** ISO-8601 instant of the local midnight AFTER the last day wanted (exclusive). */
  to: string;
  /** IANA zone the day boundaries were computed in, e.g. `America/Chicago`. */
  timeZone: string;
  /** Repository scope; `null`/omitted = all repositories. */
  repositoryUrl?: string | null;
  /**
   * `'hourly'` also fills each day's 24 hourly buckets — ask for it only over the range that draws them
   * (the week), since it costs the backend two more grouped queries. Omitted = `'daily'`.
   */
  granularity?: 'daily' | 'hourly';
}

/**
 * GET /api/usage/calendar/daily — one row per local day in `[from, to)`, oldest first. Returns the
 * unwrapped `days` array.
 */
export const fetchUsageCalendarDaily = async (
  params: UsageCalendarDailyParams,
): Promise<UsageCalendarDay[]> => {
  const query = new URLSearchParams({
    from: params.from,
    to: params.to,
    timeZone: params.timeZone,
  });
  if (params.repositoryUrl) {
    query.set('repositoryUrl', params.repositoryUrl);
  }
  if (params.granularity === 'hourly') {
    query.set('granularity', 'hourly');
  }
  const response = await getJson<UsageCalendarDailyResponse>(
    `/api/usage/calendar/daily?${query.toString()}`,
  );
  return response.days;
};
