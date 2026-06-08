import type { LogRow, SpanEvent, SpanRow } from '../../api';
import { NANOS_PER_MILLI } from '../TracesPage/timeFormat';

export { NANOS_PER_MILLI };

export const hasSpanDetails = (span: SpanRow): boolean => {
  return (
    Boolean(span.statusMessage) ||
    Boolean(span.scopeName) ||
    (span.attributes != null && Object.keys(span.attributes).length > 0) ||
    (span.events != null && span.events.length > 0)
  );
};

export const INPUT_TOKEN_KEYS = [
  'gen_ai.usage.input_tokens',
  'gen_ai.usage.prompt_tokens',
  'anthropic.usage.input_tokens',
  'input_tokens',
] as const;

export const OUTPUT_TOKEN_KEYS = [
  'gen_ai.usage.output_tokens',
  'gen_ai.usage.completion_tokens',
  'anthropic.usage.output_tokens',
  'output_tokens',
] as const;

export const CACHE_CREATE_TOKEN_KEYS = [
  'gen_ai.usage.cache_creation_input_tokens',
  'gen_ai.usage.cache_creation_tokens',
  'anthropic.usage.cache_creation_input_tokens',
  'anthropic.usage.cache_creation_tokens',
  'cache_creation_input_tokens',
  'cache_creation_tokens',
] as const;

export const CACHE_READ_TOKEN_KEYS = [
  'gen_ai.usage.cache_read_input_tokens',
  'gen_ai.usage.cache_read_tokens',
  'anthropic.usage.cache_read_input_tokens',
  'anthropic.usage.cache_read_tokens',
  'cache_read_input_tokens',
  'cache_read_tokens',
] as const;

export interface TokenBreakdown {
  input: number;
  output: number;
  cacheCreate: number;
  cacheRead: number;
  total: number;
}

export const readNumericAttr = (
  attrs: Record<string, unknown>,
  keys: readonly string[],
): number => {
  for (const key of keys) {
    const value = attrs[key];
    if (typeof value === 'number' && Number.isFinite(value)) {
      return value;
    }
    if (typeof value === 'string') {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) {
        return parsed;
      }
    }
  }
  return 0;
};

export const tokenBreakdownForSpan = (span: SpanRow): TokenBreakdown => {
  const attrs = span.attributes;
  if (!attrs) {
    return { input: 0, output: 0, cacheCreate: 0, cacheRead: 0, total: 0 };
  }
  const input = readNumericAttr(attrs, INPUT_TOKEN_KEYS);
  const output = readNumericAttr(attrs, OUTPUT_TOKEN_KEYS);
  const cacheCreate = readNumericAttr(attrs, CACHE_CREATE_TOKEN_KEYS);
  const cacheRead = readNumericAttr(attrs, CACHE_READ_TOKEN_KEYS);
  return {
    input,
    output,
    cacheCreate,
    cacheRead,
    total: input + output + cacheCreate + cacheRead,
  };
};

export const HIGH_TOKEN_PERCENTILE = 0.75;

// Tokens that actually incur a cost: input, output, and cache_create. Cache reads are
// excluded because they bill at roughly 10% of regular input price — counting them dominates
// any cost-based "expensive span" signal (cache reads are usually the bulk of total tokens
// in a Claude Code session yet contribute little to actual spend).
export const billableTokensForSpan = (span: SpanRow): number => {
  if (!span.attributes) {
    return 0;
  }
  return (
    readNumericAttr(span.attributes, INPUT_TOKEN_KEYS) +
    readNumericAttr(span.attributes, OUTPUT_TOKEN_KEYS) +
    readNumericAttr(span.attributes, CACHE_CREATE_TOKEN_KEYS)
  );
};

export const attrValueAsString = (value: unknown): string => {
  if (value == null) {
    return String(value);
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
};

export const eventOffsetNanos = (event: SpanEvent, span: SpanRow): number => {
  return (
    (Date.parse(event.timestamp) - Date.parse(span.startTimestamp)) *
    NANOS_PER_MILLI
  );
};

export const SEVERITY_TRACE_MAX = 4;
export const SEVERITY_DEBUG_MAX = 8;
export const SEVERITY_INFO_MAX = 12;
export const SEVERITY_WARN_MAX = 16;
export const SEVERITY_ERROR_MAX = 20;

export const severityLabel = (severityNumber: number | null): string => {
  if (severityNumber == null) { return 'UNSET'; }
  if (severityNumber <= SEVERITY_TRACE_MAX) { return 'TRACE'; }
  if (severityNumber <= SEVERITY_DEBUG_MAX) { return 'DEBUG'; }
  if (severityNumber <= SEVERITY_INFO_MAX) { return 'INFO'; }
  if (severityNumber <= SEVERITY_WARN_MAX) { return 'WARN'; }
  if (severityNumber <= SEVERITY_ERROR_MAX) { return 'ERROR'; }
  return 'FATAL';
};

export const severityColor = (severityNumber: number | null): 'default' | 'info' | 'warning' | 'error' => {
  if (severityNumber == null) { return 'default'; }
  if (severityNumber <= SEVERITY_DEBUG_MAX) { return 'default'; }
  if (severityNumber <= SEVERITY_INFO_MAX) { return 'info'; }
  if (severityNumber <= SEVERITY_WARN_MAX) { return 'warning'; }
  return 'error';
};

export const sequenceOf = (log: LogRow): number | null => {
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
export const eventTimeOf = (log: LogRow): number => {
  const raw = log.attributes?.['event.timestamp'];
  if (typeof raw === 'string') {
    const parsed = Date.parse(raw);
    if (!Number.isNaN(parsed)) {
      return parsed;
    }
  }
  return Date.parse(log.timestamp);
};

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

export const bucketLogsBySpan = (
  logs: LogRow[],
  spans: SpanRow[],
  rootSpanId: string,
): Map<string, LogRow[]> => {
  const knownSpanIds = new Set(spans.map((s) => s.spanId));
  // Sorted shortest-first so the timestamp fallback always picks the innermost
  // containing span (same logic as the old heuristic-only approach).
  const sortedByDuration = [...spans].sort(
    (a, b) => (a.durationNanos ?? 0) - (b.durationNanos ?? 0),
  );
  const logsBySpanId = new Map<string, LogRow[]>();

  const push = (spanId: string, log: LogRow) => {
    let bucket = logsBySpanId.get(spanId);
    if (!bucket) {
      bucket = [];
      logsBySpanId.set(spanId, bucket);
    }
    bucket.push(log);
  };

  for (const log of logs) {
    if (log.spanId && knownSpanIds.has(log.spanId)) {
      push(log.spanId, log);
      continue;
    }
    // Orphaned log (span_id not in this trace's span set): try timestamp
    // containment so the log lands on the most specific span that covers it
    // rather than always flooding the root span.
    const logMs = eventTimeOf(log);
    let placed = false;
    for (const span of sortedByDuration) {
      if (
        logMs >= Date.parse(span.startTimestamp) &&
        logMs <= Date.parse(span.endTimestamp)
      ) {
        push(span.spanId, log);
        placed = true;
        break;
      }
    }
    if (!placed) {
      push(rootSpanId, log);
    }
  }

  for (const bucket of logsBySpanId.values()) {
    bucket.sort((left, right) => {
      const leftSequence = sequenceOf(left);
      const rightSequence = sequenceOf(right);
      if (leftSequence != null && rightSequence != null) {
        return leftSequence - rightSequence;
      }
      return eventTimeOf(left) - eventTimeOf(right);
    });
  }

  return logsBySpanId;
};
