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
// Pure helpers for the Settings page. Kept out of the view so the arithmetic —
// share percentages, freshness bucketing, the retention-span label — is testable
// without rendering anything.

import type {
  ConfigurationEntry,
  ConfigurationGroup,
  SignalIngest,
  SqlMirroring,
  TableStorage,
} from './settingsTypes';

/** How stale the newest received row is allowed to be before the signal reads as degraded. */
const FRESH_THRESHOLD_MINUTES = 15;
const STALE_THRESHOLD_HOURS = 24;

const MILLISECONDS_PER_MINUTE = 60_000;
const MINUTES_PER_HOUR = 60;
const HOURS_PER_DAY = 24;
const PERCENT_SCALE = 100;

export type IngestFreshness = 'live' | 'delayed' | 'stale' | 'empty';

/**
 * Buckets a signal by how long ago this server last received a row for it.
 *
 * Deliberately reads `newestReceivedAt` (when the row landed here) rather than
 * `newestTimestamp` (when the agent says it happened): a collector that stopped
 * forwarding is the failure this is meant to catch, and the two differ by
 * milliseconds when everything is working.
 */
export const ingestFreshness = (
  signal: SignalIngest,
  now: number = Date.now(),
): IngestFreshness => {
  if (!signal.newestReceivedAt) {
    return 'empty';
  }
  const minutesAgo = (now - new Date(signal.newestReceivedAt).getTime()) / MILLISECONDS_PER_MINUTE;
  if (minutesAgo <= FRESH_THRESHOLD_MINUTES) {
    return 'live';
  }
  if (minutesAgo <= STALE_THRESHOLD_HOURS * MINUTES_PER_HOUR) {
    return 'delayed';
  }
  return 'stale';
};

/** Share of the database a table occupies, 0–100. Zero when the total is unknown. */
export const storageSharePercent = (table: TableStorage, databaseTotalBytes: number): number => {
  if (databaseTotalBytes <= 0) {
    return 0;
  }
  return (table.totalBytes / databaseTotalBytes) * PERCENT_SCALE;
};

/**
 * Whole days between a table's oldest and newest row — how much history it
 * actually holds. Null when the table is empty or holds a single instant.
 */
export const retentionSpanDays = (table: TableStorage): number | null => {
  if (!table.oldestTimestamp || !table.newestTimestamp) {
    return null;
  }
  const spanMilliseconds =
    new Date(table.newestTimestamp).getTime() - new Date(table.oldestTimestamp).getTime();
  if (spanMilliseconds <= 0) {
    return null;
  }
  return Math.floor(spanMilliseconds / (MILLISECONDS_PER_MINUTE * MINUTES_PER_HOUR * HOURS_PER_DAY));
};

/**
 * Days until the database reaches a size, at the current estimated growth rate.
 * Null when nothing is growing or the ceiling is already behind us — the caller
 * renders that as "—" rather than an infinity.
 */
export const daysUntilSize = (
  currentBytes: number,
  targetBytes: number,
  estimatedBytesPerDay: number,
): number | null => {
  if (estimatedBytesPerDay <= 0 || targetBytes <= currentBytes) {
    return null;
  }
  return Math.round((targetBytes - currentBytes) / estimatedBytesPerDay);
};

/** Case-insensitive match of a query against a property's name, value, and description. */
export const matchesConfigurationQuery = (entry: ConfigurationEntry, query: string): boolean => {
  const trimmed = query.trim().toLowerCase();
  if (!trimmed) {
    return true;
  }
  return (
    entry.propertyName.toLowerCase().includes(trimmed) ||
    entry.value.toLowerCase().includes(trimmed) ||
    entry.description.toLowerCase().includes(trimmed)
  );
};

/**
 * Filters every group's entries, dropping groups left with nothing. Returning
 * empty groups instead would leave the page showing headings above blank space.
 */
export const filterConfigurationGroups = (
  groups: ConfigurationGroup[],
  query: string,
): ConfigurationGroup[] =>
  groups
    .map((group) => ({
      ...group,
      entries: group.entries.filter((entry) => matchesConfigurationQuery(entry, query)),
    }))
    .filter((group) => group.entries.length > 0);

/** Human label for the mirroring flag, used by both the chip and its tooltip. */
export const sqlMirroringLabel = (mirroring: SqlMirroring): string => {
  if (mirroring === 'MIRRORED') {
    return 'Needs a migration';
  }
  if (mirroring === 'SHARED_LITERAL') {
    return 'Shares a SQL literal';
  }
  return 'Safe to override';
};

/** Longer explanation shown on hover — why the flag matters, not just what it says. */
export const sqlMirroringExplanation = (mirroring: SqlMirroring, mirroredIn: string[]): string => {
  if (mirroring === 'MIRRORED') {
    return (
      `This value is written as a literal in ${mirroredIn.join(', ')}. Overriding it without a ` +
      'new migration redefining those objects makes the affected pages read the wrong rows — ' +
      'silently, with no error.'
    );
  }
  if (mirroring === 'SHARED_LITERAL') {
    return (
      `${mirroredIn.join(', ')} hardcodes this same literal for its own reasons. Overriding the ` +
      'property does not invalidate that SQL, but the two then describe different events.'
    );
  }
  return 'Read only through bind parameters, so application.yml is the single source of truth.';
};
