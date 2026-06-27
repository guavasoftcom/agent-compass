import type { SpanRow } from '../../api';

// Critical path = the chain of spans that actually determines total wall-clock
// duration. Walk backward from the root: at each span, among its children that
// overlap the still-unexplained interval, follow the LAST finisher, then move the
// cursor back to that child's start and repeat. Spans NOT on this set ran in
// parallel "off the critical path" and could be sped up without shrinking the
// trace. Mirrors the mockup's algorithm.
export const computeCriticalPath = (
  roots: SpanRow[],
  childrenByParentId: Map<string, SpanRow[]>,
): Set<string> => {
  const criticalSpanIds = new Set<string>();
  if (roots.length === 0) {
    return criticalSpanIds;
  }
  const toMillis = (iso: string) => Date.parse(iso);
  const walk = (
    span: SpanRow,
    intervalStartMs: number,
    intervalEndMs: number,
  ) => {
    criticalSpanIds.add(span.spanId);
    const children = (childrenByParentId.get(span.spanId) ?? [])
      .slice()
      .sort((a, b) => toMillis(b.endTimestamp) - toMillis(a.endTimestamp));
    let cursor = intervalEndMs;
    for (const child of children) {
      const childStartMs = toMillis(child.startTimestamp);
      const childEndMs = toMillis(child.endTimestamp);
      if (childEndMs <= intervalStartMs || childStartMs >= cursor) {
        continue;
      }
      const segmentStartMs = Math.max(childStartMs, intervalStartMs);
      const segmentEndMs = Math.min(childEndMs, cursor);
      if (segmentEndMs > segmentStartMs) {
        walk(child, segmentStartMs, segmentEndMs);
        cursor = Math.min(cursor, childStartMs);
      }
    }
  };
  // pick the root that spans the whole trace (latest end)
  const mainRoot = roots
    .slice()
    .sort((a, b) => toMillis(b.endTimestamp) - toMillis(a.endTimestamp))[0];
  walk(mainRoot, toMillis(mainRoot.startTimestamp), toMillis(mainRoot.endTimestamp));
  return criticalSpanIds;
};
