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
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { TraceCursorPage, TraceFacets, TraceHistogram, TracesListResult } from './tracesApi';
import { RUNNING_TRACE_POLL_INTERVAL_MS } from './tracesApi';
import type { TraceRow } from '../../api';

const histogramFixture: TraceHistogram = {
  bucketMs: 60_000,
  buckets: [{ t0: '2026-08-30T09:59:00.000Z', t1: '2026-08-30T10:00:00.000Z', ok: 5, error: 0, p95Ms: 900 }],
  p50Ms: 400,
  p95Ms: 900,
  total: 5,
  errorCount: 0,
};

const facetsFixture: TraceFacets = {
  status: [{ value: 'ok', count: 5 }],
  operation: [{ value: 'claude_code.interaction', count: 5 }],
  service: [{ value: 'claude-code', count: 5 }],
  duration: [{ value: 'd1', count: 5 }],
  session: [{ value: 'session-1', count: 5 }],
};

const cursorPageFixture: TraceCursorPage = {
  items: [],
  nextCursor: null,
  hasMore: false,
  totalCount: 0,
};

const tablePageFixture: TracesListResult = { items: [], totalCount: 0 };

const fetchTraceHistogram = vi.fn().mockResolvedValue(histogramFixture);
const fetchTraceFacets = vi.fn().mockResolvedValue(facetsFixture);
const fetchTracesCursor = vi.fn().mockResolvedValue(cursorPageFixture);
const fetchTracesPage = vi.fn().mockResolvedValue(tablePageFixture);
const fetchTraceSummaryOrNull = vi.fn().mockResolvedValue(null);

vi.mock('./tracesApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./tracesApi')>();
  return {
    ...actual,
    fetchTraceHistogram: (...args: unknown[]) => fetchTraceHistogram(...args),
    fetchTraceFacets: (...args: unknown[]) => fetchTraceFacets(...args),
    fetchTracesCursor: (...args: unknown[]) => fetchTracesCursor(...args),
    fetchTracesPage: (...args: unknown[]) => fetchTracesPage(...args),
    fetchTraceSummaryOrNull: (...args: unknown[]) => fetchTraceSummaryOrNull(...args),
  };
});

// Imported after the mock so the hook picks up the mocked fetchers.
const { default: useTracesExplorer } = await import('./useTracesExplorer');

const baseParams = {
  startTimestamp: '2026-08-29T10:00:00.000Z',
  endTimestamp: '2026-08-30T10:01:00.000Z',
  autoRefresh: false,
  onAutoRefreshChange: vi.fn(),
  repositoryUrl: null as string | null,
};

const buildWrapper = (queryClient: QueryClient) => {
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return Wrapper;
};

const runningTraceRow: TraceRow = {
  traceId: 'running-trace',
  startTimestamp: '2026-08-30T10:00:00.000Z',
  rootSpanName: 'session.turn',
  rootSpanId: 'span-1',
  sessionId: 'session-1',
  spanCount: 4,
  durationNanos: 1_000_000,
  errorCount: 0,
  totalTokens: 0,
  totalCostUsd: 0,
  firstUserPrompt: null,
  inProgress: true,
};

describe('useTracesExplorer', () => {
  afterEach(() => {
    vi.useRealTimers();
    fetchTracesPage.mockReset().mockResolvedValue(tablePageFixture);
    fetchTraceSummaryOrNull.mockReset().mockResolvedValue(null);
  });


  // Mirrors how a window-selection change already resets the stream cursor for LogsPageView:
  // both a window change and a repository change ride the same `filters` object, so both
  // produce a new `filtersKey` and re-trigger the "reset stream" effect, which starts the
  // stream over with `cursor: null` rather than paging on a cursor built under the old
  // repository's result set.
  it('resets the stream cursor (fresh cursor: null fetch) when repositoryUrl changes, the same way a window change already does', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result, rerender } = renderHook((props) => useTracesExplorer(props), {
      wrapper: buildWrapper(queryClient),
      initialProps: { ...baseParams, repositoryUrl: 'https://github.com/example/repo-a' },
    });

    await waitFor(() => {
      expect(fetchTracesCursor).toHaveBeenCalled();
    });
    const callCountBeforeRepositoryChange = fetchTracesCursor.mock.calls.length;

    rerender({ ...baseParams, repositoryUrl: 'https://github.com/example/repo-b' });

    await waitFor(() => {
      expect(fetchTracesCursor.mock.calls.length).toBeGreaterThan(callCountBeforeRepositoryChange);
    });

    const [filtersArg, pageArg] = fetchTracesCursor.mock.calls[fetchTracesCursor.mock.calls.length - 1];
    // resetStream always requests a fresh first page, never a continuation of the old cursor.
    expect(pageArg).toMatchObject({ cursor: null, limit: 60 });
    expect(filtersArg).toMatchObject({ repositoryUrl: 'https://github.com/example/repo-b' });
    expect(result.current.streamRows).toEqual([]);
  });

  it('includes repositoryUrl in the histogram/facets/table query keys so a stale cache never serves cross-repo data', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderHook((props) => useTracesExplorer(props), {
      wrapper: buildWrapper(queryClient),
      initialProps: { ...baseParams, repositoryUrl: 'https://github.com/example/repo-a' },
    });

    await waitFor(() => {
      expect(fetchTraceHistogram).toHaveBeenCalled();
      expect(fetchTraceFacets).toHaveBeenCalled();
    });

    const [filtersArg] = fetchTraceHistogram.mock.calls[0];
    expect(filtersArg).toMatchObject({ repositoryUrl: 'https://github.com/example/repo-a' });
    const queryKeys = queryClient
      .getQueryCache()
      .getAll()
      .map((query) => JSON.stringify(query.queryKey));
    expect(queryKeys.some((key) => key.includes('repo-a'))).toBe(true);
  });

  it('polls the table query every RUNNING_TRACE_POLL_INTERVAL_MS while a returned row is inProgress, and stops once it is not', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    fetchTracesPage.mockResolvedValue({ items: [runningTraceRow], totalCount: 1 });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook((props) => useTracesExplorer(props), {
      wrapper: buildWrapper(queryClient),
      initialProps: baseParams,
    });

    act(() => {
      result.current.onViewChange('table');
    });

    await waitFor(() => {
      expect(fetchTracesPage).toHaveBeenCalled();
    });
    const callsWhileRunning = fetchTracesPage.mock.calls.length;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(RUNNING_TRACE_POLL_INTERVAL_MS);
    });

    // Unconditional on autoRefresh (baseParams.autoRefresh is false) — same
    // reasoning as Sessions' running-row poll.
    expect(fetchTracesPage.mock.calls.length).toBeGreaterThan(callsWhileRunning);

    // The row finishes: the next resolved page carries no in-progress row, so
    // the poll should stop scheduling further fetches.
    fetchTracesPage.mockResolvedValue({
      items: [{ ...runningTraceRow, inProgress: false }],
      totalCount: 1,
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(RUNNING_TRACE_POLL_INTERVAL_MS);
    });
    const callsAfterFinished = fetchTracesPage.mock.calls.length;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(RUNNING_TRACE_POLL_INTERVAL_MS * 3);
    });

    expect(fetchTracesPage.mock.calls.length).toBe(callsAfterFinished);
  });

  it('patches an already-loaded in-progress stream row in place via fetchTraceSummaryOrNull (Stream view only)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    fetchTracesCursor.mockResolvedValueOnce({
      items: [runningTraceRow],
      nextCursor: null,
      hasMore: false,
      totalCount: 1,
    });
    fetchTraceSummaryOrNull.mockResolvedValue({ ...runningTraceRow, inProgress: false });

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    // autoRefresh: false — live tail stays off, so this exercises only the
    // second, independent patch effect, not the tail-prepend effect.
    const { result } = renderHook((props) => useTracesExplorer(props), {
      wrapper: buildWrapper(queryClient),
      initialProps: baseParams,
    });

    await waitFor(() => {
      expect(result.current.streamRows).toHaveLength(1);
      expect(result.current.streamRows[0].inProgress).toBe(true);
    });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(RUNNING_TRACE_POLL_INTERVAL_MS);
    });

    expect(fetchTraceSummaryOrNull).toHaveBeenCalledWith('running-trace');
    await waitFor(() => {
      expect(result.current.streamRows[0].inProgress).toBe(false);
    });
  });
});
