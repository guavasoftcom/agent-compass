import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { TraceRow } from '../../api';
import {
  fetchTraceFacets,
  fetchTraceHistogram,
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

const TAIL_INTERVAL_MS = 1500;
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
}

const useTracesExplorer = ({
  startTimestamp,
  endTimestamp,
  autoRefresh,
  onAutoRefreshChange,
}: UseTracesExplorerParams) => {
  const [search, setSearch] = useState('');
  const [facetSelections, setFacetSelections] = useState<TraceFacetSelections>(emptySelections);
  const [hiddenHistogramSeries, setHiddenHistogramSeries] = useState<Set<HistogramSeries>>(new Set());
  const [view, setView] = useState<TraceView>('stream');
  const [sort, setSort] = useState<TraceSortKey>('new');
  const [zoom, setZoom] = useState<ZoomRange | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  // Live tail and Auto-refresh are one linked switch (Stream-only).
  const tail = autoRefresh && view === 'stream' && zoom == null;

  // stream cursor-paging state
  const [streamRows, setStreamRows] = useState<TraceRow[]>([]);
  const [streamCursor, setStreamCursor] = useState<TraceCursor | null>(null);
  const [streamHasMore, setStreamHasMore] = useState(true);
  const [streamLoading, setStreamLoading] = useState(false);
  const [streamTotal, setStreamTotal] = useState(0);

  // table offset state
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);

  const filters = useMemo<TracesFilters>(
    () => ({
      startTimestamp: zoom ? zoom.startTimestamp : startTimestamp,
      endTimestamp: zoom ? zoom.endTimestamp : endTimestamp,
      status: [...facetSelections.status] as TraceStatus[],
      operation: [...facetSelections.operation],
      service: [...facetSelections.service],
      duration: [...facetSelections.duration],
      session: [...facetSelections.session],
      q: search || undefined,
    }),
    [zoom, startTimestamp, endTimestamp, facetSelections, search],
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
  });

  // Mirror the latest values into refs so the stable stream callbacks below
  // can read fresh state without being torn down and recreated on every change.
  const filtersRef = useRef(filters);
  const sortRef = useRef(sort);
  const streamCursorRef = useRef(streamCursor);
  const streamHasMoreRef = useRef(streamHasMore);
  const streamLoadingRef = useRef(streamLoading);
  const requestSequence = useRef(0);
  useEffect(() => {
    filtersRef.current = filters;
    sortRef.current = sort;
    streamCursorRef.current = streamCursor;
    streamHasMoreRef.current = streamHasMore;
    streamLoadingRef.current = streamLoading;
  });

  const resetStream = useCallback(async () => {
    const requestId = (requestSequence.current += 1);
    setStreamLoading(true);
    setStreamRows([]);
    setStreamCursor(null);
    setStreamHasMore(true);
    const result = await fetchTracesCursor(filtersRef.current, { sort: sortRef.current, cursor: null, limit: STREAM_PAGE });
    if (requestId !== requestSequence.current) {
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
    if (view === 'stream') {
      void resetStream();
    }
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
      const newest = streamRows[0] ? { ts: streamRows[0].startTimestamp, id: streamRows[0].traceId } : null;
      const result = await fetchTracesCursor(filtersRef.current, { sort: sortRef.current, after: newest, limit: TAIL_PAGE });
      const freshRows = result.items.filter((candidate) => !streamRows.some((existing) => existing.traceId === candidate.traceId));
      if (freshRows.length) {
        setStreamRows((previous) => [...freshRows, ...previous]);
        setStreamTotal((previous) => previous + freshRows.length);
        void histogramQuery.refetch();
      }
    }, TAIL_INTERVAL_MS);
    return () => window.clearInterval(interval);
  }, [tail, streamRows, histogramQuery]);

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
  const tailLocked = view === 'table' || zoom != null;
  const tailTip = view === 'table'
    ? 'Live tail is available in the Stream view only'
    : zoom != null
      ? 'Live tail is unavailable while zoomed into a time range'
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
