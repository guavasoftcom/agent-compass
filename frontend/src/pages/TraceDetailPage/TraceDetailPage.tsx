import { useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchTraceLogs } from '../../api';
import type { LogRow } from '../../api';
import { fetchSpansForTrace, fetchTraceSummaryOrNull } from '../TracesPage/tracesApi';
import { NANOS_PER_MILLI } from '../TracesPage/tracesApi';
import { isToolCallSpan } from '../TracesPage/traceDerivations';
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

  const {
    data: spans,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['trace-spans', traceId],
    queryFn: () => fetchSpansForTrace(traceId!),
    enabled: Boolean(traceId),
  });

  // Logs load eagerly (not gated on the drawer) so the per-span "Logs" section in
  // the detail dock has data the moment you select a span.
  const { data: logsData } = useQuery({
    queryKey: ['trace-logs', traceId],
    queryFn: () => fetchTraceLogs(traceId!),
    enabled: Boolean(traceId),
  });

  // The trace's aggregate row — the spans response is an array and can't carry
  // trace-level fields. Feeds `firstUserPrompt` and the header's authoritative
  // `totalCostUsd`; the header's other numbers (tokens, span/tool counts,
  // depth) stay derived from the spans already in hand.
  const { data: traceSummary } = useQuery({
    queryKey: ['trace-summary', traceId],
    queryFn: () => fetchTraceSummaryOrNull(traceId!),
    enabled: Boolean(traceId),
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

  // What "Collapse all" targets: tool-call spans that actually have children.
  // Collapsing *every* parent folded away the interaction/llm_request structure
  // the waterfall is read for; the noise it was aimed at is the SDK's per-call
  // sub-spans (`claude_code.tool.execution`, `claude_code.tool.blocked_on_user`)
  // hanging under each `claude_code.tool`. `isToolCallSpan` is the same rule the
  // traces list counts tool calls with, so sample-store span names
  // (`tool.Read`, `mcp.connect`) collapse too, and the sub-spans themselves —
  // which that helper excludes — stay expanded.
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
      descendantErrorCounts={descendantErrorCounts}
      selfTimeNanosBySpanId={selfTimeNanosBySpanId}
      logsBySpanId={logsBySpanId}
      sessionId={sessionId}
      firstUserPrompt={traceSummary?.firstUserPrompt ?? null}
      traceCostUsd={traceSummary?.totalCostUsd ?? null}
      traceBackgroundCostUsd={traceSummary?.backgroundCostUsd ?? 0}
    />
  );
}
