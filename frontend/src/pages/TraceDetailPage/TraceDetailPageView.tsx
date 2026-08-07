import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, CircularProgress, Typography } from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import type { LogRow, SpanRow } from '../../api';
import { formatDuration } from '../TracesPage/tracesApi';
import { tokenBreakdownForSpan } from '../TracesPage/tokenBreakdown';
import { type SpanTree, type TraceWindow } from './spanTree';
import SpanDetailDock from './components/SpanDetailDock';
import TraceDetailHeader from './components/TraceDetailHeader';
import TraceMinimap, { type ZoomView } from './components/TraceMinimap';
import WaterfallToolbar from './components/WaterfallToolbar';
import SpanWaterfallRow from './components/SpanWaterfallRow';
import { radii } from '../../theme/theme';

export interface TraceDetailPageViewProps {
  traceId: string;
  spans: SpanRow[] | undefined;
  isLoading: boolean;
  error: Error | null;
  tree: SpanTree;
  spanIndices: Map<string, number>;
  depthBySpanId: Map<string, number>;
  traceWindow: TraceWindow | null;
  parentSpanIds: string[];
  descendantErrorCounts: Map<string, number>;
  selfTimeNanosBySpanId: Map<string, number>;
  logsBySpanId: Map<string, LogRow[]>;
  sessionId: string | null;
  // TraceRow.firstUserPrompt for this trace. Null hides the header's Prompt row
  // entirely (traces rooted in a tool / model / mcp / compaction span have no
  // prompt of their own, and prompt-body capture can be off).
  firstUserPrompt: string | null;
  // TraceRow.totalCostUsd from the `['trace-summary', traceId]` query — the
  // backend-authoritative trace cost. Null while that query hasn't resolved
  // yet, or resolved with no cost; the header's Cost KPI treats both the same.
  traceCostUsd: number | null;
}

const TraceDetailPageView = ({
  traceId,
  spans,
  isLoading,
  error,
  tree,
  spanIndices,
  depthBySpanId,
  traceWindow,
  parentSpanIds,
  descendantErrorCounts,
  selfTimeNanosBySpanId,
  logsBySpanId,
  sessionId,
  firstUserPrompt,
  traceCostUsd,
}: TraceDetailPageViewProps) => {
  const waterfallRef = useRef<HTMLDivElement>(null);

  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);

  const earliest = traceWindow?.earliestStartMs ?? 0;
  const totalMs = traceWindow?.totalMs ?? 1;
  const [view, setView] = useState<ZoomView>({ s: 0, e: totalMs });
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setView({ s: 0, e: totalMs });
  }, [totalMs]);

  const offMsOf = useCallback(
    (s: SpanRow) => Date.parse(s.startTimestamp) - earliest,
    [earliest],
  );
  const durMsOf = (s: SpanRow) => s.durationNanos / 1e6;

  const errorSpans = useMemo(
    () => (spans ?? []).filter((s) => s.statusCode === 'error'),
    [spans],
  );
  const errorIndexRef = useRef(-1);

  // visible spans (respect collapse) then drop those entirely outside the zoom view
  const visible = useMemo(() => {
    const out: SpanRow[] = [];
    const walk = (list: SpanRow[]) => {
      list.forEach((s) => {
        out.push(s);
        if (!collapsed.has(s.spanId)) {
          walk(tree.childrenByParentId.get(s.spanId) ?? []);
        }
      });
    };
    walk(tree.roots);
    return out.filter((s) => {
      const off = offMsOf(s);
      return off < view.e && off + durMsOf(s) > view.s;
    });
  }, [tree, collapsed, view, offMsOf]);

  const visibleSpanMs = Math.max(1, view.e - view.s);
  const percentOf = (timeMs: number) =>
    ((timeMs - view.s) / visibleSpanMs) * 100;

  const scrollToSpan = useCallback((spanId: string) => {
    const el = waterfallRef.current?.querySelector(
      `[data-span="${spanId}"]`,
    ) as HTMLElement | null;
    if (el && waterfallRef.current) {
      waterfallRef.current.scrollTop = Math.max(0, el.offsetTop - 70);
    }
  }, []);

  // error-first: select first error span on load
  const initRef = useRef(false);
  useEffect(() => {
    if (initRef.current || !spans || spans.length === 0) {
      return;
    }
    initRef.current = true;
    if (errorSpans.length) {
      errorIndexRef.current = 0;
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSelected(errorSpans[0].spanId);
      setTimeout(() => scrollToSpan(errorSpans[0].spanId), 40);
    }
  }, [spans, errorSpans, scrollToSpan]);

  const nextError = () => {
    if (!errorSpans.length) {
      return;
    }
    errorIndexRef.current = (errorIndexRef.current + 1) % errorSpans.length;
    const id = errorSpans[errorIndexRef.current].spanId;
    setSelected(id);
    scrollToSpan(id);
  };

  const toggleCollapse = (spanId: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(spanId)) {
        next.delete(spanId);
      } else {
        next.add(spanId);
      }
      return next;
    });
  const anyCollapsed = parentSpanIds.some((id) => collapsed.has(id));
  const toggleAll = () =>
    setCollapsed(anyCollapsed ? new Set() : new Set(parentSpanIds));

  const selectSpan = (spanId: string) =>
    setSelected((cur) => (cur === spanId ? null : spanId));

  if (isLoading) {
    return (
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          height: '60vh',
          gap: 1.5,
          color: 'text.secondary',
        }}
      >
        <CircularProgress size={18} thickness={5} /> Loading trace…
      </Box>
    );
  }
  if (error || !spans || spans.length === 0) {
    return (
      <Box sx={{ p: 4 }}>
        <Box
          component={RouterLink}
          to="/traces"
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 0.75,
            color: 'primary.main',
            textDecoration: 'none',
            fontWeight: 600,
            fontSize: 14,
            mb: 2,
          }}
        >
          <ArrowBackIcon sx={{ fontSize: 16 }} /> Back to traces
        </Box>
        <Typography color="text.secondary">
          {error ? error.message : 'Trace not found or has no spans.'}
        </Typography>
      </Box>
    );
  }

  const root = tree.roots[0];
  const gridColumns = 'minmax(220px, 40%) 1fr';

  return (
    <Box
      sx={{
        height: {
          xs: 'calc(100vh - 80px)',
          sm: 'calc(100vh - 84px)',
          md: 'calc(100vh - 56px)',
        },
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
      }}
    >
      <TraceDetailHeader
        traceId={traceId}
        sessionId={sessionId}
        spans={spans}
        rootName={root.name}
        earliestStartMs={earliest}
        totalMs={totalMs}
        errorCount={errorSpans.length}
        depthBySpanId={depthBySpanId}
        traceCostUsd={traceCostUsd}
        firstUserPrompt={firstUserPrompt}
      />

      {/* waterfall card */}
      <Box
        sx={{
          flex: 1,
          minHeight: 0,
          display: 'flex',
          flexDirection: 'column',
          border: 1,
          borderColor: 'divider',
          borderRadius: radii.xl,
          overflow: 'hidden',
          bgcolor: 'background.paper',
        }}
      >
        <WaterfallToolbar
          anyCollapsed={anyCollapsed}
          errorCount={errorSpans.length}
          onToggleAll={toggleAll}
          onNextError={nextError}
        />

        <TraceMinimap
          spans={spans}
          earliestStartMs={earliest}
          totalMs={totalMs}
          depthBySpanId={depthBySpanId}
          view={view}
          onViewChange={setView}
        />

        {/* axis */}
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: gridColumns,
            height: 22,
            alignItems: 'center',
            borderBottom: 1,
            borderColor: 'divider',
            flexShrink: 0,
          }}
        >
          <Box
            sx={{
              pl: 1.75,
              typography: 'eyebrowSm',
              color: 'text.disabled',
            }}
          >
            Span
          </Box>
          <Box sx={{ position: 'relative', height: '100%', mx: 1.5 }}>
            {[0, 0.25, 0.5, 0.75, 1].map((fraction) => (
              <Box
                key={fraction}
                component="span"
                sx={{
                  position: 'absolute',
                  top: '50%',
                  transform:
                    fraction === 1
                      ? 'translate(-100%,-50%)'
                      : 'translate(-50%,-50%)',
                  left: `${fraction * 100}%`,
                  typography: 'mono',
                  fontSize: 9.5,
                  color: 'text.disabled',
                }}
              >
                {formatDuration((view.s + fraction * visibleSpanMs) * 1e6)}
              </Box>
            ))}
          </Box>
        </Box>

        {/* body */}
        <Box
          ref={waterfallRef}
          sx={{ flex: 1, minHeight: 0, overflowX: 'hidden', overflowY: 'auto' }}
        >
          {visible.map((s) => {
            const left = Math.max(0, percentOf(offMsOf(s)));
            const right = Math.min(100, percentOf(offMsOf(s) + durMsOf(s)));
            return (
              <SpanWaterfallRow
                key={s.spanId}
                span={s}
                depth={depthBySpanId.get(s.spanId) ?? 0}
                hasChildren={
                  (tree.childrenByParentId.get(s.spanId) ?? []).length > 0
                }
                isCollapsed={collapsed.has(s.spanId)}
                isSelected={selected === s.spanId}
                indexLabel={spanIndices.get(s.spanId)}
                descendantErrorCount={descendantErrorCounts.get(s.spanId) ?? 0}
                logCount={logsBySpanId.get(s.spanId)?.length ?? 0}
                gridColumns={gridColumns}
                left={left}
                right={right}
                width={Math.max(0, right - left)}
                onToggleCollapse={toggleCollapse}
                onSelect={selectSpan}
              />
            );
          })}
        </Box>
      </Box>

      {/* detail dock — a separate card below the waterfall (gap above), matching the mockup */}
      {selected
        ? (() => {
            const s = spans.find((x) => x.spanId === selected);
            if (!s) {
              return null;
            }
            return (
              <SpanDetailDock
                span={s}
                selfNanos={
                  selfTimeNanosBySpanId.get(s.spanId) ?? s.durationNanos
                }
                tokens={tokenBreakdownForSpan(s)}
                logs={logsBySpanId.get(s.spanId) ?? []}
                onClose={() => setSelected(null)}
              />
            );
          })()
        : null}
    </Box>
  );
};

export default TraceDetailPageView;
