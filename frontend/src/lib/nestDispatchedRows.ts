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

// A row positioned in the nested (indented) render order: `depth` is how many
// ancestor dispatchers sit above it, `railBelow[d]` records whether the
// ancestor at that depth still has a later sibling to come (the renderer's
// cue for a continuing connector line vs. a bare elbow), and `originalIndex`
// is the row's position in the chronological input array, kept because
// reordering means the array index can no longer serve as a stable React key
// or as a lookup key back into per-row derived data (e.g. window-boundary
// dividers). A caller with no use for `originalIndex` (the Switch-trace
// modal's rows, which need no such lookup) is free to drop it when mapping
// the result into its own row type.
export interface NestedDispatchedRow<Row> {
  row: Row;
  depth: number;
  railBelow: boolean[];
  originalIndex: number;
}

// How to read the two dispatch-correlation fields off a caller's own row
// shape, since `SessionPromptRow` and `SwitchTraceRow` don't share a common
// interface (the latter narrows `traceId`/`prompt` to non-null).
export interface DispatchedRowAccessors<Row> {
  traceIdOf: (row: Row) => string | null;
  dispatchingTraceIdOf: (row: Row) => string | null;
}

/**
 * Groups a chronological list of rows so a background-dispatched subagent's
 * row nests directly beneath the row that dispatched it, instead of
 * rendering as an unrelated entry further down the list. Rows are
 * REORDERED — a child is moved to sit directly beneath its dispatcher —
 * chronological order (the input array's own order) wins wherever the two
 * would conflict, since a connector can only "point back" to an adjacent
 * row.
 *
 * This is the shared core of two page-level algorithms that were previously
 * maintained as identical copies: `SessionsPage`'s `nestPromptRows`
 * (`promptTimelineRows.ts`, over the full, unfiltered turn list, where a
 * turn's `traceId`/`prompt` can be null) and `TraceDetailPage`'s
 * `nestSwitchTraceRows` (`switchTraceRows.ts`, over rows pre-filtered to a
 * non-null `traceId`/`prompt`). Both wrap this function, mapping their own
 * row shape in through `DispatchedRowAccessors` and back out through
 * whatever result shape their own callers already expect.
 *
 * Nesting is recursive: a chained dispatch nests to depth 2 and beyond. Row
 * `i` is a child of row `p` iff `dispatchingTraceIdOf(row_i)` is non-null,
 * differs from the row's own `traceIdOf(row_i)`, and resolves — via
 * first-occurrence-wins trace-id lookup (several rows can share one trace
 * id; the earliest is the "owner") — to an index `p` strictly earlier than
 * `i`. That strictly-earlier-index requirement is also what guarantees this
 * recursion terminates: parent-of is a DAG over array order, never a cycle.
 * A row whose own `traceIdOf` is null never becomes a nesting parent, since
 * there is nothing for a later `dispatchingTraceIdOf` to resolve to.
 *
 * Every edge case resolves to "stay top-level, in place" rather than being
 * dropped or throwing: a dispatcher trace absent from the row list, a
 * self-referencing `dispatchingTraceIdOf`, a null `dispatchingTraceIdOf`
 * (the overwhelming majority of rows), and a dispatcher appearing AFTER its
 * claimed child in array order.
 */
export const nestDispatchedRows = <Row>(
  rows: Row[],
  { traceIdOf, dispatchingTraceIdOf }: DispatchedRowAccessors<Row>,
): NestedDispatchedRow<Row>[] => {
  const hasAnyDispatch = rows.some((row) => Boolean(dispatchingTraceIdOf(row)));
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
    const traceId = traceIdOf(row);
    if (traceId && !firstIndexByTraceId.has(traceId)) {
      firstIndexByTraceId.set(traceId, index);
    }
  });

  const parentIndexOf: (number | null)[] = rows.map((row, index) => {
    const dispatchingTraceId = dispatchingTraceIdOf(row);
    if (!dispatchingTraceId || dispatchingTraceId === traceIdOf(row)) {
      return null;
    }
    const parentIndex = firstIndexByTraceId.get(dispatchingTraceId);
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

  const result: NestedDispatchedRow<Row>[] = [];
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
