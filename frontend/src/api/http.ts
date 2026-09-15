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
// Transport plumbing shared by every fetcher in `endpoints.ts`. These thin
// wrappers around `fetch` centralize error handling (non-2xx → throw) and the
// `WindowSelection` → query-param flattening. Not part of the public API surface
// — the barrel re-exports `endpoints.ts` and `types.ts`, not this module.

import type { ListResult, WindowSelection } from './types';

export const getJson = async <T>(path: string, signal?: AbortSignal): Promise<T> => {
  const res = await fetch(path, { signal });
  if (!res.ok) {
    throw new Error(`${path} → ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<T>;
};

const getJsonWithHeaders = async <T>(
  path: string,
): Promise<{ body: T; headers: Headers }> => {
  const res = await fetch(path);
  if (!res.ok) {
    throw new Error(`${path} → ${res.status} ${res.statusText}`);
  }
  const body = (await res.json()) as T;
  return { body, headers: res.headers };
};

export const getText = async (path: string): Promise<string> => {
  const res = await fetch(path);
  if (!res.ok) {
    throw new Error(`${path} → ${res.status} ${res.statusText}`);
  }
  return res.text();
};

/**
 * Shared write path for a mutating fetch (`PUT`/`POST`/`DELETE`, optionally
 * with a JSON body) whose non-2xx response should surface the server's own
 * plain-text body rather than a generic status-text message — a validation
 * failure or a refused confirmation phrase explains itself in that body.
 * `getJson` can't serve these callers: it never sends a body and always
 * throws the generic `status statusText` message. Not a cacheable read, so
 * this stays out of the barrel the same way `getJson`'s siblings do.
 */
export const writeJson = async <T>(
  path: string,
  method: string,
  body?: unknown,
): Promise<T> => {
  const res = await fetch(path, {
    method,
    ...(body !== undefined
      ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
      : {}),
  });
  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    throw new Error(detail || `${path} → ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<T>;
};

export const listWithTotalCount = async <T>(
  path: string,
): Promise<ListResult<T>> => {
  const { body, headers } = await getJsonWithHeaders<T[]>(path);
  const totalHeader = headers.get('X-Total-Count');
  const totalCount = totalHeader != null ? Number(totalHeader) : null;
  return { items: body, totalCount };
};

export const windowQueryParams = (
  selection: WindowSelection,
): URLSearchParams => {
  const params = new URLSearchParams();
  if (selection.kind === 'preset') {
    params.set('minutes', String(selection.minutes));
  } else {
    params.set('startTimestamp', selection.startTimestamp);
    params.set('endTimestamp', selection.endTimestamp);
  }
  // Omitted/null means "all repositories" — today's behavior, unchanged. Only a
  // real repository URL or the reserved UNATTRIBUTED_REPOSITORY sentinel sets
  // the param, matching the backend's `:repositoryUrl IS NULL OR ...` contract.
  if (selection.repositoryUrl != null) {
    params.set('repositoryUrl', selection.repositoryUrl);
  }
  return params;
};
