import type { SpanRow } from '../../api';
import { NANOS_PER_MILLI } from '../TracesPage/tracesApi';

export interface TraceWindow {
  earliestStartMs: number;
  latestEndMs: number;
  totalMs: number;
}

export interface SpanTree {
  roots: SpanRow[];
  childrenByParentId: Map<string, SpanRow[]>;
}

export const computeTraceWindow = (spans: SpanRow[]): TraceWindow => {
  let earliestStartMs = Number.POSITIVE_INFINITY;
  let latestEndMs = Number.NEGATIVE_INFINITY;
  spans.forEach((span) => {
    const startMs = Date.parse(span.startTimestamp);
    const durationMs = (span.durationNanos ?? 0) / NANOS_PER_MILLI;
    const endMs = startMs + durationMs;
    if (startMs < earliestStartMs) {
      earliestStartMs = startMs;
    }
    if (endMs > latestEndMs) {
      latestEndMs = endMs;
    }
  });
  const totalMs = Math.max(latestEndMs - earliestStartMs, 1);
  return { earliestStartMs, latestEndMs, totalMs };
};

export const buildSpanTree = (spans: SpanRow[]): SpanTree => {
  const bySpanId = new Map<string, SpanRow>();
  spans.forEach((span) => {
    bySpanId.set(span.spanId, span);
  });
  const childrenByParentId = new Map<string, SpanRow[]>();
  const roots: SpanRow[] = [];
  spans.forEach((span) => {
    if (span.parentSpanId && bySpanId.has(span.parentSpanId)) {
      if (!childrenByParentId.has(span.parentSpanId)) {
        childrenByParentId.set(span.parentSpanId, []);
      }
      childrenByParentId.get(span.parentSpanId)!.push(span);
    } else {
      roots.push(span);
    }
  });
  const byStart = (left: SpanRow, right: SpanRow) =>
    Date.parse(left.startTimestamp) - Date.parse(right.startTimestamp);
  childrenByParentId.forEach((siblings) => siblings.sort(byStart));
  roots.sort(byStart);
  return { roots, childrenByParentId };
};

export const buildSpanIndices = (
  roots: SpanRow[],
  childrenByParentId: Map<string, SpanRow[]>,
): Map<string, number> => {
  const indices = new Map<string, number>();
  let counter = 1;
  const walk = (span: SpanRow) => {
    indices.set(span.spanId, counter++);
    for (const child of childrenByParentId.get(span.spanId) ?? []) {
      walk(child);
    }
  };
  for (const root of roots) {
    walk(root);
  }
  return indices;
};

// Depth (number of ancestors) of every span, computed in a single tree walk so the
// waterfall and minimap can read it as an O(1) lookup instead of re-deriving the
// parent chain per row.
export const buildSpanDepths = (
  roots: SpanRow[],
  childrenByParentId: Map<string, SpanRow[]>,
): Map<string, number> => {
  const depthBySpanId = new Map<string, number>();
  const walk = (span: SpanRow, depth: number) => {
    depthBySpanId.set(span.spanId, depth);
    for (const child of childrenByParentId.get(span.spanId) ?? []) {
      walk(child, depth + 1);
    }
  };
  for (const root of roots) {
    walk(root, 0);
  }
  return depthBySpanId;
};
