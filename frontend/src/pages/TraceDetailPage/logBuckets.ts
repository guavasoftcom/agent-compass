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
import type { LogRow, SpanRow } from '../../api';
import { isToolCallSpan } from '../TracesPage/traceDerivations';
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

const toolUseIdOf = (attributes: Record<string, unknown> | null): string | null => {
  const raw = attributes?.['tool_use_id'];
  return typeof raw === 'string' && raw !== '' ? raw : null;
};

// One entry per tool call: the wrapper `claude_code.tool` span's own id, plus
// every span id in its subtree (itself, `tool.execution`, `tool.blocked_on_user`,
// and anything nested further). `isToolCallSpan` is the same rule the header's
// Tool calls tile and Collapse-all use to pick out the wrapper span from its
// SDK sub-spans — reused here so "which span owns this tool call" can't drift
// from "which span the rest of the page treats as one call."
interface ToolCallSpanFamily {
  toolSpanId: string;
  subtreeSpanIds: Set<string>;
}

// Keyed by the wrapper span's own tool_use_id, so a log carrying that id can
// find its call. tool_use_id is unique per call on real data, so a duplicate
// wrapper span for one id is a non-issue in practice.
const collectToolCallFamilies = (tree: SpanTree): Map<string, ToolCallSpanFamily> => {
  const familyByToolUseId = new Map<string, ToolCallSpanFamily>();

  const collectSubtree = (span: SpanRow, into: Set<string>) => {
    into.add(span.spanId);
    for (const child of tree.childrenByParentId.get(span.spanId) ?? []) {
      collectSubtree(child, into);
    }
  };

  const visit = (span: SpanRow) => {
    const toolUseId = toolUseIdOf(span.attributes);
    if (isToolCallSpan(span.name) && toolUseId) {
      const subtreeSpanIds = new Set<string>();
      collectSubtree(span, subtreeSpanIds);
      familyByToolUseId.set(toolUseId, { toolSpanId: span.spanId, subtreeSpanIds });
    }
    for (const child of tree.childrenByParentId.get(span.spanId) ?? []) {
      visit(child);
    }
  };
  tree.roots.forEach(visit);

  return familyByToolUseId;
};

// Attaches each log to its emitting span by OTLP span_id. Claude Code >= 2.1.152
// stamps trace context (trace_id + span_id) onto every event log emitted inside
// an active span, so a log's span_id resolves directly to the span it belongs to
// for most event types. The rare log without a usable span_id (e.g. a
// session-level event with no active span) lands on the root span so nothing is
// dropped.
//
// **tool_decision and tool_result can land outside the tool call they're about**:
// Claude Code stamps them with whatever span was active at that instant, which
// is sometimes a sub-span genuinely worth keeping (a decision made while
// `claude_code.tool.blocked_on_user` — a real permission wait — was active is
// correctly that specific span, and stays there) and sometimes an ambient
// ancestor with no real connection to the call (the `claude_code.interaction`
// turn root, when the decision/result fired outside any of the tool's own
// spans) — the actual bug this correlation fixes. So the log's own span_id is
// trusted **whenever it already falls inside the tool call's own span family**
// (the wrapper `claude_code.tool` span plus its sub-spans); tool_use_id — which
// both event types carry, matching the tool_use_id on the wrapper span, an
// exact key, not a heuristic — only kicks in as a fallback, re-pointing the log
// at the wrapper span when its own span_id points somewhere outside that
// family entirely. It deliberately never redirects a log *into* a specific
// sub-span (`tool.execution` vs `tool.blocked_on_user`): nothing in the log
// itself says which phase it belongs to, and guessing wrong is worse than
// landing one level up on the wrapper.
//
// **hook_execution_start/complete (PreToolUse/PostToolUse) have no tool_use_id
// at all** — they carry a hook_name like "PreToolUse:Read" but no correlating
// id, so there's no exact way to attach a hook run to one specific tool call
// among several of the same name in a turn. They stay on whatever span their
// own span_id resolves to (in practice, the root) rather than guessing.
export const bucketLogsBySpan = (
  logs: LogRow[],
  tree: SpanTree,
  rootSpanId: string,
): Map<string, LogRow[]> => {
  const logsBySpanId = new Map<string, LogRow[]>();
  const spanIdsInTree = collectSpanIds(tree);
  const familyByToolUseId = collectToolCallFamilies(tree);
  const push = (spanId: string, log: LogRow) => {
    let bucket = logsBySpanId.get(spanId);
    if (!bucket) {
      bucket = [];
      logsBySpanId.set(spanId, bucket);
    }
    bucket.push(log);
  };

  for (const log of logs) {
    const ownSpanIdInTree = log.spanId && spanIdsInTree.has(log.spanId) ? log.spanId : null;
    const toolUseId = toolUseIdOf(log.attributes);
    const family = toolUseId ? familyByToolUseId.get(toolUseId) : undefined;
    // Already inside this call's own span family (including a sub-span like
    // blocked_on_user) — that's real, specific data; leave it alone.
    const alreadyCorrelated = ownSpanIdInTree && family?.subtreeSpanIds.has(ownSpanIdInTree);
    const target = alreadyCorrelated
      ? ownSpanIdInTree
      : (family?.toolSpanId ?? ownSpanIdInTree ?? rootSpanId);
    push(target, log);
  }

  for (const bucket of logsBySpanId.values()) {
    bucket.sort(compareLogs);
  }

  return logsBySpanId;
};
