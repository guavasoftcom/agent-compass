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
// The related calls the drawer shows for a span, and the rule for whether a row is a call at all.
// Every relation carries the span id of the call it names, which is what lets the rendered call
// number link to that row.
import { describe, expect, it } from 'vitest';
import type { SpanRow } from '../../api';
import { buildSpanRelations, sortedToolCallsOf } from './spanRelations';

// Test-only helper matching the way a real caller derives the sorted tool-call list once per
// trace (see CallContextSection.tsx) before passing it into buildSpanRelations.
const relationsFor = (selectedSpan: SpanRow, spans: SpanRow[]) =>
  buildSpanRelations(selectedSpan, spans, sortedToolCallsOf(spans));

const BASE_MS = Date.parse('2026-09-05T12:00:00.000Z');

const toolCall = (
  callNumber: number,
  toolName: string,
  attributes: Record<string, unknown> = {},
  durationMs = 100,
): SpanRow =>
  ({
    spanId: `span-${callNumber}`,
    name: 'claude_code.tool',
    callNumber,
    startTimestamp: new Date(BASE_MS + callNumber * 1000).toISOString(),
    endTimestamp: new Date(BASE_MS + callNumber * 1000 + durationMs).toISOString(),
    durationNanos: durationMs * 1e6,
    statusCode: 'ok',
    attributes: { tool_name: toolName, ...attributes },
  }) as unknown as SpanRow;

const llmRequest = (offsetSeconds: number, durationMs: number): SpanRow =>
  ({
    spanId: `llm-${offsetSeconds}`,
    name: 'claude_code.llm_request',
    startTimestamp: new Date(BASE_MS + offsetSeconds * 1000 - durationMs).toISOString(),
    endTimestamp: new Date(BASE_MS + offsetSeconds * 1000).toISOString(),
    durationNanos: durationMs * 1e6,
    attributes: { model: 'claude-opus-5' },
  }) as unknown as SpanRow;

const subSpan = (parentCallNumber: number, name: string): SpanRow =>
  ({
    spanId: `sub-${parentCallNumber}`,
    parentSpanId: `span-${parentCallNumber}`,
    name,
    startTimestamp: new Date(BASE_MS + parentCallNumber * 1000).toISOString(),
    endTimestamp: new Date(BASE_MS + parentCallNumber * 1000 + 50).toISOString(),
    durationNanos: 50 * 1e6,
    attributes: {},
  }) as unknown as SpanRow;

describe('buildSpanRelations', () => {
  it('finds a read of the same file far earlier in the trace, and states the distance', () => {
    const read = toolCall(1, 'Read', { file_path: 'backend/LogService.java' });
    const filler = Array.from({ length: 14 }, (_unused, index) =>
      toolCall(index + 2, 'Grep', { pattern: 'x' }));
    const edit = toolCall(16, 'Edit', { file_path: 'backend/LogService.java' });

    const relations = relationsFor(edit, [read, ...filler, edit]);

    expect(relations?.sameFileEarlier).toEqual([
      { spanId: 'span-1', callNumber: 1, toolName: 'Read', filePath: 'backend/LogService.java', callsAway: 15 },
    ]);
  });

  it('reports a later touch of the same file too, so a read can be told it was acted on', () => {
    const read = toolCall(1, 'Read', { file_path: 'frontend/api.ts' });
    const edit = toolCall(2, 'Edit', { file_path: 'frontend/api.ts' });

    const relations = relationsFor(read, [read, edit]);

    expect(relations?.sameFileLater).toEqual([
      { spanId: 'span-2', callNumber: 2, toolName: 'Edit', filePath: 'frontend/api.ts', callsAway: 1 },
    ]);
    expect(relations?.sameFileEarlier).toEqual([]);
  });

  it('resolves a tool call sub-span to its parent, so all three waterfall rows agree', () => {
    const read = toolCall(1, 'Read', { file_path: 'a.ts' });
    const edit = toolCall(2, 'Edit', { file_path: 'a.ts' });
    const execution = subSpan(2, 'claude_code.tool.execution');

    const relations = relationsFor(execution, [read, edit, execution]);

    expect(relations?.sameFileEarlier).toEqual([
      { spanId: 'span-1', callNumber: 1, toolName: 'Read', filePath: 'a.ts', callsAway: 1 },
    ]);
  });

  it('returns null for a span that is not a tool call', () => {
    const generation = llmRequest(1, 500);

    expect(relationsFor(generation, [generation])).toBeNull();
  });
});
