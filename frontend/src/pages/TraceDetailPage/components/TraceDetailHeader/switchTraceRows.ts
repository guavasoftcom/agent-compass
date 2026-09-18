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
import { nestDispatchedRows } from '../../../../lib/nestDispatchedRows';

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
 * rendering as an unrelated flat row. Thin wrapper around the shared
 * `nestDispatchedRows` core (`lib/nestDispatchedRows.ts`) — see that
 * module's doc comment for the full reordering/depth/edge-case contract,
 * identical here (this modal's rows are pre-filtered to a non-null
 * `traceId`/`prompt` by `hasTraceAndPrompt`, which is the only difference
 * from `SessionsPage`'s `nestPromptRows`, the core's other caller). The
 * shared core also returns an `originalIndex` per row — this wrapper drops
 * it, since nothing here needs a lookup back into the pre-nesting array
 * order the way the prompt timeline's window-boundary dividers do.
 */
export const nestSwitchTraceRows = (rows: SwitchTraceRow[]): NestedSwitchTraceRow[] =>
  nestDispatchedRows(rows, {
    traceIdOf: (row) => row.traceId,
    dispatchingTraceIdOf: (row) => row.dispatchingTraceId,
  }).map(({ row, depth, railBelow }) => ({ row, depth, railBelow }));
