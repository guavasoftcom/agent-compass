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
export interface WindowOption {
  value: number;
  label: string;
}

export const WINDOWS: readonly WindowOption[] = [
  { value: 5, label: '5 minutes' },
  { value: 15, label: '15 minutes' },
  { value: 30, label: '30 minutes' },
  { value: 60, label: '1 hour' },
  { value: 60 * 8, label: '8 hours' },
  { value: 60 * 24, label: '24 hours' },
  { value: 60 * 24 * 7, label: '7 days' },
  { value: 60 * 24 * 30, label: '30 days' },
];

// Rows-per-page options shared by every paged table footer (Sessions, Logs, Traces)
// so the choices stay in lockstep. The first entry is the default page size.
export const PAGE_SIZE_OPTIONS = [25, 50, 100] as const;

// Milliseconds-per-time-unit conversion factors, shared so the histogram bucket
// labels and the sample-data stores don't each redefine HOUR/DAY/MIN.
export const MS_PER_MINUTE = 60_000;
export const MS_PER_HOUR = 60 * MS_PER_MINUTE;
export const MS_PER_DAY = 24 * MS_PER_HOUR;

// Preset-only auto-refresh cadence, shared by every page's polling interval so
// the whole dashboard refetches on the same 60s beat.
export const AUTO_REFRESH_INTERVAL_MS = MS_PER_MINUTE;

// Live-tail poll cadence for the cursor-paged Stream views (Logs, Traces): how
// often each prepends genuinely new rows while auto-refresh is on. Aligned with
// Claude Code's OTEL_LOGS_EXPORT_INTERVAL and OTEL_TRACES_EXPORT_INTERVAL (5000ms).
export const TAIL_INTERVAL_MS = 5000;

// Widest span (in milliseconds) a resolved window may ever cover. Mirrors the
// backend's `@ValidDateRange(maxDays = 30)` / `DateRangeValidator`
// (`backend/src/main/java/com/guavasoft/agentcompass/validation/DateRangeValidator.java`),
// which 400s any request whose `startTimestamp`..`endTimestamp` span is strictly
// greater than 30 days. Both the custom-range picker (`WindowSelector`) and the
// shared `resolveWindow` preset clamp read this constant so the two caps can
// never drift apart. Keep this in lockstep with the backend's `maxDays`.
export const MAX_WINDOW_SPAN_MS = 30 * 24 * 60 * 60 * 1000;
