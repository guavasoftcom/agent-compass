import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Paper, alpha } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import ZoomInRoundedIcon from '@mui/icons-material/ZoomInRounded';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import LiveTailToggle from '../../components/LiveTailToggle/LiveTailToggle';
import StreamTableToggle from '../../components/StreamTableToggle/StreamTableToggle';
import type { WindowOption } from '../../lib/constants';
import type { LogRow, WindowSelection } from '../../api';
import {
  fetchLogFacets,
  fetchLogHistogram,
  fetchLogsCursor,
  fetchLogsPage,
  type FacetKey,
  type HistogramBucket,
  type LogCursor,
  type LogsFilters,
  type Severity,
} from './logsApi';
import LogHistogramChart from './components/LogHistogramChart';
import LogFacetRail, { type FacetSelections } from './components/LogFacetRail';
import LogStream from './components/LogStream';
import LogTable from './components/LogTable';
import { fontFamilies } from '../../theme/typography';

export interface LogsPageViewProps {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows: readonly WindowOption[];
  startTimestamp: string;
  endTimestamp: string;
  windowLabel: string;
  error: Error | null;
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  isPolling: boolean;
}

const TAIL_INTERVAL_MS = 1500;
const STREAM_PAGE = 60;

const emptySelections = (): FacetSelections => ({
  severity: new Set(),
  event: new Set(),
  tool: new Set(),
});

interface ZoomRange {
  startTimestamp: string;
  endTimestamp: string;
  label: string;
}
// e.g. "Jun 9 · 9:15 PM–9:45 PM"
const zoomLabel = (startIso: string, endIso: string): string => {
  const s = new Date(startIso);
  const e = new Date(endIso);
  const t = (d: Date) =>
    d.toLocaleTimeString('en-US', {
      hour: 'numeric',
      minute: '2-digit',
      hour12: true,
    });
  return `${s.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} · ${t(s)}–${t(e)}`;
};

const LogsPageView = ({
  selection,
  onSelectionChange,
  windows,
  startTimestamp,
  endTimestamp,
  windowLabel,
  error,
  onReload,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
}: LogsPageViewProps) => {
  const [search, setSearch] = useState('');
  const [sel, setSel] = useState<FacetSelections>(emptySelections);
  const [hidden, setHidden] = useState<Set<Severity>>(new Set());
  const [view, setView] = useState<'stream' | 'table'>('stream');
  // local drill-down: click a histogram bar to zoom the Logs time window into that
  // bucket's range (kept local so it never touches the global window context).
  const [zoom, setZoom] = useState<ZoomRange | null>(null);
  // Live tail and Auto-refresh are one linked switch — toggling either drives both.
  const tail = autoRefresh;
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  // stream cursor-paging state
  const [loaded, setLoaded] = useState<LogRow[]>([]);
  const [cursor, setCursor] = useState<LogCursor | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const [streamLoading, setStreamLoading] = useState(false);
  const [streamTotal, setStreamTotal] = useState(0);

  // table offset state
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);

  const filters = useMemo<LogsFilters>(
    () => ({
      startTimestamp: zoom ? zoom.startTimestamp : startTimestamp,
      endTimestamp: zoom ? zoom.endTimestamp : endTimestamp,
      severity: [...sel.severity] as Severity[],
      event: [...sel.event],
      tool: [...sel.tool],
      q: search || undefined,
      hiddenSeverity: [...hidden],
    }),
    [zoom, startTimestamp, endTimestamp, sel, search, hidden],
  );
  const filtersKey = JSON.stringify(filters);

  // facets ignore the legend mute so severity totals stay stable
  const facetFilters = useMemo<LogsFilters>(
    () => ({ ...filters, hiddenSeverity: [] }),
    [filters],
  );

  const histogramQuery = useQuery({
    queryKey: ['log-histogram', filtersKey],
    queryFn: () => fetchLogHistogram(facetFilters, 50),
  });
  const facetsQuery = useQuery({
    queryKey: ['log-facets', filtersKey],
    queryFn: () => fetchLogFacets(facetFilters),
  });
  const tableQuery = useQuery({
    queryKey: ['log-table', filtersKey, page, pageSize],
    queryFn: () => fetchLogsPage(filters, page, pageSize),
    enabled: view === 'table',
  });

  // refs so async stream callbacks read fresh values without re-binding;
  // synced in an effect (not during render) to stay pure
  const filtersRef = useRef(filters);
  const cursorRef = useRef(cursor);
  const hasMoreRef = useRef(hasMore);
  const loadingRef = useRef(streamLoading);
  const reqId = useRef(0);
  const newestRef = useRef<LogCursor | null>(null);
  useEffect(() => {
    filtersRef.current = filters;
    cursorRef.current = cursor;
    hasMoreRef.current = hasMore;
    loadingRef.current = streamLoading;
    newestRef.current = loaded[0]
      ? { ts: loaded[0].timestamp, id: loaded[0].id }
      : null;
  });

  const resetStream = useCallback(async () => {
    const id = (reqId.current += 1);
    setStreamLoading(true);
    setLoaded([]);
    setCursor(null);
    setHasMore(true);
    const res = await fetchLogsCursor(filtersRef.current, {
      cursor: null,
      limit: STREAM_PAGE,
    });
    if (id !== reqId.current) {
      return;
    }
    setLoaded(res.items);
    setCursor(res.nextCursor);
    setHasMore(res.hasMore);
    setStreamTotal(res.totalCount);
    setStreamLoading(false);
  }, []);

  const loadMore = useCallback(async () => {
    if (loadingRef.current || !hasMoreRef.current) {
      return;
    }
    const id = reqId.current;
    setStreamLoading(true);
    const res = await fetchLogsCursor(filtersRef.current, {
      cursor: cursorRef.current,
      limit: STREAM_PAGE,
    });
    if (id !== reqId.current) {
      return;
    }
    setLoaded((prev) => prev.concat(res.items));
    setCursor(res.nextCursor);
    setHasMore(res.hasMore);
    setStreamTotal(res.totalCount);
    setStreamLoading(false);
  }, []);

  // reset stream + collapse rows whenever the filters or window change
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setExpanded(new Set());
    setPage(0);
    if (view === 'stream') {
      void resetStream();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filtersKey, view]);

  // clear the local zoom whenever the underlying window changes (preset/custom)
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setZoom(null);
  }, [startTimestamp, endTimestamp]);

  // live tail — poll newest-after-cursor and prepend (driven by the shared auto-refresh switch)
  useEffect(() => {
    if (!autoRefresh || view !== 'stream') {
      return undefined;
    }
    const iv = window.setInterval(async () => {
      const after = newestRef.current;
      const res = await fetchLogsCursor(filtersRef.current, {
        after,
        limit: 20,
      });
      if (res.items.length) {
        setLoaded((prev) => [...res.items, ...prev]);
        setStreamTotal((t) => t + res.items.length);
        void histogramQuery.refetch();
      }
    }, TAIL_INTERVAL_MS);
    return () => window.clearInterval(iv);
  }, [autoRefresh, view, histogramQuery]);

  const toggleFacet = (key: FacetKey, value: string) => {
    setSel((prev) => {
      const next = { ...prev, [key]: new Set(prev[key]) } as FacetSelections;
      if (next[key].has(value)) {
        next[key].delete(value);
      } else {
        next[key].add(value);
      }
      return next;
    });
  };
  const clearFacet = (key: FacetKey) =>
    setSel((prev) => ({ ...prev, [key]: new Set() }));
  const toggleSeverity = (s: Severity) =>
    setHidden((prev) => {
      const next = new Set(prev);
      if (next.has(s)) {
        next.delete(s);
      } else {
        next.add(s);
      }
      return next;
    });
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

  // active filter chips
  const chips: Array<{ key: FacetKey | 'q'; value: string; label: string }> =
    [];
  if (search) {
    chips.push({ key: 'q', value: search, label: `"${search}"` });
  }
  (['severity', 'event', 'tool'] as FacetKey[]).forEach((k) => {
    sel[k].forEach((v) => chips.push({ key: k, value: v, label: v }));
  });
  const removeChip = (key: FacetKey | 'q', value: string) => {
    if (key === 'q') {
      setSearch('');
    } else {
      toggleFacet(key, value);
    }
  };
  const clearAll = () => {
    setSearch('');
    setSel(emptySelections());
  };

  // Window selection changes (preset/custom) clear any local zoom.
  const handleSelectionChange = (next: WindowSelection) => {
    setZoom(null);
    onSelectionChange(next);
  };
  // Drill into a histogram bar's bucket; live tail can't run on a fixed past slice.
  const handleBarZoom = (b: HistogramBucket) => {
    setZoom({
      startTimestamp: b.t0,
      endTimestamp: b.t1,
      label: zoomLabel(b.t0, b.t1),
    });
    if (autoRefresh) {
      onAutoRefreshChange(false);
    }
  };

  const totalCount =
    view === 'stream' ? streamTotal : (tableQuery.data?.totalCount ?? 0);
  const tailLocked = zoom != null;

  return (
    <PageLayout
      eyebrow="Observability"
      title="Logs"
      subtitle={
        'Structured event logs streamed from every agent session. Spot error spikes on the ' +
        'timeline, narrow with facets, and expand any line to read the full body and attribute ' +
        'payload — no modal hop.'
      }
      error={error}
      actions={
        <PageActions
          selection={selection}
          onSelectionChange={handleSelectionChange}
          windows={windows}
          onReload={onReload}
          autoRefresh={autoRefresh}
          onAutoRefreshChange={onAutoRefreshChange}
          isPolling={isPolling}
          autoRefreshDisabled={zoom != null}
        />
      }
    >
      <Paper variant="outlined" sx={{ p: 2.25, mb: 2 }}>
        <LogHistogramChart
          histogram={histogramQuery.data}
          hiddenSeverities={hidden}
          facetSeverity={facetsQuery.data?.severity ?? []}
          windowLabel={zoom ? zoom.label : windowLabel}
          onToggleSeverity={toggleSeverity}
          onBarClick={handleBarZoom}
        />
      </Paper>

      {/* persistent toolbar: view toggle + live tail (left), count + chips (right) */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 1.75,
          mb: 1.25,
          flexWrap: 'wrap',
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25 }}>
          <StreamTableToggle value={view} onChange={setView} />
          <LiveTailToggle
            active={tail}
            locked={tailLocked}
            tooltip={
              tailLocked
                ? 'Live tail is unavailable while zoomed into a time range'
                : undefined
            }
            onToggle={() => onAutoRefreshChange(!autoRefresh)}
          />
        </Box>
        <Box
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 0.6,
            height: 40,
            px: 1.9,
            borderRadius: 1.5,
            border: 1,
            borderColor: 'divider',
            bgcolor: 'background.paper',
            boxShadow: 1,
            whiteSpace: 'nowrap',
            flexShrink: 0,
          }}
        >
          <Box
            component="b"
            sx={{
              color: 'text.primary',
              fontWeight: 700,
              fontFamily: fontFamilies.display,
              fontSize: 13,
            }}
          >
            {totalCount.toLocaleString()}
          </Box>
          <Box
            component="span"
            sx={{
              color: 'text.secondary',
              fontFamily: fontFamilies.display,
              fontWeight: 600,
              fontSize: 13,
            }}
          >
            events
          </Box>
        </Box>
      </Box>

      {/* active-filter band — its own row so the zoom chip + many filters wrap cleanly below the controls */}
      {zoom || chips.length ? (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            flexWrap: 'wrap',
            mb: 1.75,
          }}
        >
          {zoom ? (
            <Box
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.75,
                height: 30,
                pl: 1.1,
                pr: 0.75,
                borderRadius: 1.1,
                bgcolor: (t) => alpha(t.palette.primary.main, 0.12),
                boxShadow: (t) =>
                  `inset 0 0 0 1px ${alpha(t.palette.primary.main, 0.32)}`,
                color: 'primary.main',
                fontSize: 12,
                fontWeight: 600,
              }}
            >
              <ZoomInRoundedIcon sx={{ fontSize: 14 }} />
              {zoom.label}
              <Box
                component="span"
                onClick={() => setZoom(null)}
                sx={{
                  display: 'grid',
                  placeItems: 'center',
                  width: 17,
                  height: 17,
                  borderRadius: 0.7,
                  cursor: 'pointer',
                  '&:hover': {
                    bgcolor: (t) => alpha(t.palette.primary.main, 0.32),
                  },
                }}
              >
                <CloseIcon sx={{ fontSize: 12 }} />
              </Box>
            </Box>
          ) : null}
          {chips.map((c) => (
            <Box
              key={`${c.key}:${c.value}`}
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.75,
                height: 30,
                pl: 1.4,
                pr: 0.75,
                borderRadius: 1.1,
                bgcolor: (t) => alpha(t.palette.primary.main, 0.12),
                boxShadow: (t) =>
                  `inset 0 0 0 1px ${alpha(t.palette.primary.main, 0.32)}`,
                color: 'primary.main',
                fontSize: 12,
                fontWeight: 600,
              }}
            >
              {c.label}
              <Box
                component="span"
                onClick={() => removeChip(c.key, c.value)}
                sx={{
                  display: 'grid',
                  placeItems: 'center',
                  width: 17,
                  height: 17,
                  borderRadius: 0.7,
                  cursor: 'pointer',
                  '&:hover': {
                    bgcolor: (t) => alpha(t.palette.primary.main, 0.32),
                  },
                }}
              >
                <CloseIcon sx={{ fontSize: 12 }} />
              </Box>
            </Box>
          ))}
          <Box
            component="span"
            onClick={() => {
              clearAll();
              setZoom(null);
            }}
            sx={{
              fontSize: 12.5,
              fontWeight: 600,
              color: 'text.secondary',
              cursor: 'pointer',
              ml: 0.5,
              whiteSpace: 'nowrap',
              '&:hover': { color: 'primary.main' },
            }}
          >
            Clear all
          </Box>
        </Box>
      ) : null}

      {view === 'stream' ? (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '236px 1fr' },
            gap: 2,
            height: 'calc(100vh - 430px)',
            minHeight: 420,
          }}
        >
          <Paper
            variant="outlined"
            sx={{
              overflow: 'hidden',
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <LogFacetRail
              facets={facetsQuery.data}
              selections={sel}
              search={search}
              onSearchChange={setSearch}
              onToggle={toggleFacet}
              onClear={clearFacet}
            />
          </Paper>
          <Paper
            variant="outlined"
            sx={{
              overflow: 'hidden',
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <LogStream
              rows={loaded}
              total={streamTotal}
              loading={streamLoading}
              hasMore={hasMore}
              expanded={expanded}
              onToggleExpand={toggleExpand}
              onLoadMore={loadMore}
            />
          </Paper>
        </Box>
      ) : (
        <Paper
          variant="outlined"
          sx={{
            overflow: 'hidden',
            display: 'flex',
            flexDirection: 'column',
            height: 'calc(100vh - 420px)',
            minHeight: 420,
          }}
        >
          <LogTable
            rows={tableQuery.data?.items ?? []}
            total={tableQuery.data?.totalCount ?? 0}
            page={page}
            pageSize={pageSize}
            loading={tableQuery.isLoading}
            onPageChange={setPage}
            onPageSizeChange={(s) => {
              setPageSize(s);
              setPage(0);
            }}
          />
        </Paper>
      )}
    </PageLayout>
  );
};

export default LogsPageView;
