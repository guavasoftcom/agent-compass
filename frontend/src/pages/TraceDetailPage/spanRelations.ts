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
// The related calls the SpanInspectorDrawer shows for the selected span, and the rule for deciding
// whether a span has a "what was this call for" question at all.
//
// RELEVANCE-SELECTED, NOT POSITIONAL. The obvious design is a fixed window (this span, the one
// before, the one after). Measured against this project's own database that window is too narrow
// for the case it exists to serve: of 4,992 Edit/Write calls, 2,970 have an earlier Read of the
// same file in the same trace, but only 1,060 of those (36%) were read in the immediately
// preceding call — the median gap is 2 calls and the p90 gap is 15. A +/-1 window therefore
// misses roughly two thirds of read-then-edit pairs, and widening it to +/-15 would put thirty
// mostly-unrelated calls on screen to catch them.
//
import type { SpanRow } from '../../api';
import { isToolCallSpan } from '../TracesPage/traceDerivations';

// Cap on the file relation lists. Small on purpose: three prior touches of a file tell a reader as
// much as twelve, and the drawer has limited room.
const MAXIMUM_RELATED_BY_FILE = 3;

const stringAttribute = (
  attributes: Record<string, unknown> | null | undefined,
  key: string,
): string | null => {
  const value = attributes?.[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
};

// One related call, as the reader sees it: what it was, which file it touched, and -- the part a
// positional window cannot state -- how far away it is. "Read 15 calls earlier" is exactly the
// fact that makes a far-apart read/edit pair legible instead of looking like a blind edit.
export interface RelatedCall {
  // The row this relation points at. Carried so the rendered call number can be a link straight to
  // it -- a reader who sees "read 15 calls earlier" wants to go and look at that call, and the
  // waterfall row for it may be scrolled away, collapsed inside a subagent dispatch, or outside the
  // current zoom (all three of which TraceDetailPageView#revealSpan handles).
  spanId: string;
  callNumber: number | null;
  toolName: string;
  filePath: string | null;
  callsAway: number;
}

export interface SpanRelations {
  // Earlier/later calls against the same file_path, nearest first.
  sameFileEarlier: RelatedCall[];
  sameFileLater: RelatedCall[];
}

// The same rule the header's Tool calls tile, "Collapse all" and the phase timeline use, so this
// section agrees with the rest of the page about what a tool call is (and so the sample store's
// bare `tool.Read` / `mcp.connect` names work too, not just real Claude Code's `claude_code.tool`).
const isToolCall = (span: SpanRow): boolean =>
  isToolCallSpan(span.name) && stringAttribute(span.attributes, 'tool_name') !== null;

// The waterfall renders a tool call as THREE stacked rows -- the `claude_code.tool` wrapper and its
// `tool.execution` / `tool.blocked_on_user` sub-spans -- which look like one call to a reader and
// are one call in every way that matters here. Clicking any of the three resolves to the wrapper
// that carries tool_name, rather than the section silently rendering nothing on two rows out of
// three. The backend applies the identical resolution to the span id it is sent.
export const resolveToolCall = (span: SpanRow, spans: SpanRow[]): SpanRow | null => {
  if (isToolCall(span)) {
    return span;
  }
  const parent = spans.find((candidate) => candidate.spanId === span.parentSpanId);
  return parent !== undefined && isToolCall(parent) ? parent : null;
};

const startMsOf = (span: SpanRow): number => Date.parse(span.startTimestamp);

const toRelatedCall = (span: SpanRow, callsAway: number): RelatedCall => ({
  spanId: span.spanId,
  callNumber: span.callNumber ?? null,
  toolName: stringAttribute(span.attributes, 'tool_name') ?? 'unknown',
  filePath: stringAttribute(span.attributes, 'file_path'),
  callsAway,
});

/**
 * Every tool call in the trace, sorted by start time ascending. Identical for every span selection
 * within one trace, so a caller re-rendering `buildSpanRelations` per selection should memoize this
 * once on `spans` alone (see `CallContextSection.tsx`) instead of letting `buildSpanRelations`
 * re-filter and re-sort the whole trace on every selection.
 */
export const sortedToolCallsOf = (spans: SpanRow[]): SpanRow[] =>
  spans.filter(isToolCall).sort((left, right) => startMsOf(left) - startMsOf(right));

// One direction's worth of candidates (earlier or later), narrowed to the ones that touch
// `filePath`, nearest first, capped at MAXIMUM_RELATED_BY_FILE. `sameFileEarlier` and
// `sameFileLater` are the same pipeline over two different candidate lists.
const relatedByFile = (
  candidates: { span: SpanRow; callsAway: number }[],
  filePath: string,
): RelatedCall[] =>
  candidates
    .filter(({ span }) => stringAttribute(span.attributes, 'file_path') === filePath)
    .slice(0, MAXIMUM_RELATED_BY_FILE)
    .map(({ span, callsAway }) => toRelatedCall(span, callsAway));

/**
 * Finds the calls related to one span. `spans` is the whole trace -- the relations are found by
 * scanning it, so passing a filtered or zoomed subset would silently narrow what is found.
 * `sortedToolCalls` is every tool call in the trace sorted by start time (`sortedToolCallsOf`) --
 * callers that invoke this once per span selection should compute it once per trace instead of
 * passing a freshly filtered/sorted list every time.
 *
 * A `tool.execution` / `tool.blocked_on_user` sub-span resolves to its parent tool call, so all
 * three of the waterfall rows that make up one call show the same relations. Returns null only for
 * spans that are genuinely not a tool call -- an llm_request row or the interaction root -- where
 * there is no "what did this call do" question to ask.
 */
export const buildSpanRelations = (
  selectedSpan: SpanRow,
  spans: SpanRow[],
  sortedToolCalls: SpanRow[],
): SpanRelations | null => {
  const subject = resolveToolCall(selectedSpan, spans);
  if (subject === null) {
    return null;
  }

  const subjectIndex = sortedToolCalls.findIndex((span) => span.spanId === subject.spanId);
  if (subjectIndex < 0) {
    return null;
  }

  const filePath = stringAttribute(subject.attributes, 'file_path');

  const earlier = sortedToolCalls.slice(0, subjectIndex).reverse();
  const later = sortedToolCalls.slice(subjectIndex + 1);

  const earlierCandidates = earlier.map((span, offset) => ({ span, callsAway: offset + 1 }));
  const laterCandidates = later.map((span, offset) => ({ span, callsAway: offset + 1 }));

  const sameFileEarlier = filePath === null ? [] : relatedByFile(earlierCandidates, filePath);
  const sameFileLater = filePath === null ? [] : relatedByFile(laterCandidates, filePath);

  return { sameFileEarlier, sameFileLater };
};
