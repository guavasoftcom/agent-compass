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

// Client-side "what actually happened" breakdown for the Analyze trace dialog's summary card
// (TraceSummaryCard, in AnalyzeTraceDialogView.tsx). Every number here is derived from the spans
// already loaded by TraceDetailPage — nothing here is sent to the backend or to Ollama, and this
// module intentionally has no knowledge of either: it is pure, no React, same idiom as
// callCitations.ts in this same folder.
//
// Every rule below is borrowed, not reinvented, so this card can never disagree with the rest of
// the page about what counts as a tool call or a model call:
//   - "tool call" is isToolCallSpan(span.name) — the same rule the header's Tool calls KPI and
//     WaterfallToolbar's Collapse-all use (see TraceDetailPage/CLAUDE.md's "Collapse and expand").
//   - "model call" is tokenBreakdownForSpan(span).total > 0 — the same one-pass rule
//     TraceDetailHeader's modelCallCount uses.
//   - each tool's chip color is classifyToolCall(span) (../../traceInsightsDerivations) — the same
//     READ/EDIT/SEARCH/VERIFY/OTHER taxonomy AnalyzeTraceDialogView.tsx colors its own chips by.
// Cost is deliberately NOT part of this module's return shape: TraceDetailPage/CLAUDE.md's "Cost"
// section documents, with real bug history, why a client-side sum of per-span cost must never be
// presented as a trace's total. The dialog's Cost section reads the backend-authoritative
// traceCostUsd directly and only uses costOfSelectedSpan for the (explicitly non-total) per-call
// breakdown — see AnalyzeTraceDialogView.tsx.
import type { SpanRow } from '../../../../api';
import { shortModelName } from '../../../../lib/format';
import { computeTraceWindow } from '../../spanTree';
import { tokenBreakdownForSpan } from '../../../TracesPage/tokenBreakdown';
import { isToolCallSpan } from '../../../TracesPage/traceDerivations';
import { classifyToolCall, type PhaseKind } from '../../traceInsightsDerivations';

// Where a tool call's name and the file it touched live, when it touched one. Same keys
// SpanWaterfallRow/SpanToolBadge already read off span.attributes — see that component's
// TOOL_ARG_KEYS and its tool_name read.
const TOOL_NAME_ATTRIBUTE = 'tool_name';
const FILE_PATH_ATTRIBUTE = 'file_path';

// The model attribute a `claude_code.llm_request` span carries. `gen_ai.request.model` is the
// OTel-canonical key outliving the vendor-specific `model` one; SpanWaterfallRow's own model
// badge reads the same two keys in the same order.
const MODEL_ATTRIBUTE_KEYS = ['model', 'gen_ai.request.model'];

export interface TraceWorkNameCount {
  name: string;
  count: number;
}

export interface TraceWorkToolCount extends TraceWorkNameCount {
  // classifyToolCall's read of the tool's first-seen call — the same taxonomy
  // AnalyzeTraceDialogView.tsx's chip coloring uses, so a Read/Edit/Search/Bash chip here
  // takes the identical hue it would there. First-seen rather than a per-call breakdown: the
  // chip is one per tool NAME, and a name with mixed classifications across calls (a "Bash"
  // that ran both `git status` and `cat file`) still needs a single color.
  kind: PhaseKind;
}

export interface TraceWorkFileTouch {
  path: string;
  // Distinct tools that touched this file, in first-seen order, each carrying how many times
  // that tool touched this specific file (not the tool's trace-wide count from `tools` above).
  tools: TraceWorkNameCount[];
}

export interface TraceWorkSummary {
  toolCalls: number;
  modelCalls: number;
  durationMs: number;
  // One entry per distinct tool name, in first-seen order.
  tools: TraceWorkToolCount[];
  // One entry per distinct (short) model name, in first-seen order.
  models: TraceWorkNameCount[];
  files: TraceWorkFileTouch[];
}

const stringAttribute = (
  attributes: Record<string, unknown> | null | undefined,
  key: string,
): string | null => {
  const value = attributes?.[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
};

const modelNameOf = (span: SpanRow): string | null => {
  for (const attributeKey of MODEL_ATTRIBUTE_KEYS) {
    const rawModelName = stringAttribute(span.attributes, attributeKey);
    if (rawModelName) {
      return shortModelName(rawModelName);
    }
  }
  return null;
};

// Increments a name's count in a Map while preserving first-seen insertion order — Map
// iteration order is insertion order, so the Tools/Models chip rows read in the order those
// names first appeared in the trace rather than needing a separate sort.
const bumpCount = (counts: Map<string, number>, name: string): void => {
  counts.set(name, (counts.get(name) ?? 0) + 1);
};

/**
 * Computes the Work/Tools/Models/Files breakdown the summary card's supplementary sections show,
 * purely from the spans this page already has in memory.
 *
 * `durationMs` is the trace's own wall-clock span (earliest span start to latest span end),
 * reusing `computeTraceWindow` from `spanTree.ts` rather than a second min/max pass — the same
 * function the waterfall's zoom window is computed from. Guarded to 0 for an empty trace rather
 * than `computeTraceWindow`'s own 1ms floor (which exists to keep a zoom window non-degenerate,
 * not to describe a duration).
 */
export const summarizeTraceWork = (spans: SpanRow[]): TraceWorkSummary => {
  let toolCalls = 0;
  let modelCalls = 0;
  const toolCounts = new Map<string, number>();
  const toolKinds = new Map<string, PhaseKind>();
  const modelCounts = new Map<string, number>();
  const toolCountsByFilePath = new Map<string, Map<string, number>>();

  spans.forEach((span) => {
    if (isToolCallSpan(span.name)) {
      toolCalls += 1;
      const toolName = stringAttribute(span.attributes, TOOL_NAME_ATTRIBUTE);
      if (toolName) {
        bumpCount(toolCounts, toolName);
        if (!toolKinds.has(toolName)) {
          toolKinds.set(toolName, classifyToolCall(toolName));
        }
        const filePath = stringAttribute(span.attributes, FILE_PATH_ATTRIBUTE);
        if (filePath) {
          const toolCountsForFile = toolCountsByFilePath.get(filePath) ?? new Map<string, number>();
          bumpCount(toolCountsForFile, toolName);
          toolCountsByFilePath.set(filePath, toolCountsForFile);
        }
      }
    }
    if (tokenBreakdownForSpan(span).total > 0) {
      modelCalls += 1;
      const modelName = modelNameOf(span);
      if (modelName) {
        bumpCount(modelCounts, modelName);
      }
    }
  });

  return {
    toolCalls,
    modelCalls,
    durationMs: spans.length === 0 ? 0 : computeTraceWindow(spans).totalMs,
    tools: [...toolCounts.entries()].map(([name, count]) => ({
      name,
      count,
      kind: toolKinds.get(name) ?? 'OTHER',
    })),
    models: [...modelCounts.entries()].map(([name, count]) => ({ name, count })),
    files: [...toolCountsByFilePath.entries()].map(([path, toolCountsForFile]) => ({
      path,
      tools: [...toolCountsForFile.entries()].map(([name, count]) => ({ name, count })),
    })),
  };
};
