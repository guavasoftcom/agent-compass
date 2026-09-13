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

import { getJson, writeJson } from '../../api/http';
import type {
  EffectiveConfiguration,
  IngestHealth,
  OllamaConnectionTestResult,
  OllamaModelListResult,
  OllamaSettings,
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
 * The only write the dashboard performs. Routed through `writeJson` rather
 * than `getJson`: this needs DELETE rather than GET, and it can run for
 * minutes on a large database, so it gets no client-side timeout and surfaces
 * the server's own error text — a refused confirmation reads as a 400 whose body
 * explains itself, which is worth showing verbatim.
 */
export const purgeTelemetry = (retentionDays: number): Promise<PurgeResult> => {
  const path =
    `/api/system/telemetry?days=${retentionDays}` +
    `&confirmation=${encodeURIComponent(PURGE_CONFIRMATION_PHRASE)}`;
  return writeJson(path, 'DELETE');
};

export const fetchOllamaSettings = (): Promise<OllamaSettings> =>
  getJson('/api/system/ollama-settings');

/**
 * Saves a new base URL / model / enabled override, or clears baseUrl/model
 * back to the `application.yml` default when that field is blank. Routed
 * through `writeJson` rather than `getJson` — this is a write, not a
 * cacheable fetch, and a validation failure's plain-text body (e.g. an
 * unparsable URL) is worth showing verbatim rather than collapsing into a
 * generic status-text message. `enabled` has no blank/clear form the way
 * baseUrl/model do — a toggle is always on or off — so it is always sent as
 * an explicit boolean.
 */
export const saveOllamaSettings = (
  baseUrl: string,
  model: string,
  enabled: boolean,
): Promise<OllamaSettings> =>
  writeJson('/api/system/ollama-settings', 'PUT', {
    baseUrl: baseUrl.trim() || null,
    model: model.trim() || null,
    enabled,
  });

/**
 * Pings Ollama at the given (possibly unsaved/just-typed) base URL and model.
 * Always resolves — success/failure is carried in the response body, not an
 * HTTP error status, so "unreachable" renders as a normal result rather than
 * a query/mutation error. Routed through `writeJson` for the same reason
 * `saveOllamaSettings` is: this is a probe, not a cacheable read.
 */
export const testOllamaConnection = (
  baseUrl: string,
  model: string,
): Promise<OllamaConnectionTestResult> =>
  writeJson('/api/system/ollama/test-connection', 'POST', { baseUrl, model });

/**
 * Lists models installed on the Ollama instance at the given base URL, for
 * the Model field's `Autocomplete` options. Always resolves — like
 * `testOllamaConnection`, success/failure is carried in the response body,
 * and `models` comes back as `[]` (never null) on any failure, so a caller
 * can use the result directly as an options list without a null check.
 * Unlike the mutations above, this fetcher is meant to be wrapped in a
 * `useQuery`, not called imperatively — still routed through `writeJson`
 * rather than `getJson` for the same reason `testOllamaConnection` is:
 * `getJson` doesn't support a request body.
 */
export const fetchOllamaModels = (baseUrl: string): Promise<OllamaModelListResult> =>
  writeJson('/api/system/ollama/models', 'POST', { baseUrl: baseUrl.trim() || null });
