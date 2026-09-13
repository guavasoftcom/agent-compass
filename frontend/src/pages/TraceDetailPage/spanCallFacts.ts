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
// What is already KNOWN about one tool call, with no model involved.
//
// The drawer's call-context section once showed only the related calls, which meant a call with no
// relations -- a Bash command, the first call in a trace -- rendered a header and nothing else,
// while the facts that actually answer "what was this call for" went to a model and nowhere near
// the reader. Trace d71d261cfdd9afe118836b20f660f482's call 2 is the case in point: a `sed` with no
// file_path and no earlier call to relate to, whose own tool_result log carried the agent's stated
// intent, "Verify section placement in drawer".
//
// The best of these facts is the DESCRIPTION, and it is the one nothing else on the page surfaces.
// It lives inside the tool_result log's `tool_input` JSON string, so the drawer's Logs section
// shows it only as part of an opaque blob. It is also the agent's own account of its intent, which
// is precisely the question the section asks.
//
// WHY THE LOGS ARE REACHABLE FROM HERE AT ALL. A tool log is stamped by Claude Code with the coarse
// interaction-root span id, not with the call it describes -- but LogService#resolveLeafSpans
// already re-points it onto the exact leaf span by tool_use_id before the trace detail page ever
// sees it, so logsBySpanId has them correctly bucketed. The buckets sit on the tool call's CHILDREN
// (tool_result on the execution span, tool_decision on the approval-wait span), never on the
// wrapper, which is why this scans the children rather than the subject's own bucket. Every match
// is re-checked against the call's own tool_use_id rather than trusted from the bucket alone.
import type { LogRow, SpanRow } from '../../api';
import { resolveToolCall } from './spanRelations';

const TOOL_RESULT_EVENT_NAME = 'tool_result';
const TOOL_DECISION_EVENT_NAME = 'tool_decision';
const EVENT_NAME_ATTRIBUTE = 'event.name';
const TOOL_USE_ID_ATTRIBUTE = 'tool_use_id';

// A description is a one-line intent, not a paragraph; anything longer is a misuse of the field and
// would push the relations off screen.
const MAXIMUM_DESCRIPTION_LENGTH = 200;

export interface SpanCallFacts {
  // The agent's own stated intent for this call, from tool_input.description.
  description: string | null;
  // null when no tool_result log resolved -- "not recorded" is not "succeeded".
  succeeded: boolean | null;
  errorText: string | null;
  // 'accept' / 'reject', and where the decision came from ('config' = pre-authorized, so nobody was
  // interrupted; a user_* source means the reader was actually asked).
  decision: string | null;
  decisionSource: string | null;
  resultSizeBytes: number | null;
}

const stringAttribute = (
  attributes: Record<string, unknown> | null | undefined,
  key: string,
): string | null => {
  const value = attributes?.[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
};

const numberAttribute = (
  attributes: Record<string, unknown> | null | undefined,
  key: string,
): number | null => {
  const value = attributes?.[key];
  if (typeof value === 'number') {
    return value;
  }
  // Claude Code sends some counters as JSON strings; a non-numeric one is not a size.
  const parsed = typeof value === 'string' ? Number(value) : Number.NaN;
  return Number.isFinite(parsed) ? parsed : null;
};

// tool_input is a JSON *string*, and a malformed or truncated one is a normal outcome rather than
// an error -- Claude Code truncates long values inside it before export.
const descriptionFrom = (toolResult: LogRow | undefined): string | null => {
  const rawInput = stringAttribute(toolResult?.attributes, 'tool_input');
  if (rawInput === null) {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(rawInput);
    if (parsed === null || typeof parsed !== 'object') {
      return null;
    }
    const description = (parsed as Record<string, unknown>).description;
    if (typeof description !== 'string' || description.length === 0) {
      return null;
    }
    return description.length > MAXIMUM_DESCRIPTION_LENGTH
      ? `${description.slice(0, MAXIMUM_DESCRIPTION_LENGTH)}…`
      : description;
  } catch {
    return null;
  }
};

const logsForCall = (
  toolCall: SpanRow,
  spans: SpanRow[],
  logsBySpanId: Map<string, LogRow[]>,
): LogRow[] => {
  const spanIds = [
    toolCall.spanId,
    ...spans.filter((span) => span.parentSpanId === toolCall.spanId).map((span) => span.spanId),
  ];
  return spanIds.flatMap((spanId) => logsBySpanId.get(spanId) ?? []);
};

const findLog = (logs: LogRow[], eventName: string, toolUseId: string | null): LogRow | undefined =>
  logs.find(
    (log) =>
      stringAttribute(log.attributes, EVENT_NAME_ATTRIBUTE) === eventName
      && (toolUseId === null
        || stringAttribute(log.attributes, TOOL_USE_ID_ATTRIBUTE) === toolUseId),
  );

/**
 * Facts about the tool call the given span belongs to, or null when the span is not a tool call.
 * Every field is independently nullable: a call whose exporter recorded no tool_use_id (3,979 of
 * 28,659 measured tool spans) still resolves, it just has less to say.
 */
export const buildSpanCallFacts = (
  selectedSpan: SpanRow,
  spans: SpanRow[],
  logsBySpanId: Map<string, LogRow[]>,
): SpanCallFacts | null => {
  const toolCall = resolveToolCall(selectedSpan, spans);
  if (toolCall === null) {
    return null;
  }
  const toolUseId = stringAttribute(toolCall.attributes, TOOL_USE_ID_ATTRIBUTE);
  const logs = logsForCall(toolCall, spans, logsBySpanId);
  const toolResult = findLog(logs, TOOL_RESULT_EVENT_NAME, toolUseId);
  const toolDecision = findLog(logs, TOOL_DECISION_EVENT_NAME, toolUseId);

  const success = toolResult?.attributes?.success;
  return {
    description: descriptionFrom(toolResult),
    succeeded: typeof success === 'boolean' ? success : null,
    errorText: stringAttribute(toolResult?.attributes, 'error'),
    decision: stringAttribute(toolDecision?.attributes, 'decision'),
    decisionSource: stringAttribute(toolDecision?.attributes, 'source'),
    resultSizeBytes: numberAttribute(toolResult?.attributes, 'tool_result_size_bytes'),
  };
};

/** True when there is at least one fact worth rendering — otherwise the section shows nothing. */
export const hasAnyFact = (facts: SpanCallFacts): boolean =>
  facts.description !== null
  || facts.succeeded !== null
  || facts.errorText !== null
  || facts.decision !== null
  || facts.resultSizeBytes !== null;
