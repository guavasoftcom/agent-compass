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
