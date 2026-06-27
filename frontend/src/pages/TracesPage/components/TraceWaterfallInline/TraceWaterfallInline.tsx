import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import type { SpanRow, TraceRow } from '../../../../api';
import { fetchSpansForTrace } from '../../tracesApi';
import TraceWaterfallInlineView, {
  type PlacedSpan,
} from './TraceWaterfallInlineView';

interface TraceWaterfallInlineProps {
  trace: TraceRow;
}

const TraceWaterfallInline = ({ trace }: TraceWaterfallInlineProps) => {
  const navigate = useNavigate();
  const { data: spans, isLoading } = useQuery({
    queryKey: ['trace-inline-spans', trace.traceId],
    queryFn: () => fetchSpansForTrace(trace.traceId),
  });

  const { placedSpans, totalDurationMs } = useMemo(() => {
    if (!spans || spans.length === 0) {
      return { placedSpans: [] as PlacedSpan[], totalDurationMs: 1 };
    }
    const spansById = new Map(spans.map((s) => [s.spanId, s]));
    const earliestStartMs = Math.min(
      ...spans.map((s) => Date.parse(s.startTimestamp)),
    );
    const latestEndMs = Math.max(
      ...spans.map((s) => Date.parse(s.endTimestamp)),
    );
    const totalDurationMs = Math.max(1, latestEndMs - earliestStartMs);
    const depthOf = (span: SpanRow): number => {
      let depth = 0;
      let currentSpan: SpanRow | undefined = span;
      while (
        currentSpan?.parentSpanId &&
        spansById.has(currentSpan.parentSpanId)
      ) {
        depth += 1;
        currentSpan = spansById.get(currentSpan.parentSpanId);
      }
      return depth;
    };
    const placedSpans: PlacedSpan[] = spans
      .slice()
      .sort(
        (a, b) => Date.parse(a.startTimestamp) - Date.parse(b.startTimestamp),
      )
      .map((span) => {
        const offsetMs = Date.parse(span.startTimestamp) - earliestStartMs;
        const durationMs = span.durationNanos / 1e6;
        return {
          span,
          offPct: (offsetMs / totalDurationMs) * 100,
          wPct: Math.max(0.6, (durationMs / totalDurationMs) * 100),
          depth: depthOf(span),
          error: span.statusCode === 'error',
        };
      });
    return { placedSpans, totalDurationMs };
  }, [spans]);

  return (
    <TraceWaterfallInlineView
      trace={trace}
      placed={placedSpans}
      totalMs={totalDurationMs}
      isLoading={isLoading}
      onOpenTrace={() => navigate(`/traces/${trace.traceId}`)}
    />
  );
};

export default TraceWaterfallInline;
