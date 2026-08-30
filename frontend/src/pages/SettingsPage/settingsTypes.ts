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
// Payload shapes for /api/system, mirroring the Java records one-for-one.
// Page-local rather than in `api/types.ts` because these endpoints serve exactly
// one page (same reasoning as `logsTypes.ts` / `tracesApi.ts`).

/** Whether overriding a property also requires a Flyway migration. */
export type SqlMirroring = 'NOT_MIRRORED' | 'MIRRORED' | 'SHARED_LITERAL';

export interface TableStorage {
  tableName: string;
  rowCount: number;
  heapBytes: number;
  indexBytes: number;
  toastBytes: number;
  totalBytes: number;
  /** Null when the table is empty. */
  oldestTimestamp: string | null;
  newestTimestamp: string | null;
  rowsLastSevenDays: number;
  /** Estimate: average on-disk bytes per row times the last seven days' insert rate. */
  estimatedBytesPerDay: number;
}

export interface StorageOverview {
  tables: TableStorage[];
  databaseTotalBytes: number;
  estimatedTotalBytesPerDay: number;
  measuredAt: string;
}

export interface SignalIngest {
  signal: string;
  tableName: string;
  newestTimestamp: string | null;
  newestReceivedAt: string | null;
  rowsLastHour: number;
  rowsLastDay: number;
  rowsLastWeek: number;
  nameCardinality: number;
  nameCardinalityLabel: string;
  /** Only metrics carry stream identity; null for logs and traces. */
  seriesCardinality: number | null;
}

export interface IngestHealth {
  signals: SignalIngest[];
  measuredAt: string;
}

export interface SchemaMigration {
  installedRank: number;
  version: string | null;
  description: string;
  type: string;
  script: string;
  success: boolean;
  executionTimeMillis: number;
  installedOn: string;
}

export interface SystemBuild {
  applicationVersion: string;
  buildTime: string | null;
  javaVersion: string;
  javaVendor: string;
  jvmName: string;
  postgresVersion: string;
  migrations: SchemaMigration[];
}

export interface ConfigurationEntry {
  propertyName: string;
  value: string;
  overridden: boolean;
  sqlMirroring: SqlMirroring;
  /** Migration versions carrying the literal, e.g. ["V14", "V19"]. Empty when not mirrored. */
  mirroredIn: string[];
  description: string;
}

export interface ConfigurationGroup {
  name: string;
  description: string;
  entries: ConfigurationEntry[];
}

export interface EffectiveConfiguration {
  groups: ConfigurationGroup[];
  propertyCount: number;
  overriddenCount: number;
}

export interface PurgeTableEstimate {
  tableName: string;
  timestampColumn: string;
  rowsToDelete: number;
  /**
   * Rows older than the cutoff that a purge deliberately keeps. Almost entirely rows
   * belonging to a session that is still active in some other signal — session.id is
   * common to all three tables, so a session is always purged whole or not at all,
   * never split. A small residual (metric_points only) keeps the newest row of every
   * stream even inside an otherwise-eligible session, so a still-live counter's next
   * emission always has a predecessor to compute its delta against.
   */
  preservedRows: number;
  totalRows: number;
  sharePercent: number;
  estimatedReclaimableBytes: number;
  statement: string;
}

export interface PurgeTableResult {
  tableName: string;
  rowsDeleted: number;
  rowsRemaining: number;
}

export interface PurgeResult {
  retentionDays: number;
  cutoff: string;
  tables: PurgeTableResult[];
  totalRowsDeleted: number;
  preservedRows: number;
  databaseTotalBytesBefore: number;
  databaseTotalBytesAfter: number;
  durationMillis: number;
  /** VACUUM FULL statement — not run by the purge, since it needs an exclusive lock. */
  reclaimSpaceSql: string;
  completedAt: string;
}

export interface PurgePreview {
  retentionDays: number;
  cutoff: string;
  tables: PurgeTableEstimate[];
  totalRowsToDelete: number;
  estimatedReclaimableBytes: number;
  sql: string;
}
