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
import { describe, expect, it } from 'vitest';
import type { SpanRow } from '../../../../api';
import { summarizeTraceWork } from './summarizeTraceWork';

let nextId = 1;

const span = (overrides: Partial<SpanRow> & { name: string }): SpanRow => {
  const id = nextId;
  nextId += 1;
  return {
    id,
    spanId: `span-${id}`,
    parentSpanId: null,
    traceId: 'trace-0102',
    kind: null,
    startTimestamp: '2026-08-30T10:00:00.000Z',
    endTimestamp: '2026-08-30T10:00:01.000Z',
    durationNanos: 1_000_000_000,
    statusCode: 'ok',
    statusMessage: null,
    scopeName: 'claude_code',
    attributes: null,
    events: null,
    resourceAttributes: null,
    ...overrides,
  };
};

describe('summarizeTraceWork', () => {
  it('returns all-zero, empty breakdown for an empty trace', () => {
    expect(summarizeTraceWork([])).toEqual({
      toolCalls: 0,
      modelCalls: 0,
      durationMs: 0,
      tools: [],
      models: [],
      files: [],
    });
  });

  it('counts tool calls and model calls the same way the header KPIs do', () => {
    const spans = [
      span({
        name: 'claude_code.tool',
        attributes: { tool_name: 'Read', file_path: 'TracesPageView.tsx' },
      }),
      span({
        name: 'claude_code.tool.execution',
        attributes: { tool_name: 'Read' },
      }),
      span({
        name: 'claude_code.llm_request',
        attributes: { model: 'claude-sonnet-4', input_tokens: 100, output_tokens: 20 },
      }),
    ];

    const result = summarizeTraceWork(spans);

    // The tool.execution sub-span is not itself a call — isToolCallSpan excludes it, same
    // as the waterfall's Collapse-all and the header's Tool calls tile.
    expect(result.toolCalls).toBe(1);
    expect(result.modelCalls).toBe(1);
  });

  it('gives a repeated tool name a count badge but a name seen once none', () => {
    const spans = [
      span({ name: 'claude_code.tool', attributes: { tool_name: 'Read', file_path: 'a.ts' } }),
      span({ name: 'claude_code.tool', attributes: { tool_name: 'Read', file_path: 'b.ts' } }),
      span({ name: 'claude_code.tool', attributes: { tool_name: 'Edit', file_path: 'a.ts' } }),
    ];

    const result = summarizeTraceWork(spans);

    expect(result.tools).toEqual([
      { name: 'Read', count: 2, kind: 'READ' },
      { name: 'Edit', count: 1, kind: 'EDIT' },
    ]);
  });

  it('classifies each tool by the same READ/EDIT/SEARCH/VERIFY rules the Insights phase timeline uses', () => {
    const spans = [
      span({ name: 'claude_code.tool', attributes: { tool_name: 'Grep' } }),
      span({
        name: 'claude_code.tool',
        attributes: { tool_name: 'Bash', command: 'git status' },
      }),
      span({ name: 'claude_code.tool', attributes: { tool_name: 'TodoWrite' } }),
    ];

    const result = summarizeTraceWork(spans);

    expect(result.tools).toEqual([
      { name: 'Grep', count: 1, kind: 'SEARCH' },
      { name: 'Bash', count: 1, kind: 'VERIFY' },
      { name: 'TodoWrite', count: 1, kind: 'OTHER' },
    ]);
  });

  it('gives a repeated model name a count badge but a name seen once none', () => {
    const spans = [
      span({
        name: 'claude_code.llm_request',
        attributes: { model: 'claude-sonnet-4', input_tokens: 10 },
      }),
      span({
        name: 'claude_code.llm_request',
        attributes: { model: 'claude-sonnet-4', input_tokens: 10 },
      }),
      span({
        name: 'claude_code.llm_request',
        attributes: { model: 'claude-opus-4', input_tokens: 10 },
      }),
      // A model span with no token attributes at all is not a model call.
      span({ name: 'claude_code.llm_request', attributes: { model: 'claude-haiku-4' } }),
    ];

    const result = summarizeTraceWork(spans);

    expect(result.modelCalls).toBe(3);
    expect(result.models).toEqual([
      { name: 'Sonnet 4', count: 2 },
      { name: 'Opus 4', count: 1 },
    ]);
  });

  it('lists every distinct tool that touched a file, in first-seen order, with its own per-file count', () => {
    const spans = [
      span({
        name: 'claude_code.tool',
        attributes: { tool_name: 'Read', file_path: 'TracesPageView.tsx' },
      }),
      span({
        name: 'claude_code.tool',
        attributes: { tool_name: 'Edit', file_path: 'TracesPageView.tsx' },
      }),
      // Reading the same file again must bump Read's count on it, not duplicate the chip.
      span({
        name: 'claude_code.tool',
        attributes: { tool_name: 'Read', file_path: 'TracesPageView.tsx' },
      }),
      span({
        name: 'claude_code.tool',
        attributes: { tool_name: 'Read', file_path: 'AGENTS.md' },
      }),
    ];

    const result = summarizeTraceWork(spans);

    expect(result.files).toEqual([
      {
        path: 'TracesPageView.tsx',
        tools: [
          { name: 'Read', count: 2 },
          { name: 'Edit', count: 1 },
        ],
      },
      { path: 'AGENTS.md', tools: [{ name: 'Read', count: 1 }] },
    ]);
  });

  it('computes duration from the earliest span start to the latest span end', () => {
    const spans = [
      span({
        name: 'claude_code.interaction',
        startTimestamp: '2026-08-30T10:00:00.000Z',
        durationNanos: 5_000_000_000,
      }),
      span({
        name: 'claude_code.tool',
        startTimestamp: '2026-08-30T10:00:01.000Z',
        durationNanos: 500_000_000,
        attributes: { tool_name: 'Bash' },
      }),
    ];

    expect(summarizeTraceWork(spans).durationMs).toBe(5000);
  });

  it('ignores a tool span with no tool_name attribute rather than crashing on it', () => {
    const spans = [span({ name: 'claude_code.tool', attributes: null })];

    const result = summarizeTraceWork(spans);

    expect(result.toolCalls).toBe(1);
    expect(result.tools).toEqual([]);
    expect(result.files).toEqual([]);
  });
});
