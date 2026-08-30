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
// Formatters shared between SessionsKpiStrip and SessionsTable.
//
// Cache efficiency deliberately does NOT live here: the Tokens page needs the same
// ratio and bands, so they live in `lib/cacheEfficiency.ts` alongside the note about
// the three backend expressions that have to match. Import them from there.
//
// USD_FORMATTER, formatTimestamp, and formatRelativeTime also do NOT live here:
// the Tokens page's cache-efficiency rank card and detail dialog need the
// identical formatters, so they're defined once in `lib/format.ts` and
// re-exported below so existing SessionsPage imports keep working unchanged.

export { USD_FORMATTER, formatTimestamp, formatRelativeTime } from '../../../lib/format';

export const USD_PER_MINUTE_FORMATTER = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 3,
  maximumFractionDigits: 3,
});

// Seconds → human-readable string: "45s" / "2m 30s" / "1h 5m".
// Note: this formats wall-clock seconds, not nanoseconds — it is distinct from
// TracesPage's nanosecond formatDuration and should not be shared with it.
export const formatDuration = (seconds: number): string => {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return '—';
  }
  if (seconds < 60) {
    return `${Math.round(seconds)}s`;
  }
  if (seconds < 3600) {
    const minutes = Math.floor(seconds / 60);
    const remainder = Math.round(seconds % 60);
    return remainder === 0 ? `${minutes}m` : `${minutes}m ${remainder}s`;
  }
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.round((seconds % 3600) / 60);
  return minutes === 0 ? `${hours}h` : `${hours}h ${minutes}m`;
};

// Compact token formatter (raw int → "5.4M" / "820K"); matches the Cost column's
// raw-number / client-format convention used across the Sessions page. Shared
// by the grid's Tokens column and PromptTimelinePanel's per-turn token line so
// both the K/M rounding and the zero-case ("—") stay in exact lockstep.
export const formatTokens = (value: number): string => {
  if (!Number.isFinite(value) || value <= 0) {
    return '—';
  }
  // Round-trip through the K bucket first: a value like 999,600 rounds to
  // "1000K" if formatted directly against the 1e6 threshold, so promote
  // anything that rounds up to 1000K into the M bucket instead.
  const thousands = Math.round(value / 1e3);
  if (value >= 1e6 || thousands >= 1000) {
    return `${(value / 1e6).toFixed(1).replace(/\.0$/, '')}M`;
  }
  if (value >= 1e3) {
    return `${thousands}K`;
  }
  return `${Math.round(value)}`;
};

// Cost-outlier tiers for the grid's Cost column and the drawer header's cost
// figure. Thresholds mirror the page's own P95 stat card so the highlighting
// is never arbitrary: 'warm' starts at a flat $8, 'hot' at the live P95
// cost/session figure (passed in rather than hardcoded, so the two can never
// drift apart).
export const COST_WARM_THRESHOLD_USD = 8;

export type CostTier = 'plain' | 'warm' | 'hot';

export const costTier = (costUsd: number, hotThresholdUsd: number): CostTier => {
  if (costUsd >= hotThresholdUsd) {
    return 'hot';
  }
  if (costUsd >= COST_WARM_THRESHOLD_USD) {
    return 'warm';
  }
  return 'plain';
};

// Compact "Aug 9, 10:36 AM" — for the session detail drawer's metadata line,
// where the full locale string formatTimestamp returns is too long to sit next
// to three other facts. Seconds are dropped deliberately: the line describes
// when a session began, not an event to correlate.
export const formatShortTimestamp = (value: string): string =>
  value
    ? new Date(value).toLocaleString('en-US', {
        month: 'short',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit',
      })
    : '';
