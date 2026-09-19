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
import { useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchTraceLogs } from '../../api';
import type { LogRow } from '../../api';
import { fetchSpansForTrace, fetchTraceSummaryOrNull } from '../TracesPage/tracesApi';
import { NANOS_PER_MILLI, RUNNING_TRACE_POLL_INTERVAL_MS } from '../TracesPage/tracesApi';
import { isToolCallSpan } from '../TracesPage/traceDerivations';
import { fetchOllamaSettings } from '../SettingsPage/settingsApi';
import { fetchTraceAnalysis } from './traceAnalysisApi';
import { buildAgentDispatchColoring } from './agentDispatch';
import {
  buildSpanDepths,
  buildSpanIndices,
  buildSpanTree,
  computeTraceWindow,
  type SpanTree,
  type TraceWindow,
} from './spanTree';
import { bucketLogsBySpan } from './logBuckets';
import TraceDetailPageView from './TraceDetailPageView';

export default function TraceDetailPage() {
  const { traceId } = useParams<{ traceId: string }>();

  // The trace's aggregate row — the spans response is an array and can't carry
  // trace-level fields. Feeds firstUserPrompt and the header's authoritative
  // totalCostUsd; the header's other numbers (tokens, span/tool counts,
  // depth) stay derived from the spans already in hand. Declared first (ahead
  // of the trace-spans query below) so its own resolved `data?.inProgress` can
  // drive both this query's and the spans query's `refetchInterval`.
  const traceSummaryQuery = useQuery({
    queryKey: ['trace-summary', traceId],
    queryFn: () => fetchTraceSummaryOrNull(traceId!),
    enabled: Boolean(traceId),
    // Function form (reads the query's own last result) rather than closing over
    // `traceSummaryQuery` itself, which isn't assigned yet at this point in the
    // hook call — same reasoning as useTracesExplorer's tableQuery.
    refetchInterval: (query) => (query.state.data?.inProgress ? RUNNING_TRACE_POLL_INTERVAL_MS : false),
  });
  const traceSummary = traceSummaryQuery.data;

  const {
    data: spans,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['trace-spans', traceId],
    queryFn: () => fetchSpansForTrace(traceId!),
    enabled: Boolean(traceId),
    // Keyed off the trace-summary query's own resolved inProgress, not a second
    // liveness check — a still-running trace's waterfall keeps growing new
    // spans until the summary query itself sees inProgress flip false.
    refetchInterval: traceSummaryQuery.data?.inProgress ? RUNNING_TRACE_POLL_INTERVAL_MS : false,
  });

  // Logs load eagerly (not gated on the drawer) so the per-span "Logs" section in
  // the detail dock has data the moment you select a span.
  const { data: logsData } = useQuery({
    queryKey: ['trace-logs', traceId],
    queryFn: () => fetchTraceLogs(traceId!),
    enabled: Boolean(traceId),
  });

  // Hoisted from AnalyzeTraceDialog (which still runs the identical query,
  // gated on 'open') so the toolbar's Analyze trace button can show a dot for
  // "has a saved analysis" / "saved analysis is outdated" without the reader
  // having to open the dialog first. Same query key, so this and the dialog's
  // own useQuery share one cache entry and one network request — mounting
  // the dialog doesn't trigger a second fetch.
  const { data: traceAnalysis } = useQuery({
    queryKey: ['trace-analysis', traceId],
    queryFn: () => fetchTraceAnalysis(traceId!),
    enabled: Boolean(traceId),
  });

  // Same query key as SettingsPage's Ollama tab, so the two share one cache
  // entry. Gates the "Analyze trace" button — see the Ollama configuration
  // section of SettingsPage/CLAUDE.md for why `enabled` is the real,
  // backend-enforced switch rather than a client-only flag. Defaults to
  // shown (true) while the query is still loading, matching the existing
  // Settings-page toggle's own initial state.
  const { data: ollamaSettings } = useQuery({
    queryKey: ['system-ollama-settings'],
    queryFn: fetchOllamaSettings,
  });

  const tree = useMemo<SpanTree>(() => {
    if (!spans || spans.length === 0) {
      return { roots: [], childrenByParentId: new Map() };
    }
    return buildSpanTree(spans);
  }, [spans]);

  const spanIndices = useMemo(
    () => buildSpanIndices(tree.roots, tree.childrenByParentId),
    [tree],
  );

  const depthBySpanId = useMemo(
    () => buildSpanDepths(tree.roots, tree.childrenByParentId),
    [tree],
  );

  const traceWindow = useMemo<TraceWindow | null>(() => {
    if (!spans || spans.length === 0) {
      return null;
    }
    return computeTraceWindow(spans);
  }, [spans]);

  const agentDispatchColoring = useMemo(
    () => buildAgentDispatchColoring(tree, spans ?? []),
    [tree, spans],
  );

  const collapsibleToolSpanIds = useMemo(
    () =>
      (spans ?? [])
        .filter(
          (span) =>
            isToolCallSpan(span.name) &&
            (tree.childrenByParentId.get(span.spanId)?.length ?? 0) > 0,
        )
        .map((span) => span.spanId),
    [spans, tree],
  );

  const descendantErrorCounts = useMemo<Map<string, number>>(() => {
    const counts = new Map<string, number>();
    if (!spans || spans.length === 0) {
      return counts;
    }
    const countBelow = (spanId: string): number => {
      const children = tree.childrenByParentId.get(spanId) ?? [];
      let total = 0;
      for (const child of children) {
        if (child.statusCode === 'error') {
          total += 1;
        }
        total += countBelow(child.spanId);
      }
      return total;
    };
    for (const span of spans) {
      counts.set(span.spanId, countBelow(span.spanId));
    }
    return counts;
  }, [spans, tree]);

  const selfTimeNanosBySpanId = useMemo<Map<string, number>>(() => {
    const map = new Map<string, number>();
    if (!spans || spans.length === 0) {
      return map;
    }
    for (const span of spans) {
      const totalNanos = span.durationNanos ?? 0;
      const children = tree.childrenByParentId.get(span.spanId) ?? [];
      if (children.length === 0) {
        map.set(span.spanId, totalNanos);
        continue;
      }
      const intervals = children
        .map((child) => ({
          start: Date.parse(child.startTimestamp),
          end: Date.parse(child.endTimestamp),
        }))
        .sort((left, right) => left.start - right.start);
      let unionMs = 0;
      let currentStart = Number.POSITIVE_INFINITY;
      let currentEnd = Number.NEGATIVE_INFINITY;
      for (const interval of intervals) {
        if (interval.start > currentEnd) {
          if (currentEnd > currentStart) {
            unionMs += currentEnd - currentStart;
          }
          currentStart = interval.start;
          currentEnd = interval.end;
        } else if (interval.end > currentEnd) {
          currentEnd = interval.end;
        }
      }
      if (currentEnd > currentStart) {
        unionMs += currentEnd - currentStart;
      }
      map.set(span.spanId, Math.max(0, totalNanos - unionMs * NANOS_PER_MILLI));
    }
    return map;
  }, [spans, tree]);

  const rootSpanId = tree.roots[0]?.spanId ?? '';
  const logsBySpanId = useMemo<Map<string, LogRow[]>>(() => {
    if (!spans || spans.length === 0 || !logsData || logsData.length === 0) {
      return new Map();
    }
    return bucketLogsBySpan(logsData, tree, rootSpanId);
  }, [logsData, spans, rootSpanId, tree]);

  const sessionId = useMemo<string | null>(() => {
    if (!spans || spans.length === 0) {
      return null;
    }
    const root = spans.find((s) => !s.parentSpanId) ?? spans[0];
    const fromAttrs = root.attributes?.['session.id'];
    if (typeof fromAttrs === 'string') {
      return fromAttrs;
    }
    const fromResource = root.resourceAttributes?.['session.id'];
    return typeof fromResource === 'string' ? fromResource : null;
  }, [spans]);

  return (
    <TraceDetailPageView
      key={traceId}
      traceId={traceId ?? ''}
      spans={spans}
      isLoading={isLoading}
      error={error as Error | null}
      tree={tree}
      spanIndices={spanIndices}
      depthBySpanId={depthBySpanId}
      traceWindow={traceWindow}
      collapsibleToolSpanIds={collapsibleToolSpanIds}
      agentColorBySpanId={agentDispatchColoring.colorBySpanId}
      agentLabelBySpanId={agentDispatchColoring.labelBySpanId}
      agentLegend={agentDispatchColoring.legend}
      descendantErrorCounts={descendantErrorCounts}
      selfTimeNanosBySpanId={selfTimeNanosBySpanId}
      logsBySpanId={logsBySpanId}
      sessionId={sessionId}
      firstUserPrompt={traceSummary?.firstUserPrompt ?? null}
      traceCostUsd={traceSummary?.totalCostUsd ?? null}
      traceBackgroundCostUsd={traceSummary?.backgroundCostUsd ?? 0}
      traceInProgress={traceSummary?.inProgress ?? false}
      traceAnalysis={traceAnalysis ?? null}
      ollamaAnalysisEnabled={ollamaSettings?.enabled ?? false}
    />
  );
}
