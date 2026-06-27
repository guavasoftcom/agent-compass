import type { LogRow } from '../../api';
import type { SpanTree } from './spanTree';

const sequenceOf = (log: LogRow): number | null => {
  const raw = log.attributes?.['event.sequence'];
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return raw;
  }
  if (typeof raw === 'string') {
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
};

// Claude Code emits an `event.timestamp` attribute carrying the wall-clock time
// the event actually occurred. The OTLP log record's own `time_unix_nano` (which
// becomes log.timestamp) can lag by seconds when the SDK batches its exporter,
// so the attribute is the authoritative event time when present.
const eventTimeOf = (log: LogRow): number => {
  const raw = log.attributes?.['event.timestamp'];
  if (typeof raw === 'string') {
    const parsed = Date.parse(raw);
    if (!Number.isNaN(parsed)) {
      return parsed;
    }
  }
  return Date.parse(log.timestamp);
};

const compareLogs = (left: LogRow, right: LogRow): number => {
  const leftSequence = sequenceOf(left);
  const rightSequence = sequenceOf(right);
  if (leftSequence != null && rightSequence != null) {
    return leftSequence - rightSequence;
  }
  return eventTimeOf(left) - eventTimeOf(right);
};

// Collects every span ID rendered in the trace tree, so a log's own span_id can
// be checked against the spans we're actually showing. buildSpanTree places every
// span in exactly one of roots or a childrenByParentId list, so the union covers
// all spans.
const collectSpanIds = (tree: SpanTree): Set<string> => {
  const spanIds = new Set<string>();
  tree.roots.forEach((root) => spanIds.add(root.spanId));
  tree.childrenByParentId.forEach((children) => {
    children.forEach((child) => spanIds.add(child.spanId));
  });
  return spanIds;
};

// Attaches each log to its emitting span by OTLP span_id. Claude Code >= 2.1.152
// stamps trace context (trace_id + span_id) onto every event log emitted inside
// an active span, so a log's span_id resolves directly to the span it belongs to.
// The rare log without a usable span_id (e.g. a session-level event with no active
// span) lands on the root span so nothing is dropped.
export const bucketLogsBySpan = (
  logs: LogRow[],
  tree: SpanTree,
  rootSpanId: string,
): Map<string, LogRow[]> => {
  const logsBySpanId = new Map<string, LogRow[]>();
  const spanIdsInTree = collectSpanIds(tree);
  const push = (spanId: string, log: LogRow) => {
    let bucket = logsBySpanId.get(spanId);
    if (!bucket) {
      bucket = [];
      logsBySpanId.set(spanId, bucket);
    }
    bucket.push(log);
  };

  for (const log of logs) {
    const target = log.spanId && spanIdsInTree.has(log.spanId) ? log.spanId : rootSpanId;
    push(target, log);
  }

  for (const bucket of logsBySpanId.values()) {
    bucket.sort(compareLogs);
  }

  return logsBySpanId;
};
