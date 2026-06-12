import { useEffect, useMemo, useRef, useState } from 'react';
import { useInfiniteQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query';
import type { LogRow } from '../../../../api';
import { fetchLogsCursor, type LogCursor, type LogCursorPage, type LogsFilters } from '../../logsApi';
import LogStreamView from './LogStreamView';

const TAIL_INTERVAL_MS = 1500;
const STREAM_PAGE = 60;
const TAIL_PAGE = 20;

export interface LogStreamProps {
  /** Called at fetch time to produce fresh timestamps — never captured at render time. */
  resolveFilters: () => LogsFilters;
  /** Stable serialization of the current filter+window state — the query key + reset-on-change trigger. */
  filtersKey: string;
  /** When true, poll for newer rows and prepend them (live tail). */
  autoRefresh: boolean;
  /** Report the matching-row total up to the shared events counter. */
  onTotalChange: (total: number) => void;
  /** Fired when live tail appends rows, so the histogram can refetch. */
  onTailRows: () => void;
}

/**
 * Stream container — scroll-back paging via useInfiniteQuery; live tail via a manual poll
 * that prepends newer rows into the query cache. Renders the presentational LogStreamView.
 *
 * With stable query keys (see LogsPageView) the query key only changes on real filter or
 * window-type changes, not on every auto-refresh tick. As a result scroll-back pages,
 * expanded rows, and flash state persist across the whole tail session; they reset on
 * actual filter changes via the prevFiltersKey render guard below.
 */
const LogStream = ({ resolveFilters, filtersKey, autoRefresh, onTotalChange, onTailRows }: LogStreamProps) => {
  const queryClient = useQueryClient();

  const streamQuery = useInfiniteQuery<
    LogCursorPage,
    Error,
    InfiniteData<LogCursorPage>,
    readonly unknown[],
    LogCursor | null
  >({
    queryKey: ['log-stream', filtersKey],
    queryFn: ({ pageParam }) => fetchLogsCursor(resolveFilters(), { cursor: pageParam, limit: STREAM_PAGE }),
    initialPageParam: null,
    getNextPageParam: (lastPage) => (lastPage.hasMore ? lastPage.nextCursor : undefined),
  });

  const pages = streamQuery.data?.pages;
  const rows = useMemo<LogRow[]>(() => (pages ? pages.flatMap((page) => page.items) : []), [pages]);
  // pages[0] carries the freshest totalCount — the tail updater bumps it on prepend.
  const total = pages?.[0]?.totalCount ?? 0;
  const loading = streamQuery.isLoading || streamQuery.isFetchingNextPage;
  const hasMore = streamQuery.hasNextPage;
  const error = streamQuery.isError
    ? (streamQuery.error instanceof Error ? streamQuery.error.message : 'Failed to load logs')
    : null;

  const handleLoadMore = () => {
    if (streamQuery.hasNextPage && !streamQuery.isFetchingNextPage) {
      void streamQuery.fetchNextPage();
    }
  };

  // Collapse rows and clear flash state when the filter set changes — done in render (not
  // an effect) so it lands before this render's rows show. The query itself auto-resets
  // pages/total/error via its key change.
  const [expanded, setExpanded] = useState<Set<number>>(() => new Set());
  const [freshRowIds, setFreshRowIds] = useState<Set<number>>(() => new Set());
  const [prevFiltersKey, setPrevFiltersKey] = useState(filtersKey);
  if (prevFiltersKey !== filtersKey) {
    setPrevFiltersKey(filtersKey);
    setExpanded(new Set());
    setFreshRowIds(new Set());
  }

  const toggleExpand = (id: number) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });

  // surface the running total to the shared toolbar counter
  useEffect(() => {
    onTotalChange(total);
  }, [total, onTotalChange]);

  // Latest values the tail interval reads, synced each render so the interval can
  // subscribe only on `autoRefresh` and not re-create when other deps change.
  // setFreshRowIds is a stable setter from useState — safe to capture directly.
  //
  // IMPORTANT: resolveFilters is captured here so the tail tick calls it fresh each
  // tick. This is what keeps the window alive indefinitely during tail: each 1.5 s tick
  // resolves a new endTimestamp (1 minute ahead of now) so the preset span always
  // covers the very latest rows, and the span never goes stale or trips the backend's
  // 30-day ValidDateRange cap.
  const tailRef = useRef({ resolveFilters, filtersKey, onTailRows, queryClient, setFreshRowIds });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    tailRef.current = { resolveFilters, filtersKey, onTailRows, queryClient, setFreshRowIds };
  });

  // live tail — poll newest-after-cursor and prepend into the cache (mounted only in Stream view)
  useEffect(() => {
    if (!autoRefresh) {
      return undefined;
    }
    const interval = window.setInterval(async () => {
      const {
        resolveFilters: resolveLiveFilters,
        filtersKey: liveKey,
        onTailRows: tailRows,
        queryClient: client,
        setFreshRowIds: markFresh,
      } = tailRef.current;
      const key = ['log-stream', liveKey] as const;
      const first = client.getQueryData<InfiniteData<LogCursorPage>>(key)?.pages?.[0];
      if (!first) {
        return; // initial load hasn't landed yet — nothing to tail from
      }
      const newest = first.items[0];
      const after: LogCursor | null = newest ? { ts: newest.timestamp, id: newest.id } : null;
      // Resolve a fresh window at tick time so the tail's endTimestamp keeps advancing
      // and the preset span never goes stale during a long session.
      const liveFilters = resolveLiveFilters();
      try {
        const res = await fetchLogsCursor(liveFilters, { after, limit: TAIL_PAGE });
        if (res.items.length) {
          client.setQueryData<InfiniteData<LogCursorPage>>(key, (data) => {
            if (!data || data.pages.length === 0) {
              return data;
            }
            const [firstPage, ...rest] = data.pages;
            const merged: LogCursorPage = {
              ...firstPage,
              items: [...res.items, ...firstPage.items],
              totalCount: firstPage.totalCount + res.items.length,
            };
            return { ...data, pages: [merged, ...rest] };
          });
          // Mark the newly prepended row IDs so the view can flash them.
          markFresh((previousIds) => {
            const nextIds = new Set(previousIds);
            for (const item of res.items) {
              nextIds.add(item.id);
            }
            return nextIds;
          });
          tailRows();
        }
      } catch {
        // Transient tail poll failure — drop this tick; the next interval retries.
      }
    }, TAIL_INTERVAL_MS);
    return () => window.clearInterval(interval);
  }, [autoRefresh]);

  return (
    <LogStreamView
      rows={rows}
      total={total}
      loading={loading}
      hasMore={hasMore}
      error={error}
      expanded={expanded}
      freshRowIds={freshRowIds}
      onToggleExpand={toggleExpand}
      onLoadMore={handleLoadMore}
    />
  );
};

export default LogStream;
