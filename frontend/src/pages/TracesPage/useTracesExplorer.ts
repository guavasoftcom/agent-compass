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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { TraceRow } from '../../api';
import {
  RUNNING_TRACE_POLL_INTERVAL_MS,
  fetchTraceFacets,
  fetchTraceHistogram,
  fetchTraceSummaryOrNull,
  fetchTracesCursor,
  fetchTracesPage,
  serviceOf,
  type FacetKey,
  type TraceCursor,
  type TraceHistogramBucket,
  type TraceSortKey,
  type TracesFilters,
  type TraceStatus,
} from './tracesApi';
import type { HistogramSeries } from './components/TraceHistogram';
import type { TraceFacetSelections } from './components/TraceFacetRail';
import type { TraceView } from './components/TraceViewToggle';
import { TAIL_INTERVAL_MS } from '../../lib/constants';
import { useDebouncedValue } from '../../lib/useDebouncedValue';

const STREAM_PAGE = 60;
const TAIL_PAGE = 20;
const HISTOGRAM_BUCKETS = 48;

const emptySelections = (): TraceFacetSelections => ({
  status: new Set(),
  operation: new Set(),
  service: new Set(),
  duration: new Set(),
  session: new Set(),
});

export interface ZoomRange {
  startTimestamp: string;
  endTimestamp: string;
  label: string;
}

const formatZoomLabel = (startIso: string, endIso: string): string => {
  const start = new Date(startIso);
  const end = new Date(endIso);
  const formatTime = (date: Date) => date.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
  return `${start.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} · ${formatTime(start)}–${formatTime(end)}`;
};

export interface UseTracesExplorerParams {
  startTimestamp: string;
  endTimestamp: string;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  repositoryUrl: string | null;
}

const useTracesExplorer = ({
  startTimestamp,
  endTimestamp,
  autoRefresh,
  onAutoRefreshChange,
  repositoryUrl,
}: UseTracesExplorerParams) => {
  const [search, setSearch] = useState('');
  const [facetSelections, setFacetSelections] = useState<TraceFacetSelections>(emptySelections);
  const [hiddenHistogramSeries, setHiddenHistogramSeries] = useState<Set<HistogramSeries>>(new Set());
  const [view, setView] = useState<TraceView>('stream');
  const [sort, setSort] = useState<TraceSortKey>('new');
  const [zoom, setZoom] = useState<ZoomRange | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  // Live tail and Auto-refresh are one linked switch (Stream-only, newest-first only).
  // The tail cursor is the row at the head of the stream, which is only the *newest* trace
  // under sort='new'. Under any other sort the head is the slowest/most-errored/etc trace, so
  // polling `after=<that row>` would prepend unrelated traces and silently break the ordering.
  const tail = autoRefresh && view === 'stream' && zoom == null && sort === 'new';

  // stream cursor-paging state
  const [streamRows, setStreamRows] = useState<TraceRow[]>([]);
  const [streamCursor, setStreamCursor] = useState<TraceCursor | null>(null);
  const [streamHasMore, setStreamHasMore] = useState(true);
  const [streamLoading, setStreamLoading] = useState(false);
  const [streamTotal, setStreamTotal] = useState(0);

  // table offset state
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);

  // The search box stays bound to `search` (immediate) so typing never lags;
  // only the debounced copy feeds `filters`, so a query fires ~250ms after the
  // user stops typing instead of on every keystroke.
  const debouncedSearch = useDebouncedValue(search);

  // repositoryUrl rides this same filters object (not a second fetcher argument), so a
  // repository change falls into `filtersKey` exactly like a window/facet/search change
  // already does — that's what drives the stream-reset effect below to reset the
  // live-tail cursor on a repository change too, mirroring LogsPageView's `filters`.
  const filters = useMemo<TracesFilters>(
    () => ({
      startTimestamp: zoom ? zoom.startTimestamp : startTimestamp,
      endTimestamp: zoom ? zoom.endTimestamp : endTimestamp,
      status: [...facetSelections.status] as TraceStatus[],
      operation: [...facetSelections.operation],
      service: [...facetSelections.service],
      duration: [...facetSelections.duration],
      session: [...facetSelections.session],
      q: debouncedSearch || undefined,
      repositoryUrl,
    }),
    [zoom, startTimestamp, endTimestamp, facetSelections, debouncedSearch, repositoryUrl],
  );
  const filtersKey = JSON.stringify(filters);

  const histogramQuery = useQuery({
    queryKey: ['trace-histogram', filtersKey],
    queryFn: () => fetchTraceHistogram(filters, HISTOGRAM_BUCKETS),
  });
  const facetsQuery = useQuery({
    queryKey: ['trace-facets', filtersKey],
    queryFn: () => fetchTraceFacets(filters),
  });
  const tableQuery = useQuery({
    queryKey: ['trace-table', filtersKey, sort, page, pageSize],
    queryFn: () => fetchTracesPage(filters, sort, page, pageSize),
    enabled: view === 'table',
    // Unconditional on autoRefresh — same reasoning as Sessions' running-row
    // poll (SessionsPage.tsx): a displayed "running" dot is a promise the UI
    // has to keep regardless of the user's auto-refresh preference. Table
    // view's "nothing running" refresh is already covered by the page's
    // existing 60s preset-driven reload, so no extra fallback branch is needed
    // here.
    refetchInterval: (query) => {
      const hasRunningRow = query.state.data?.items.some((row) => row.inProgress) ?? false;
      return hasRunningRow ? RUNNING_TRACE_POLL_INTERVAL_MS : false;
    },
  });

  // Mirror the latest values into refs so the stable stream callbacks below
  // can read fresh state without being torn down and recreated on every change.
  const filtersRef = useRef(filters);
  const sortRef = useRef(sort);
  const streamCursorRef = useRef(streamCursor);
  const streamHasMoreRef = useRef(streamHasMore);
  const streamLoadingRef = useRef(streamLoading);
  const streamRowsRef = useRef(streamRows);
  // TanStack Query hands back a new result object on every render, so keeping the query itself
  // in an effect dep array retriggers that effect constantly. Only the refetch function is
  // needed here, and it goes through a ref for the same reason.
  const refetchHistogramRef = useRef(histogramQuery.refetch);
  const requestSequence = useRef(0);
  useEffect(() => {
    filtersRef.current = filters;
    sortRef.current = sort;
    streamCursorRef.current = streamCursor;
    streamHasMoreRef.current = streamHasMore;
    streamLoadingRef.current = streamLoading;
    streamRowsRef.current = streamRows;
    refetchHistogramRef.current = histogramQuery.refetch;
  });

  const resetStream = useCallback(async (signal?: AbortSignal) => {
    const requestId = (requestSequence.current += 1);
    setStreamLoading(true);
    setStreamRows([]);
    setStreamCursor(null);
    setStreamHasMore(true);
    const result = await fetchTracesCursor(
      filtersRef.current,
      { sort: sortRef.current, cursor: null, limit: STREAM_PAGE },
      signal,
    ).catch((error) => {
      // StrictMode double-invokes this effect on mount; the cleanup below aborts
      // the superseded request so only one real fetch reaches the backend.
      if (signal?.aborted) {
        return null;
      }
      throw error;
    });
    if (result === null || requestId !== requestSequence.current) {
      return;
    }
    setStreamRows(result.items);
    setStreamCursor(result.nextCursor);
    setStreamHasMore(result.hasMore);
    setStreamTotal(result.totalCount);
    setStreamLoading(false);
  }, []);

  const loadMore = useCallback(async () => {
    if (streamLoadingRef.current || !streamHasMoreRef.current) {
      return;
    }
    const requestId = requestSequence.current;
    setStreamLoading(true);
    const result = await fetchTracesCursor(filtersRef.current, { sort: sortRef.current, cursor: streamCursorRef.current, limit: STREAM_PAGE });
    if (requestId !== requestSequence.current) {
      return;
    }
    setStreamRows((previous) => previous.concat(result.items));
    setStreamCursor(result.nextCursor);
    setStreamHasMore(result.hasMore);
    setStreamTotal(result.totalCount);
    setStreamLoading(false);
  }, []);

  // When the query inputs change, collapse open rows, return to the first page,
  // and re-pull the stream from the top. The state resets here are intentional.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setExpanded(new Set());
    setPage(0);
    if (view !== 'stream') {
      return undefined;
    }
    // The AbortController lets cleanup cancel a superseded request instead of just
    // discarding its result — otherwise React StrictMode's mount→cleanup→mount on
    // initial load fires this fetch twice for real.
    const controller = new AbortController();
    void resetStream(controller.signal);
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filtersKey, view, sort]);

  // A new time window invalidates any histogram-bar zoom from the previous window.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setZoom(null);
  }, [startTimestamp, endTimestamp]);

  // live tail — prepend newest (sample re-pulls newest page; live polls after-cursor)
  useEffect(() => {
    if (!tail) {
      return undefined;
    }
    const interval = window.setInterval(async () => {
      const loadedRows = streamRowsRef.current;
      const newest = loadedRows[0] ? { ts: loadedRows[0].startTimestamp, id: loadedRows[0].traceId } : null;
      const result = await fetchTracesCursor(filtersRef.current, { sort: sortRef.current, after: newest, limit: TAIL_PAGE });
      const loadedTraceIds = new Set(streamRowsRef.current.map((existing) => existing.traceId));
      const freshRows = result.items.filter((candidate) => !loadedTraceIds.has(candidate.traceId));
      if (freshRows.length) {
        setStreamRows((previous) => [...freshRows, ...previous]);
        setStreamTotal((previous) => previous + freshRows.length);
        void refetchHistogramRef.current();
      }
    }, TAIL_INTERVAL_MS);
    return () => window.clearInterval(interval);
    // Everything the tick reads comes from a ref, so `tail` is genuinely the only dep. Listing
    // `streamRows`/`histogramQuery` here would clear and rebuild the interval on every render
    // and the 1.5s tick would never fire while the user is typing or toggling facets.
  }, [tail]);

  // The tail effect above only prepends new rows; it never re-fetches a row already in
  // streamRows, so an in-progress trace's dot would otherwise never clear once rendered.
  // Patch already-loaded in-progress rows in place, unconditional on `tail`/autoRefresh —
  // same reasoning as Sessions' running-row poll being unconditional on that toggle.
  useEffect(() => {
    if (view !== 'stream') {
      return undefined;
    }
    const interval = window.setInterval(async () => {
      const runningTraceIds = streamRowsRef.current.filter((row) => row.inProgress).map((row) => row.traceId);
      if (runningTraceIds.length === 0) {
        return;
      }
      const updates = await Promise.all(runningTraceIds.map((traceId) => fetchTraceSummaryOrNull(traceId)));
      const updatesByTraceId = new Map(
        updates.filter((row): row is TraceRow => row !== null).map((row) => [row.traceId, row]),
      );
      if (updatesByTraceId.size === 0) {
        return;
      }
      setStreamRows((previous) => previous.map((row) => updatesByTraceId.get(row.traceId) ?? row));
    }, RUNNING_TRACE_POLL_INTERVAL_MS);
    return () => window.clearInterval(interval);
    // Depends only on `[view]` — like the tail effect above, everything it reads comes from
    // streamRowsRef, so it isn't torn down/recreated on every render.
  }, [view]);

  const toggleFacet = (key: FacetKey, value: string) => {
    setFacetSelections((previous) => {
      const next = { ...previous, [key]: new Set(previous[key]) } as TraceFacetSelections;
      if (next[key].has(value)) {
        next[key].delete(value);
      } else {
        next[key].add(value);
      }
      return next;
    });
  };
  const clearFacet = (key: FacetKey) => setFacetSelections((previous) => ({ ...previous, [key]: new Set() }));

  const toggleHistogramSeries = (series: HistogramSeries) =>
    setHiddenHistogramSeries((previous) => {
      const next = new Set(previous);
      if (next.has(series)) {
        next.delete(series);
      } else {
        next.add(series);
      }
      return next;
    });

  const toggleExpand = (traceId: string) =>
    setExpanded((previous) => {
      const next = new Set(previous);
      if (next.has(traceId)) {
        next.delete(traceId);
      } else {
        next.add(traceId);
      }
      return next;
    });

  const serviceForValue = (key: FacetKey, value: string): string | null => {
    if (key === 'service') {
      return value;
    }
    if (key === 'operation') {
      return serviceOf(value);
    }
    return null;
  };

  const clearZoom = () => setZoom(null);
  const clearAllFilters = () => {
    setSearch('');
    setFacetSelections(emptySelections());
    setZoom(null);
  };
  const zoomToBucket = (bucket: TraceHistogramBucket) => {
    setZoom({ startTimestamp: bucket.t0, endTimestamp: bucket.t1, label: formatZoomLabel(bucket.t0, bucket.t1) });
    if (autoRefresh) {
      onAutoRefreshChange(false);
    }
  };
  const toggleTail = () => onAutoRefreshChange(!autoRefresh);
  const changeTablePageSize = (nextPageSize: number) => {
    setPageSize(nextPageSize);
    setPage(0);
  };

  const totalCount = view === 'stream' ? streamTotal : tableQuery.data?.totalCount ?? 0;
  const tailLocked = view === 'table' || zoom != null || sort !== 'new';
  const tailTip = view === 'table'
    ? 'Live tail is available in the Stream view only'
    : zoom != null
      ? 'Live tail is unavailable while zoomed into a time range'
      : sort !== 'new'
        ? 'Live tail is available under the Newest first sort only'
        : undefined;

  return {
    // filter state
    search,
    onSearchChange: setSearch,
    facetSelections,
    toggleFacet,
    clearFacet,
    serviceForValue,
    zoom,
    clearZoom,
    clearAllFilters,
    zoomToBucket,

    // view / sort / histogram / expansion
    view,
    onViewChange: setView,
    sort,
    onSortChange: setSort,
    hiddenHistogramSeries,
    toggleHistogramSeries,
    expanded,
    toggleExpand,

    // query results
    histogramData: histogramQuery.data,
    facetsData: facetsQuery.data,

    // stream
    streamRows,
    streamLoading,
    streamHasMore,
    streamTotal,
    loadMore,

    // table
    tableRows: tableQuery.data?.items ?? [],
    tableTotal: tableQuery.data?.totalCount ?? 0,
    tableLoading: tableQuery.isLoading,
    page,
    pageSize,
    onTablePageChange: setPage,
    onTablePageSizeChange: changeTablePageSize,

    // derived flags
    totalCount,
    tail,
    tailLocked,
    tailTip,
    toggleTail,
  };
};

export type TracesExplorer = ReturnType<typeof useTracesExplorer>;

export default useTracesExplorer;
