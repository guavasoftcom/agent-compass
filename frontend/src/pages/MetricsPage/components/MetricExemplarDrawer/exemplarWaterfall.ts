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
import type { SpanRow, TraceRow } from '../../../../api';
import { shortModelName } from '../../../../lib/format';
import {
  buildSpanIndices,
  buildSpanTree,
  computeTraceWindow,
} from '../../../TraceDetailPage/spanTree';

/**
 * How far below the trace's root the peek goes: the root turn and its direct children (the model
 * requests and tool calls). A tool call's own sub-spans and anything a dispatched subagent did
 * stay in the full Trace Detail page, which is one click away.
 */
export const EXEMPLAR_WATERFALL_MAX_DEPTH = 1;

/** Upper bound on rendered rows, so a turn with hundreds of calls cannot stretch the drawer. */
export const EXEMPLAR_WATERFALL_MAX_ROWS = 40;

export interface ExemplarWaterfallRow {
  span: SpanRow;
  depth: number;
  /** The span's number in the full trace's depth-first order, as the Trace Detail page shows it. */
  indexLabel: number | undefined;
  /** Errored spans below this one, whether or not they made it into the peek. */
  descendantErrorCount: number;
  /** Bar geometry as percentages of the whole trace's duration. */
  left: number;
  right: number;
  width: number;
}

export interface ExemplarWaterfall {
  rows: ExemplarWaterfallRow[];
  /** Spans in the trace that the peek does not draw. */
  hiddenSpanCount: number;
}

export type ExemplarStatus = 'ok' | 'error' | 'running';

const countDescendantErrors = (
  span: SpanRow,
  childrenByParentId: Map<string, SpanRow[]>,
  cache: Map<string, number>,
): number => {
  const cached = cache.get(span.spanId);
  if (cached !== undefined) {
    return cached;
  }
  let errorCount = 0;
  for (const child of childrenByParentId.get(span.spanId) ?? []) {
    errorCount += (child.statusCode === 'error' ? 1 : 0) + countDescendantErrors(child, childrenByParentId, cache);
  }
  cache.set(span.spanId, errorCount);
  return errorCount;
};

/**
 * The compact waterfall for one exemplar trace: depth-first rows down to
 * {@link EXEMPLAR_WATERFALL_MAX_DEPTH}, capped at {@link EXEMPLAR_WATERFALL_MAX_ROWS}, each with its bar
 * placed against the time extent of the rows that were kept.
 */
export const buildExemplarWaterfall = (spans: readonly SpanRow[]): ExemplarWaterfall => {
  if (spans.length === 0) {
    return { rows: [], hiddenSpanCount: 0 };
  }
  const allSpans = [...spans];
  const tree = buildSpanTree(allSpans);
  const indexBySpanId = buildSpanIndices(tree.roots, tree.childrenByParentId);
  const descendantErrorCache = new Map<string, number>();

  // Pick the rows first: the time axis is derived from them, so it has to be known who made the cut.
  const picked: { span: SpanRow; depth: number }[] = [];
  const walk = (siblings: SpanRow[], depth: number) => {
    for (const span of siblings) {
      if (picked.length >= EXEMPLAR_WATERFALL_MAX_ROWS) {
        return;
      }
      picked.push({ span, depth });
      if (depth < EXEMPLAR_WATERFALL_MAX_DEPTH) {
        walk(tree.childrenByParentId.get(span.spanId) ?? [], depth + 1);
      }
    }
  };
  walk(tree.roots, 0);

  // Scaled to the drawn rows, not the whole trace: a turn can dispatch background work that keeps
  // running long after it, and a window stretched to cover spans the peek does not draw would
  // squash every visible bar into a sliver at the left edge.
  const { earliestStartMs, totalMs } = computeTraceWindow(picked.map(({ span }) => span));

  const rows = picked.map(({ span, depth }): ExemplarWaterfallRow => {
    const offsetMs = Date.parse(span.startTimestamp) - earliestStartMs;
    const durationMs = (span.durationNanos ?? 0) / 1e6;
    const left = Math.max(0, (offsetMs / totalMs) * 100);
    const right = Math.min(100, ((offsetMs + durationMs) / totalMs) * 100);
    return {
      span,
      depth,
      indexLabel: indexBySpanId.get(span.spanId),
      descendantErrorCount: countDescendantErrors(span, tree.childrenByParentId, descendantErrorCache),
      left,
      right,
      width: Math.max(0, right - left),
    };
  });

  return { rows, hiddenSpanCount: allSpans.length - rows.length };
};

/**
 * The model(s) the trace's requests went to, for the stat row: the first-seen model's short name,
 * plus " +N" when the trace switched to others. Null when no span names a model (a trace with no
 * model request, or spans that have not loaded).
 */
export const summarizeExemplarModels = (spans: readonly SpanRow[]): string | null => {
  const distinctModels: string[] = [];
  for (const span of spans) {
    const modelAttribute = span.attributes?.['model'] ?? span.attributes?.['gen_ai.request.model'];
    if (typeof modelAttribute === 'string' && modelAttribute !== '' && !distinctModels.includes(modelAttribute)) {
      distinctModels.push(modelAttribute);
    }
  }
  if (distinctModels.length === 0) {
    return null;
  }
  const otherModelCount = distinctModels.length - 1;
  return otherModelCount > 0
    ? `${shortModelName(distinctModels[0])} +${otherModelCount}`
    : shortModelName(distinctModels[0]);
};

/**
 * The exemplar trace's outcome: the summary's own answer when it has one (it is the only place
 * `inProgress` lives), else derived from the spans, else unknown.
 */
export const exemplarStatusOf = (
  summary: TraceRow | null | undefined,
  spans: readonly SpanRow[] | undefined,
): ExemplarStatus | null => {
  if (summary) {
    if (summary.inProgress) {
      return 'running';
    }
    return summary.errorCount > 0 ? 'error' : 'ok';
  }
  if (spans && spans.length > 0) {
    return spans.some((span) => span.statusCode === 'error') ? 'error' : 'ok';
  }
  return null;
};
