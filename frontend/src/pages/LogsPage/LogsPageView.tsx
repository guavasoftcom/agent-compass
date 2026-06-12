import { useMemo, useState } from 'react';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { Box, Paper, alpha } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import ZoomInRoundedIcon from '@mui/icons-material/ZoomInRounded';
import ViewStreamIcon from '@mui/icons-material/ViewStream';
import TableRowsIcon from '@mui/icons-material/TableRows';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import type { WindowOption } from '../../constants';
import type { WindowSelection } from '../../api';
import {
  fetchLogFacets,
  fetchLogHistogram,
  HISTOGRAM_DEFAULT_TARGET,
  SEVERITIES,
  type FacetKey,
  type HistogramBucket,
  type LogsFilters,
  type Severity,
} from './logsApi';
import { resolveWindow } from './resolveWindow';
import LogHistogramChart from './components/LogHistogramChart';
import LogFacetRail, { type FacetSelections } from './components/LogFacetRail';
import LogStream from './components/LogStream';
import LogTable from './components/LogTable';

export interface LogsPageViewProps {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows: readonly WindowOption[];
  windowLabel: string;
  error: Error | null;
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  isPolling: boolean;
}

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
  const t = (d: Date) => d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
  return `${s.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} · ${t(s)}–${t(e)}`;
};

// Height reserved above the Stream/Table body — header + histogram + toolbar + the <main>
// padding. The body wrapper fills the rest of the viewport at a fixed height; the
// active-filter band lives inside it (flex), so any number of chips shrinks the table, not
// the page — it never scrolls, however many filters wrap.
const BODY_CHROME_PX = 460;

// Auto-refresh poll interval — 60 s for preset windows, never for custom or zoomed ranges.
const AUTO_REFRESH_INTERVAL_MS = 60_000;

// The narrowest zoom window we allow. Clicking a bar whose span is smaller than this
// centers a MINIMUM_ZOOM_SPAN_MS window on the bucket midpoint instead of zooming to
// the bare bucket range. Zoom is unavailable when the current window is already at or
// below this span (clicking would produce no useful narrowing).
const MINIMUM_ZOOM_SPAN_MS = 30 * 60_000; // 1 800 000 ms

/**
 * Build a stable window key from a WindowSelection.
 *
 * This key is baked into every TanStack Query key so cache entries split cleanly per
 * selection without encoding ephemeral resolved timestamps. A preset "last 24 hours"
 * always maps to the same key string regardless of when it was last resolved; the fresh
 * timestamps are produced at fetch time by `resolveFilters()` in the queryFn closure.
 */
const buildWindowKey = (selection: WindowSelection, zoom: ZoomRange | null): string => {
  if (zoom != null) {
    return `zoom:${zoom.startTimestamp}:${zoom.endTimestamp}`;
  }
  if (selection.kind === 'custom') {
    return `custom:${selection.startTimestamp}:${selection.endTimestamp}`;
  }
  return `preset:${selection.minutes}`;
};

const LogsPageView = ({
  selection,
  onSelectionChange,
  windows,
  windowLabel,
  error,
  onReload,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
}: LogsPageViewProps) => {
  const [search, setSearch] = useState('');
  const [sel, setSel] = useState<FacetSelections>(emptySelections);
  const [view, setView] = useState<'stream' | 'table'>('stream');
  // local drill-down: click a histogram bar to zoom the Logs time window into that
  // bucket's range (kept local so it never touches the global window context).
  const [zoom, setZoom] = useState<ZoomRange | null>(null);
  // Live tail and Auto-refresh are one linked switch — toggling either drives both.
  // Both are Stream-only: the Table is offset-paged, so it never tails, and in Table
  // view the top-right auto-refresh toggle is disabled too (its state is preserved
  // and resumes when switching back to Stream).
  const tail = autoRefresh && view === 'stream';
  // Matching-row total for the toolbar counter — reported up by whichever of the
  // Stream / Table containers is currently mounted.
  const [matchingTotal, setMatchingTotal] = useState(0);

  // Stable window key — does NOT change on every auto-refresh tick. Preset windows
  // always produce the same key; resolved timestamps are deferred to fetch time.
  const windowKey = buildWindowKey(selection, zoom);

  // The non-window filter parts (severity/event/tool/search) as a stable string.
  // Changing any of these produces a new filterPartsKey which updates the query key
  // and triggers a fresh fetch; the window timestamp never causes spurious cache misses.
  const filterPartsKey = useMemo(
    () => JSON.stringify({ severity: [...sel.severity], event: [...sel.event], tool: [...sel.tool], query: search || undefined }),
    [sel, search],
  );

  // Full query key segment for histogram/facets/stream/table queries.
  const filtersKey = `${filterPartsKey}|${windowKey}`;

  /**
   * Resolve the current window + facet selections into a concrete LogsFilters object.
   * Called at fetch time (inside queryFn / tail tick) — NOT at render time — so preset
   * windows always resolve to a fresh end timestamp without changing the query key.
   */
  const resolveFilters = (): LogsFilters => {
    const { startTimestamp, endTimestamp } = zoom
      ? { startTimestamp: zoom.startTimestamp, endTimestamp: zoom.endTimestamp }
      : resolveWindow(selection);
    return {
      startTimestamp,
      endTimestamp,
      severity: [...sel.severity] as Severity[],
      event: [...sel.event],
      tool: [...sel.tool],
      query: search || undefined,
    };
  };

  // When a severity selection is active, dim the unselected series in the histogram
  // client-side. The histogram never sends severity to the server — all four series
  // are always present. An empty selection means all series are visible (empty hidden set).
  const hiddenSeverities = useMemo<Set<Severity>>(() => {
    if (sel.severity.size === 0) {
      return new Set<Severity>();
    }
    return new Set(SEVERITIES.filter((s) => !sel.severity.has(s)));
  }, [sel.severity]);

  // Poll only in Stream view, for preset windows without an active zoom — custom ranges
  // have a fixed end so re-fetching would return identical data; zoom is always a past
  // fixed slice; Table view has auto-refresh disabled entirely.
  const shouldPoll = autoRefresh && view === 'stream' && selection.kind === 'preset' && zoom == null;
  const histogramRefetchInterval = shouldPoll ? AUTO_REFRESH_INTERVAL_MS : false;

  const histogramQuery = useQuery({
    queryKey: ['log-histogram', filterPartsKey, windowKey],
    queryFn: () => fetchLogHistogram(resolveFilters(), HISTOGRAM_DEFAULT_TARGET),
    refetchInterval: histogramRefetchInterval,
    placeholderData: keepPreviousData,
  });
  const facetsQuery = useQuery({
    queryKey: ['log-facets', filterPartsKey, windowKey],
    queryFn: () => fetchLogFacets(resolveFilters()),
    refetchInterval: histogramRefetchInterval,
    placeholderData: keepPreviousData,
  });

  // Clear the local zoom whenever the underlying selection changes (preset/custom switch or
  // custom range update). Done in render rather than an effect to avoid a cascading
  // re-render — same idiom as LogTable's page reset. handleSelectionChange covers
  // user-driven changes; this covers any other path.
  //
  // We key on the selection-derived part of the window key only (ignoring zoom itself),
  // so the guard fires on real window changes, not on zoom set/clear cycles.
  const selectionKey = selection.kind === 'custom'
    ? `custom:${selection.startTimestamp}:${selection.endTimestamp}`
    : `preset:${selection.minutes}`;
  const [prevSelectionKey, setPrevSelectionKey] = useState(selectionKey);
  if (prevSelectionKey !== selectionKey) {
    setPrevSelectionKey(selectionKey);
    setZoom(null);
  }

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
  const clearFacet = (key: FacetKey) => setSel((prev) => ({ ...prev, [key]: new Set() }));

  // active filter chips
  const chips: Array<{ key: FacetKey | 'query'; value: string; label: string }> = [];
  if (search) {
    chips.push({ key: 'query', value: search, label: `"${search}"` });
  }
  (['severity', 'event', 'tool'] as FacetKey[]).forEach((k) => {
    sel[k].forEach((v) => chips.push({ key: k, value: v, label: v }));
  });
  const removeChip = (key: FacetKey | 'query', value: string) => {
    if (key === 'query') {
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
  // If the bucket span is already >= the floor, zoom to the exact bucket range.
  // Otherwise build a MINIMUM_ZOOM_SPAN_MS window centered on the bucket midpoint,
  // then slide it to fit inside the containing window without shrinking — the
  // containing window is always wider than the floor (enforced by the onBarClick gate),
  // so the slide always has room.
  const handleBarZoom = (b: HistogramBucket) => {
    const bucketStartMs = Date.parse(b.t0);
    const bucketEndMs = Date.parse(b.t1);
    const bucketSpanMs = bucketEndMs - bucketStartMs;

    let zoomStartMs: number;
    let zoomEndMs: number;

    if (bucketSpanMs >= MINIMUM_ZOOM_SPAN_MS) {
      zoomStartMs = bucketStartMs;
      zoomEndMs = bucketEndMs;
    } else {
      // Determine the containing window: active zoom if already zoomed, else the
      // current selection resolved against the clock at click time.
      const containingWindow = zoom != null
        ? { startMs: Date.parse(zoom.startTimestamp), endMs: Date.parse(zoom.endTimestamp) }
        : (() => {
            const resolved = resolveWindow(selection);
            return { startMs: Date.parse(resolved.startTimestamp), endMs: Date.parse(resolved.endTimestamp) };
          })();

      const midpointMs = bucketStartMs + bucketSpanMs / 2;
      const halfFloor = MINIMUM_ZOOM_SPAN_MS / 2;

      // Center the floor-sized window on the midpoint, then slide to stay inside the
      // containing window — never shrink below MINIMUM_ZOOM_SPAN_MS.
      zoomStartMs = midpointMs - halfFloor;
      zoomEndMs = midpointMs + halfFloor;

      if (zoomEndMs > containingWindow.endMs) {
        const overshoot = zoomEndMs - containingWindow.endMs;
        zoomStartMs -= overshoot;
        zoomEndMs = containingWindow.endMs;
      }
      if (zoomStartMs < containingWindow.startMs) {
        zoomStartMs = containingWindow.startMs;
      }
    }

    const startIso = new Date(zoomStartMs).toISOString();
    const endIso = new Date(zoomEndMs).toISOString();
    setZoom({ startTimestamp: startIso, endTimestamp: endIso, label: zoomLabel(startIso, endIso) });
    if (autoRefresh) {
      onAutoRefreshChange(false);
    }
  };

  const tailDisabledReason = zoom != null
    ? 'Live tail is unavailable while zoomed into a time range'
    : view === 'table'
      ? 'Live tail is available in the Stream view only'
      : null;
  const tailLocked = tailDisabledReason != null;
  const bodyHeight = `calc(100vh - ${BODY_CHROME_PX}px)`;

  return (
    <PageLayout
      eyebrow="Observability"
      title="Logs"
      subtitle={
        'Structured event logs streamed from every agent session. Spot error spikes on the '
        + 'timeline, narrow with facets, and expand any line to read the full body and attribute '
        + 'payload — no modal hop.'
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
          autoRefreshDisabled={zoom != null || view === 'table'}
        />
      }
    >
      <Paper variant="outlined" sx={{ p: 2.25, mb: 2 }}>
        <LogHistogramChart
          data={histogramQuery.data}
          hidden={hiddenSeverities}
          facetSeverity={facetsQuery.data?.severity ?? []}
          windowLabel={zoom ? zoom.label : windowLabel}
          onToggleSeverity={(s) => toggleFacet('severity', s)}
          onBarClick={
            (() => {
              // Gate on the CURRENT WINDOW SPAN, not bucket width. Zoom is
              // pointless once the window is already at or below the floor.
              const currentSpanMs = zoom != null
                ? Date.parse(zoom.endTimestamp) - Date.parse(zoom.startTimestamp)
                : selection.kind === 'custom'
                  ? Date.parse(selection.endTimestamp) - Date.parse(selection.startTimestamp)
                  : selection.minutes * 60_000;
              return currentSpanMs <= MINIMUM_ZOOM_SPAN_MS ? undefined : handleBarZoom;
            })()
          }
        />
      </Paper>

      {/* persistent toolbar: view toggle + live tail (left), count + chips (right) */}
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1.75, mb: 1.25, flexWrap: 'wrap' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25 }}>
          <Box sx={{ display: 'inline-flex', bgcolor: 'action.hover', borderRadius: 1.5, p: '3px', gap: '2px', height: 40, alignItems: 'center' }}>
            {(['stream', 'table'] as const).map((v) => {
              const on = view === v;
              return (
                <Box
                  key={v}
                  component="button"
                  onClick={() => setView(v)}
                  sx={{
                    border: 'none',
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 0.9,
                    height: 34,
                    px: 1.6,
                    borderRadius: 1,
                    fontFamily: "'Sora', sans-serif",
                    fontSize: 13,
                    fontWeight: 600,
                    color: on ? 'primary.main' : 'text.secondary',
                    bgcolor: on ? 'background.paper' : 'transparent',
                    boxShadow: on ? 1 : 'none',
                    '&:hover': { color: on ? 'primary.main' : 'text.primary' },
                    '& svg': { fontSize: 16 },
                  }}
                >
                  {v === 'stream' ? <ViewStreamIcon /> : <TableRowsIcon />}
                  {v === 'stream' ? 'Stream' : 'Table'}
                </Box>
              );
            })}
          </Box>
          <Box
            component="button"
            title={tailDisabledReason ?? undefined}
            onClick={() => {
              if (!tailLocked) {
                onAutoRefreshChange(!autoRefresh);
              }
            }}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              height: 40,
              px: 1.75,
              borderRadius: 1.5,
              cursor: tailLocked ? 'not-allowed' : 'pointer',
              opacity: tailLocked ? 0.4 : 1,
              fontFamily: "'Sora', sans-serif",
              fontSize: 13,
              fontWeight: 600,
              border: tail ? 'none' : 1,
              borderColor: 'divider',
              color: tail ? 'common.white' : 'text.secondary',
              background: tail ? 'linear-gradient(135deg, #1f9d6b, #13b6e6)' : (t) => t.palette.background.paper,
              boxShadow: tail ? '0 6px 16px rgba(31,157,107,0.4)' : 'none',
              '&:hover': { color: tail ? 'common.white' : 'text.primary' },
            }}
          >
            <Box
              sx={{
                width: 9,
                height: 9,
                borderRadius: '50%',
                bgcolor: tail ? 'common.white' : 'text.disabled',
                animation: tail ? 'tailPulse 1.1s infinite' : 'none',
                '@keyframes tailPulse': { '0%,100%': { opacity: 1, transform: 'scale(1)' }, '50%': { opacity: 0.35, transform: 'scale(0.8)' } },
              }}
            />
            {tail ? 'LIVE' : 'Live tail'}
          </Box>
        </Box>
        <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.6, height: 40, px: 1.9, borderRadius: 1.5, border: 1, borderColor: 'divider', bgcolor: 'background.paper', boxShadow: 1, whiteSpace: 'nowrap', flexShrink: 0 }}>
          <Box component="b" sx={{ color: 'text.primary', fontWeight: 700, fontFamily: "'Sora', sans-serif", fontSize: 13 }}>
            {matchingTotal.toLocaleString()}
          </Box>
          <Box component="span" sx={{ color: 'text.secondary', fontFamily: "'Sora', sans-serif", fontWeight: 600, fontSize: 13 }}>
            events
          </Box>
        </Box>
      </Box>

      {/* Fixed-height body: the active-filter band and the Stream/Table share this box, so a
          varying number of filter chips shrinks the table — not the page — and it won't scroll. */}
      <Box sx={{ height: bodyHeight, minHeight: 420, display: 'flex', flexDirection: 'column' }}>
      {/* active-filter band — its own row so the zoom chip + many filters wrap cleanly below the controls */}
      {zoom || chips.length ? (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 1.75 }}>
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
                boxShadow: (t) => `inset 0 0 0 1px ${alpha(t.palette.primary.main, 0.32)}`,
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
                sx={{ display: 'grid', placeItems: 'center', width: 17, height: 17, borderRadius: 0.7, cursor: 'pointer', '&:hover': { bgcolor: (t) => alpha(t.palette.primary.main, 0.32) } }}
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
                boxShadow: (t) => `inset 0 0 0 1px ${alpha(t.palette.primary.main, 0.32)}`,
                color: 'primary.main',
                fontSize: 12,
                fontWeight: 600,
              }}
            >
              {c.label}
              <Box
                component="span"
                onClick={() => removeChip(c.key, c.value)}
                sx={{ display: 'grid', placeItems: 'center', width: 17, height: 17, borderRadius: 0.7, cursor: 'pointer', '&:hover': { bgcolor: (t) => alpha(t.palette.primary.main, 0.32) } }}
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
            sx={{ fontSize: 12.5, fontWeight: 600, color: 'text.secondary', cursor: 'pointer', ml: 0.5, whiteSpace: 'nowrap', '&:hover': { color: 'primary.main' } }}
          >
            Clear all
          </Box>
        </Box>
      ) : null}

      {view === 'stream' ? (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '236px 1fr' }, gap: 2, flex: 1, minHeight: 0 }}>
          <Paper variant="outlined" sx={{ overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
            <LogFacetRail
              facets={facetsQuery.data}
              selections={sel}
              search={search}
              onSearchChange={setSearch}
              onToggle={toggleFacet}
              onClear={clearFacet}
            />
          </Paper>
          <Paper variant="outlined" sx={{ overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
            <LogStream
              resolveFilters={resolveFilters}
              filtersKey={filtersKey}
              autoRefresh={autoRefresh}
              onTotalChange={setMatchingTotal}
              onTailRows={() => {
                void histogramQuery.refetch();
              }}
            />
          </Paper>
        </Box>
      ) : (
        <Paper variant="outlined" sx={{ overflow: 'hidden', display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
          <LogTable
            resolveFilters={resolveFilters}
            filtersKey={filtersKey}
            onTotalChange={setMatchingTotal}
          />
        </Paper>
      )}
      </Box>
    </PageLayout>
  );
};

export default LogsPageView;
