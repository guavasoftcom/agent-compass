import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchTraceLogs, fetchTraceSpans } from '../../api';
import {
  buildSpanIndices,
  buildSpanTree,
  computeTraceWindow,
  NANOS_PER_MILLI,
  type SpanTree,
  type TraceWindow,
} from './traceDetailHelpers';
import TraceDetailPageView from './TraceDetailPageView';

export default function TraceDetailPage() {
  const { traceId } = useParams<{ traceId: string }>();
  const [showLogs, setShowLogs] = useState(false);

  const {
    data: spans,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['trace-spans', traceId],
    queryFn: () => fetchTraceSpans(traceId!),
    enabled: Boolean(traceId),
  });

  const { data: logsData } = useQuery({
    queryKey: ['trace-logs', traceId],
    queryFn: () => fetchTraceLogs(traceId!),
    enabled: showLogs && Boolean(traceId),
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

  const traceWindow = useMemo<TraceWindow | null>(() => {
    if (!spans || spans.length === 0) {
      return null;
    }
    return computeTraceWindow(spans);
  }, [spans]);

  const parentSpanIds = useMemo(
    () => [...tree.childrenByParentId.keys()],
    [tree],
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
      const selfNanos = Math.max(0, totalNanos - unionMs * NANOS_PER_MILLI);
      map.set(span.spanId, selfNanos);
    }
    return map;
  }, [spans, tree]);

  const sessionId = useMemo<string | null>(() => {
    if (!spans || spans.length === 0) {
      return null;
    }
    const rootSpan =
      spans.find((candidate) => !candidate.parentSpanId) ?? spans[0];
    const fromAttrs = rootSpan.attributes?.['session.id'];
    if (typeof fromAttrs === 'string') {
      return fromAttrs;
    }
    const fromResource = rootSpan.resourceAttributes?.['session.id'];
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
      traceWindow={traceWindow}
      parentSpanIds={parentSpanIds}
      descendantErrorCounts={descendantErrorCounts}
      selfTimeNanosBySpanId={selfTimeNanosBySpanId}
      sessionId={sessionId}
      showLogs={showLogs}
      onShowLogsChange={setShowLogs}
      logs={logsData ?? []}
    />
  );
}
