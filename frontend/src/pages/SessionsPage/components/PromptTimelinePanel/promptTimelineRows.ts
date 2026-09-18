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
import { nestDispatchedRows, type NestedDispatchedRow } from '../../../../lib/nestDispatchedRows';

// A turn positioned in the nested (indented) render order — see
// `NestedDispatchedRow` (`lib/nestDispatchedRows.ts`) for what `depth`,
// `railBelow`, and `originalIndex` mean. Kept as a named alias since this
// panel's render loop and its tests already spell out `NestedPromptRow`.
export type NestedPromptRow = NestedDispatchedRow<SessionPromptRow>;

/**
 * Groups a session's prompt-timeline turns so a background-dispatched
 * subagent's turn nests directly beneath the turn that dispatched it, instead
 * of rendering as an unrelated card further down the timeline. Thin wrapper
 * around the shared `nestDispatchedRows` core (`lib/nestDispatchedRows.ts`) —
 * see that module's doc comment for the full reordering/depth/edge-case
 * contract, identical here. Same algorithm as `TraceDetailPage`'s
 * `nestSwitchTraceRows` (`switchTraceRows.ts`), adapted for the full,
 * unfiltered turn list this panel renders (a turn's `traceId`/`prompt` can be
 * null here — pre-tracing sessions and capture-disabled prompts are kept, not
 * filtered) — `switchTraceRows.ts` operates on rows pre-filtered to a
 * non-null `traceId`/`prompt`, which is the only reason the two aren't the
 * same call site.
 */
export const nestPromptRows = (rows: SessionPromptRow[]): NestedPromptRow[] =>
  nestDispatchedRows(rows, {
    traceIdOf: (row) => row.traceId,
    dispatchingTraceIdOf: (row) => row.dispatchingTraceId,
  });

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
