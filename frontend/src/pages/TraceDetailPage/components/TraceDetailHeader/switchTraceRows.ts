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

// A SessionPromptRow known to carry both a trace and a prompt — the two
// fields SwitchTraceModal filters the raw timeline down to before this module
// (or the modal view) ever sees a row.
export type SwitchTraceRow = SessionPromptRow & {
  traceId: string;
  prompt: string;
};

// Rows without a trace (pre-tracing sessions) or a prompt (capture disabled)
// aren't traces a reader can jump to. Shared by SwitchTraceModal (the row
// list) and IdentityPill (which needs the same filter to count distinct
// traces before deciding whether switching is worth offering at all).
export const hasTraceAndPrompt = (
  row: SessionPromptRow,
): row is SwitchTraceRow => row.traceId !== null && row.prompt !== null;

// A SwitchTraceRow positioned in the nested (indented) render order:
// `depth` is how many ancestor dispatchers sit above it, and `railBelow[d]`
// records, for the ancestor chain, whether the ancestor at depth `d` still
// has a later sibling to come after this row — the renderer's cue for
// whether to draw a continuing vertical connector line at that depth or stop
// at the elbow. A row's own "am I the last child of my parent" is
// `!railBelow[depth - 1]`.
export interface NestedSwitchTraceRow {
  row: SwitchTraceRow;
  depth: number;
  railBelow: boolean[];
}

/**
 * Groups a session's switch-trace rows so a background-dispatched subagent's
 * trace nests directly beneath the turn that dispatched it, instead of
 * rendering as an unrelated flat row. Rows are REORDERED — a child is moved
 * to sit directly beneath its dispatcher — chronological order (the input
 * array's own order, which `fetchSessionPrompts` already returns
 * chronologically) wins wherever the two would conflict, since a connector
 * can only "point back" to an adjacent row.
 *
 * Nesting is recursive, not just one level: a chained dispatch (a subagent
 * that itself background-dispatches a grandchild) nests to depth 2 and
 * beyond. Row `i` is a child of row `p` iff `dispatchingTraceId` is
 * non-null, differs from the row's own `traceId` (no self-reference), and
 * resolves — via first-occurrence-wins trace-id lookup, since several turns
 * can legitimately share one trace id and the earliest is the "owner" for
 * nesting purposes — to an index `p` strictly earlier than `i`. That
 * strictly-earlier-index requirement is also what guarantees this recursion
 * terminates: parent-of is a DAG over array order, never a cycle.
 *
 * Every edge case below resolves to "stay top-level, in place" rather than
 * being dropped or throwing: a dispatcher trace absent from the row list
 * (filtered out earlier by `hasTraceAndPrompt`, or beyond a row cap), a
 * self-referencing `dispatchingTraceId`, a null `dispatchingTraceId` (the
 * overwhelming majority of rows), and a dispatcher that appears AFTER its
 * claimed child in array order.
 */
export const nestSwitchTraceRows = (rows: SwitchTraceRow[]): NestedSwitchTraceRow[] => {
  const hasAnyDispatch = rows.some((row) => Boolean(row.dispatchingTraceId));
  if (!hasAnyDispatch) {
    return rows.map((row) => ({ row, depth: 0, railBelow: [] }));
  }

  // First occurrence wins: the earliest row carrying a given trace id is
  // that trace's "owner" for nesting purposes.
  const firstIndexByTraceId = new Map<string, number>();
  rows.forEach((row, index) => {
    if (!firstIndexByTraceId.has(row.traceId)) {
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

  const result: NestedSwitchTraceRow[] = [];
  const emit = (index: number, depth: number, railBelow: boolean[]) => {
    result.push({ row: rows[index], depth, railBelow });
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
