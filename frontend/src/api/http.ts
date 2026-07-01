// Transport plumbing shared by every fetcher in `endpoints.ts`. These thin
// wrappers around `fetch` centralize error handling (non-2xx → throw) and the
// `WindowSelection` → query-param flattening. Not part of the public API surface
// — the barrel re-exports `endpoints.ts` and `types.ts`, not this module.

import type { ListResult, WindowSelection } from './types';

export const getJson = async <T>(path: string): Promise<T> => {
  const res = await fetch(path);
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
  return params;
};
