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
import { Link as RouterLink } from 'react-router-dom';
import { Box, CircularProgress, Typography } from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import type { LogRow, SpanRow } from '../../api';
import { formatDuration } from '../TracesPage/tracesApi';
import { tokenBreakdownForSpan } from '../TracesPage/tokenBreakdown';
import { costOfSelectedSpan, costOfSpan } from './spanCost';
import type { AgentDispatchLegendEntry } from './agentDispatch';
import { type SpanTree, type TraceWindow } from './spanTree';
import { type TraceAnalysisResult } from './traceAnalysisApi';
import {
  loadChipsOff,
  persistChipsOff,
  type ChipFamily,
} from './chipVisibility';
import SpanInspectorDrawer, {
  type SpanInspectorSelection,
} from './components/SpanInspectorDrawer';
import TraceDetailHeader from './components/TraceDetailHeader';
import TraceMinimap, { type ZoomView } from './components/TraceMinimap';
import WaterfallToolbar from './components/WaterfallToolbar';
import SpanWaterfallRow from './components/SpanWaterfallRow';
import LiveTailRow from './components/LiveTailRow';
import AnalyzeTraceDialog from './components/AnalyzeTraceDialog';
import { radii } from '../../theme/theme';
import { useNowTick } from '../../lib/useNowTick';

export interface TraceDetailPageViewProps {
  traceId: string;
  spans: SpanRow[] | undefined;
  isLoading: boolean;
  error: Error | null;
  tree: SpanTree;
  spanIndices: Map<string, number>;
  depthBySpanId: Map<string, number>;
  traceWindow: TraceWindow | null;
  // Spans "Collapse all" folds: tool-call spans with children (see the
  // container). Empty for a trace with no tool calls, which hides the button.
  collapsibleToolSpanIds: string[];
  // Per-span subagent-dispatch color: every span belonging to an Agent-tool dispatch (the
  // dispatch span itself and its whole subtree) resolved to the color its dispatch was assigned;
  // absent for a main-loop span. See agentDispatch.ts for the color-assignment rule.
  agentColorBySpanId: Map<string, string>;
  // The matching label (subagent_type, or the generic fallback) for the same span set —
  // SpanInspectorDrawer pairs this with agentColorBySpanId to render the drawer header's
  // "which subagent" name chip for the selected span.
  agentLabelBySpanId: Map<string, string>;
  // Distinct dispatch labels paired with their color, first-seen order — what WaterfallToolbar
  // renders as extra legend swatches. Empty when the trace dispatched no subagent.
  agentLegend: AgentDispatchLegendEntry[];
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
  // TraceRow.backgroundCostUsd — the portion of traceCostUsd billed after this
  // trace's own root span closed. Defaults to 0 (same as an unresolved query)
  // rather than null, since the header only needs to know whether to show the
  // background-cost caption at all.
  traceBackgroundCostUsd: number;
  // TraceRow.inProgress from the same `['trace-summary', traceId]` query —
  // true while this trace is still running (no exported root span yet, active
  // in the last 20 minutes). Defaults to false while the query hasn't resolved
  // yet. Drives the header's RunningIndicator dot and the two conditional
  // `refetchInterval`s in TraceDetailPage (trace-summary and trace-spans).
  traceInProgress: boolean;
  // Cached trace analysis result, hoisted from AnalyzeTraceDialog so the
  // toolbar can show a dot for "has a saved analysis" without opening the dialog.
  traceAnalysis: TraceAnalysisResult | null;
  // Effective Ollama `enabled` setting (SettingsPage's Ollama tab) — gates the
  // toolbar's "Analyze trace" button. See SettingsPage/CLAUDE.md's Ollama
  // configuration section: this is a real, backend-enforced switch, not a
  // client-only flag, so hiding the button here is a convenience on top of
  // the server already refusing the call when disabled.
  ollamaAnalysisEnabled: boolean;
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
  collapsibleToolSpanIds,
  agentColorBySpanId,
  agentLabelBySpanId,
  agentLegend,
  descendantErrorCounts,
  selfTimeNanosBySpanId,
  logsBySpanId,
  sessionId,
  firstUserPrompt,
  traceCostUsd,
  traceBackgroundCostUsd,
  traceInProgress,
  traceAnalysis,
  ollamaAnalysisEnabled,
}: TraceDetailPageViewProps) => {
  const waterfallRef = useRef<HTMLDivElement>(null);

  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);

  // Row-density preference, not per-trace state: unlike collapsed/selected/view
  // it deliberately survives the key={traceId} remount, because the initial
  // value is re-read from localStorage rather than reset to empty.
  const [chipsOff, setChipsOff] = useState<Set<ChipFamily>>(() => loadChipsOff());
  const toggleChipFamily = useCallback((family: ChipFamily) => {
    setChipsOff((previous) => {
      const next = new Set(previous);
      if (next.has(family)) {
        next.delete(family);
      } else {
        next.add(family);
      }
      persistChipsOff(next);
      return next;
    });
  }, []);

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

  // One cycling position per agent-dispatch legend label, mirroring errorIndexRef/nextError above:
  // a legend swatch can name several same-type dispatches, so repeat clicks step through all of
  // them rather than only ever landing on the first. Keyed by label (not a single shared index)
  // because each label's own click cycles independently of the others.
  const agentDispatchCycleIndexByLabelRef = useRef<Map<string, number>>(new Map());

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

  // The live-tail row's geometry — see LiveTailRow's own doc comment for why this is a new
  // trailing segment rather than an existing row's bar growing. useNowTick is mounted
  // unconditionally (Rules of Hooks), but its return is only used while traceInProgress —
  // a finished trace's row math never depends on the ticking clock.
  const now = useNowTick();
  const lastKnownEndMs = traceInProgress
    ? Math.max(0, ...visible.map((s) => offMsOf(s) + durMsOf(s)))
    : 0;
  const liveTailLeft = traceInProgress ? Math.max(0, percentOf(lastKnownEndMs)) : 0;
  const liveTailRight = traceInProgress
    ? Math.min(100, percentOf(Math.min(view.e, now - earliest)))
    : 0;

  // Bring a span's row into view, scrolling only as far as it takes and leaving
  // a couple of rows of context at whichever edge the row entered from. Rows
  // already comfortably in view don't move the list at all, so stepping through
  // spans slides the highlight rather than yanking the waterfall on every press.
  //
  // Geometry comes from live rects rather than `el.offsetTop`: offsetTop is
  // measured from the nearest *positioned* ancestor, and nothing between a row
  // and <body> is positioned, so it included the app chrome, header, toolbar,
  // minimap, and axis — scrolling the target clean past the top edge.
  const scrollToSpan = useCallback((spanId: string) => {
    const container = waterfallRef.current;
    const row = container?.querySelector(
      `[data-span="${spanId}"]`,
    ) as HTMLElement | null;
    if (!container || !row) {
      return;
    }
    const containerBounds = container.getBoundingClientRect();
    const rowBounds = row.getBoundingClientRect();
    // Capped so a short waterfall (or a tall row) can't demand more margin than
    // the visible band has room for, which would leave the two edges fighting.
    const edgeMargin = Math.min(rowBounds.height * 2, containerBounds.height / 4);
    const visibleTop = containerBounds.top + edgeMargin;
    const visibleBottom = containerBounds.bottom - edgeMargin;
    if (rowBounds.top < visibleTop) {
      container.scrollTop -= visibleTop - rowBounds.top;
    } else if (rowBounds.bottom > visibleBottom) {
      container.scrollTop += rowBounds.bottom - visibleBottom;
    }
  }, []);

  // Nothing is selected on arrival, so the inspector drawer starts closed and the
  // waterfall gets the full width. An earlier revision auto-selected the first
  // error span here, which opened the drawer and scrolled away from the top of
  // the trace before the reader had looked at it; "Next error" is the way in
  // now — errorIndexRef starts at -1, so the first press lands on error 1 of N.
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
  // "Expand all" clears everything that's collapsed — including rows collapsed
  // one chevron at a time — while "Collapse all" only folds the tool-call spans.
  const anyCollapsed = collapsed.size > 0;
  const canToggleAll = anyCollapsed || collapsibleToolSpanIds.length > 0;
  const toggleAll = () =>
    setCollapsed(anyCollapsed ? new Set() : new Set(collapsibleToolSpanIds));

  const selectSpan = (spanId: string) =>
    setSelected((cur) => (cur === spanId ? null : spanId));

  // Span nav — derived fresh each render, same pattern as errorSpans above. It
  // walks `visible`, the rendered row list, rather than the selected span's
  // siblings: every span has a row above/below it (a single-root trace's root
  // has no siblings at all, which left the most commonly selected span with no
  // nav), and the target is guaranteed on screen, so scrollToSpan's [data-span]
  // lookup can't miss the way it does for a collapsed-away or zoomed-out span.
  const selectedSpan = spans?.find((s) => s.spanId === selected) ?? null;
  const waterfallIndex = visible.findIndex((s) => s.spanId === selected);
  // 0 hides the nav — either nothing is selected, or the selected span is no
  // longer rendered (its parent was collapsed, or the zoom window moved off it).
  const waterfallCount = waterfallIndex >= 0 ? visible.length : 0;

  const selectAdjacentSpan = useCallback(
    (delta: number) => {
      const currentIndex = visible.findIndex((s) => s.spanId === selected);
      if (currentIndex < 0) {
        return;
      }
      const nextIndex = currentIndex + delta;
      if (nextIndex >= 0 && nextIndex < visible.length) {
        const spanId = visible[nextIndex].spanId;
        setSelected(spanId);
        scrollToSpan(spanId);
      }
    },
    [visible, selected, scrollToSpan],
  );

  // Reveals a related call CallContextSection linked to: expands whatever
  // collapsed ancestor is hiding it, widens the zoom window if it sits outside
  // the current view, then selects and scrolls to it. Expanding/widening only
  // takes effect on the next render, so the scroll itself is deferred to the
  // pendingRevealRef + effect below rather than run synchronously here, where
  // the row wouldn't exist in the DOM yet.
  const pendingRevealRef = useRef<string | null>(null);
  // Bumped on every revealSpan call, purely to force the effect below to
  // re-run -- see that effect's own comment for why visible can't be trusted
  // as the trigger.
  const [pendingRevealTick, setPendingRevealTick] = useState(0);
  const revealSpan = useCallback(
    (spanId: string) => {
      const spanById = new Map((spans ?? []).map((s) => [s.spanId, s]));
      const target = spanById.get(spanId);
      if (!target) {
        return;
      }
      setCollapsed((previous) => {
        if (previous.size === 0) {
          return previous;
        }
        const next = new Set(previous);
        let current: SpanRow | undefined = target;
        while (current?.parentSpanId) {
          next.delete(current.parentSpanId);
          current = spanById.get(current.parentSpanId);
        }
        return next.size === previous.size ? previous : next;
      });
      const off = offMsOf(target);
      const dur = durMsOf(target);
      setView((previous) => {
        const start = Math.min(previous.s, off);
        const end = Math.max(previous.e, off + dur);
        return start === previous.s && end === previous.e ? previous : { s: start, e: end };
      });
      setSelected(spanId);
      pendingRevealRef.current = spanId;
      setPendingRevealTick((tick) => tick + 1);
    },
    [spans, offMsOf],
  );

  // Keyed on pendingRevealTick, not visible, so a target that is already
  // expanded and in the zoom window (where setCollapsed/setView bail out and
  // return their previous state, leaving visible's reference unchanged) still
  // triggers the scroll -- the tick bump batches into the same render as
  // those two updates, so the DOM still reflects them by the time this runs.
  useEffect(() => {
    if (pendingRevealRef.current) {
      const spanId = pendingRevealRef.current;
      pendingRevealRef.current = null;
      scrollToSpan(spanId);
    }
  }, [pendingRevealTick, scrollToSpan]);

  // Clicking a WaterfallToolbar agent-dispatch legend swatch jumps to one of that label's dispatch
  // spans via revealSpan, same idiom as nextError/errorIndexRef above: a ref-held index per label,
  // incremented mod the dispatch count on each press so repeat clicks cycle through every dispatch
  // of that type rather than only ever landing on the first.
  const revealNextAgentDispatch = useCallback(
    (label: string, dispatchSpanIds: string[]) => {
      if (dispatchSpanIds.length === 0) {
        return;
      }
      const previousIndex = agentDispatchCycleIndexByLabelRef.current.get(label) ?? -1;
      const nextIndex = (previousIndex + 1) % dispatchSpanIds.length;
      agentDispatchCycleIndexByLabelRef.current.set(label, nextIndex);
      revealSpan(dispatchSpanIds[nextIndex]);
    },
    [revealSpan],
  );

  const [analyzeTraceDialogOpen, setAnalyzeTraceDialogOpen] = useState(false);

  // The dialog's call citations ("call 20") link back to a specific waterfall
  // row by the backend-numbered callNumber (SpanRow.callNumber), never the
  // spanIndices DFS counter — see this page's CLAUDE.md "call 20 is not row
  // 20" gotcha for why the two must never be conflated.
  const spanIdByCallNumber = useMemo(() => {
    const map = new Map<number, string>();
    for (const span of spans ?? []) {
      if (span.callNumber !== null && span.callNumber !== undefined) {
        map.set(span.callNumber, span.spanId);
      }
    }
    return map;
  }, [spans]);
  const knownCallNumbers = useMemo(
    () => new Set(spanIdByCallNumber.keys()),
    [spanIdByCallNumber],
  );
  const navigateToCall = useCallback(
    (callNumber: number) => {
      const spanId = spanIdByCallNumber.get(callNumber);
      if (!spanId) {
        return;
      }
      setAnalyzeTraceDialogOpen(false);
      revealSpan(spanId);
    },
    [spanIdByCallNumber, revealSpan, setAnalyzeTraceDialogOpen],
  );

  // ArrowUp/ArrowDown step to the row above/below while a span is selected.
  // Deliberately narrow about what it claims: this is a window-level listener
  // that preventDefaults, so anything it swallows is scrolling or typing the
  // user expected to work. It stands down for text entry, for modifier
  // combinations (browser/OS shortcuts), inside any open dialog — a log-value
  // modal would otherwise be destroyed mid-read when the span swap remounts the
  // span-id-keyed drawer content — and inside the drawer's own scroll column,
  // where arrow keys should scroll.
  useEffect(() => {
    if (!selected) {
      return undefined;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey) {
        return;
      }
      const target = event.target as HTMLElement | null;
      const tag = target?.tagName?.toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select') {
        return;
      }
      if (target?.isContentEditable) {
        return;
      }
      if (target?.closest('[role="dialog"], [data-drawer-scroll]')) {
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        selectAdjacentSpan(-1);
      } else if (event.key === 'ArrowDown') {
        event.preventDefault();
        selectAdjacentSpan(1);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [selected, selectAdjacentSpan]);

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

  const drawerSelection: SpanInspectorSelection | null = selectedSpan
    ? {
        span: selectedSpan,
        selfTimeNanos:
          selfTimeNanosBySpanId.get(selectedSpan.spanId) ??
          selectedSpan.durationNanos,
        tokens: tokenBreakdownForSpan(selectedSpan),
        logs: logsBySpanId.get(selectedSpan.spanId) ?? [],
        // Falls back to the span's own api_request logs when nothing was
        // stamped against the span itself — see costOfSelectedSpan. That is
        // what puts a per-call figure on an llm_request row, which span_costs
        // leaves at 0.
        costUsd: costOfSelectedSpan(selectedSpan, logsBySpanId.get(selectedSpan.spanId)),
        waterfallIndex,
        waterfallCount,
      }
    : null;

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
        selfTimeNanosBySpanId={selfTimeNanosBySpanId}
        traceCostUsd={traceCostUsd}
        traceBackgroundCostUsd={traceBackgroundCostUsd}
        firstUserPrompt={firstUserPrompt}
        inProgress={traceInProgress}
      />

      {/* body row: waterfall card + inspector drawer as flex siblings, so the
          waterfall's width recalculates live as the drawer opens/resizes and
          keeps its full height (all rows) while a span is inspected */}
      <Box sx={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'row' }}>
        {/* waterfall card */}
        <Box
          sx={{
            flex: 1,
            minWidth: 0,
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
            canToggleAll={canToggleAll}
            errorCount={errorSpans.length}
            onToggleAll={toggleAll}
            onNextError={nextError}
            chipsOff={chipsOff}
            onToggleChipFamily={toggleChipFamily}
            onAnalyzeTrace={() => setAnalyzeTraceDialogOpen(true)}
            hasAnalysis={traceAnalysis !== null}
            analysisOutdated={traceAnalysis?.outdated ?? false}
            ollamaAnalysisEnabled={ollamaAnalysisEnabled}
            agentLegend={agentLegend}
            onAgentLegendClick={revealNextAgentDispatch}
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
                  costUsd={costOfSelectedSpan(s, logsBySpanId.get(s.spanId))}
                  isRollupCost={costOfSpan(s) > 0}
                  agentColor={agentColorBySpanId.get(s.spanId)}
                  chipsOff={chipsOff}
                  logs={logsBySpanId.get(s.spanId)}
                  gridColumns={gridColumns}
                  left={left}
                  right={right}
                  width={Math.max(0, right - left)}
                  onToggleCollapse={toggleCollapse}
                  onSelect={selectSpan}
                />
              );
            })}
            {traceInProgress ? (
              <LiveTailRow
                gridColumns={gridColumns}
                left={liveTailLeft}
                right={liveTailRight}
              />
            ) : null}
          </Box>
        </Box>

        <SpanInspectorDrawer
          selection={drawerSelection}
          spans={spans}
          logsBySpanId={logsBySpanId}
          agentColorBySpanId={agentColorBySpanId}
          agentLabelBySpanId={agentLabelBySpanId}
          onRevealSpan={revealSpan}
          onClose={() => setSelected(null)}
          onPreviousSpan={() => selectAdjacentSpan(-1)}
          onNextSpan={() => selectAdjacentSpan(1)}
        />
      </Box>

      {analyzeTraceDialogOpen ? (
        <AnalyzeTraceDialog
          open={analyzeTraceDialogOpen}
          onClose={() => setAnalyzeTraceDialogOpen(false)}
          traceId={traceId}
          knownCallNumbers={knownCallNumbers}
          onNavigateToCall={navigateToCall}
          spans={spans}
          logsBySpanId={logsBySpanId}
          traceCostUsd={traceCostUsd}
        />
      ) : null}
    </Box>
  );
};

export default TraceDetailPageView;
