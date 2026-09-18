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
import type { SessionPromptRow } from '../../../../api';

// A turn positioned in the nested (indented) render order: `depth` is how many
// ancestor dispatchers sit above it, `railBelow[d]` records whether the
// ancestor at that depth still has a later sibling to come (the renderer's
// cue for a continuing connector line vs. a bare elbow), and `originalIndex`
// is the row's position in the chronological `prompts` array, kept because
// reordering means the array index can no longer serve as a stable React key.
export interface NestedPromptRow {
  row: SessionPromptRow;
  depth: number;
  railBelow: boolean[];
  originalIndex: number;
}

/**
 * Groups a session's prompt-timeline turns so a background-dispatched
 * subagent's turn nests directly beneath the turn that dispatched it, instead
 * of rendering as an unrelated card further down the timeline. Rows are
 * REORDERED — a child is moved to sit directly beneath its dispatcher —
 * chronological order (the input array's own order, which
 * `fetchSessionPrompts` already returns chronologically) wins wherever the
 * two would conflict, since a connector can only "point back" to an adjacent
 * row. Same algorithm as `TraceDetailPage`'s `nestSwitchTraceRows`
 * (`switchTraceRows.ts`), adapted for the full, unfiltered turn list this
 * panel renders (a turn's `traceId`/`prompt` can be null here — pre-tracing
 * sessions and capture-disabled prompts are kept, not filtered).
 *
 * Nesting is recursive: a chained dispatch nests to depth 2 and beyond. Row
 * `i` is a child of row `p` iff `dispatchingTraceId` is non-null, differs
 * from the row's own `traceId`, and resolves — via first-occurrence-wins
 * trace-id lookup (several turns can share one trace id; the earliest is the
 * "owner") — to an index `p` strictly earlier than `i`. A row whose own
 * `traceId` is null never becomes a nesting parent, since there is nothing
 * for a later `dispatchingTraceId` to resolve to.
 *
 * Every edge case resolves to "stay top-level, in place": a dispatcher trace
 * absent from the timeline, a self-referencing `dispatchingTraceId`, a null
 * `dispatchingTraceId` (the overwhelming majority of turns), and a dispatcher
 * appearing AFTER its claimed child.
 */
export const nestPromptRows = (rows: SessionPromptRow[]): NestedPromptRow[] => {
  const hasAnyDispatch = rows.some((row) => Boolean(row.dispatchingTraceId));
  if (!hasAnyDispatch) {
    return rows.map((row, originalIndex) => ({
      row,
      depth: 0,
      railBelow: [],
      originalIndex,
    }));
  }

  // First occurrence wins: the earliest row carrying a given trace id is that
  // trace's "owner" for nesting purposes. Rows with no trace id are skipped —
  // they can't be a dispatcher.
  const firstIndexByTraceId = new Map<string, number>();
  rows.forEach((row, index) => {
    if (row.traceId && !firstIndexByTraceId.has(row.traceId)) {
      firstIndexByTraceId.set(row.traceId, index);
    }
  });

  const parentIndexOf: (number | null)[] = rows.map((row, index) => {
    if (!row.dispatchingTraceId || row.dispatchingTraceId === row.traceId) {
      return null;
    }
    const parentIndex = firstIndexByTraceId.get(row.dispatchingTraceId);
    if (parentIndex === undefined || parentIndex >= index) {
      return null;
    }
    return parentIndex;
  });

  const childIndicesByParentIndex = new Map<number, number[]>();
  parentIndexOf.forEach((parentIndex, index) => {
    if (parentIndex === null) {
      return;
    }
    const siblings = childIndicesByParentIndex.get(parentIndex) ?? [];
    siblings.push(index);
    childIndicesByParentIndex.set(parentIndex, siblings);
  });

  const emittedAsChild = new Set(
    parentIndexOf.flatMap((parentIndex, index) => (parentIndex === null ? [] : [index])),
  );

  const result: NestedPromptRow[] = [];
  const emit = (index: number, depth: number, railBelow: boolean[]) => {
    result.push({ row: rows[index], depth, railBelow, originalIndex: index });
    const children = childIndicesByParentIndex.get(index) ?? [];
    children.forEach((childIndex, childPosition) => {
      const isLastChild = childPosition === children.length - 1;
      emit(childIndex, depth + 1, [...railBelow, !isLastChild]);
    });
  };

  rows.forEach((_row, index) => {
    if (!emittedAsChild.has(index)) {
      emit(index, 0, []);
    }
  });

  return result;
};

export type WindowBoundaryLabel = 'selected window starts' | 'selected window ends';

/**
 * A "selected window starts/ends" divider is only meaningful between two
 * adjacent TOP-LEVEL turns — a nested child was moved out of its natural
 * chronological slot to sit under its dispatcher (see `nestPromptRows`
 * above), so it no longer marks a real crossing point; it still gets the
 * dimming (opacity), just no divider. Returns a lookup from a row's
 * `originalIndex` to the divider label that should render immediately above
 * it. Pulled out as its own pure function (rather than a mutable variable
 * threaded through the render loop) because a `let` reassigned across a JSX
 * `.map` trips the React Compiler's immutability lint rule.
 */
export const windowBoundariesByOriginalIndex = (
  nestedRows: NestedPromptRow[],
  windowStartMs: number | undefined,
  windowEndMs: number | undefined,
): Map<number, WindowBoundaryLabel> => {
  const boundaries = new Map<number, WindowBoundaryLabel>();
  let previousTopLevelInWindow: boolean | null = null;
  for (const { row, depth, originalIndex } of nestedRows) {
    if (depth !== 0) {
      continue;
    }
    const turnMs = row.timestamp ? new Date(row.timestamp).getTime() : NaN;
    const inWindow =
      windowStartMs == null || windowEndMs == null || Number.isNaN(turnMs)
        ? true
        : turnMs >= windowStartMs && turnMs <= windowEndMs;
    if (previousTopLevelInWindow === false && inWindow) {
      boundaries.set(originalIndex, 'selected window starts');
    } else if (previousTopLevelInWindow === true && !inWindow) {
      boundaries.set(originalIndex, 'selected window ends');
    }
    previousTopLevelInWindow = inWindow;
  }
  return boundaries;
};
