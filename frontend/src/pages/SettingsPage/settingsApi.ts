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
// Fetchers for /api/system. Page-local because every one of these endpoints
// serves only the Settings page — the same split `logsApi.ts` and `tracesApi.ts`
// follow. Transport comes from the shared `api/http` helper rather than a
// bespoke wrapper, so non-2xx responses throw with the same message shape as
// every other page.

import { getJson } from '../../api/http';
import type {
  EffectiveConfiguration,
  IngestHealth,
  PurgePreview,
  PurgeResult,
  StorageOverview,
  SystemBuild,
} from './settingsTypes';

/**
 * Phrase the operator must type to enable the purge. Sent as a query param and
 * re-checked server-side, so a client that skipped the dialog still cannot
 * delete anything by accident.
 */
export const PURGE_CONFIRMATION_PHRASE = 'PURGE';

export const fetchStorageOverview = (): Promise<StorageOverview> =>
  getJson('/api/system/storage');

export const fetchIngestHealth = (): Promise<IngestHealth> => getJson('/api/system/ingest');

export const fetchSystemBuild = (): Promise<SystemBuild> => getJson('/api/system/build');

export const fetchEffectiveConfiguration = (): Promise<EffectiveConfiguration> =>
  getJson('/api/system/configuration');

export const fetchPurgePreview = (retentionDays: number): Promise<PurgePreview> =>
  getJson(`/api/system/purge-preview?days=${retentionDays}`);

/**
 * Permanently deletes telemetry older than `retentionDays`. Irreversible.
 *
 * The only write the dashboard performs. Deliberately not routed through the
 * shared `getJson` helper: this needs DELETE rather than GET, and it can run for
 * minutes on a large database, so it gets no client-side timeout and surfaces
 * the server's own error text — a refused confirmation reads as a 400 whose body
 * explains itself, which is worth showing verbatim.
 */
export const purgeTelemetry = async (retentionDays: number): Promise<PurgeResult> => {
  const path =
    `/api/system/telemetry?days=${retentionDays}` +
    `&confirmation=${encodeURIComponent(PURGE_CONFIRMATION_PHRASE)}`;
  const response = await fetch(path, { method: 'DELETE' });
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    throw new Error(detail || `${path} → ${response.status} ${response.statusText}`);
  }
  return response.json() as Promise<PurgeResult>;
};
