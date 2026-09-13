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
// Fixtures reproduce trace d71d261cfdd9afe118836b20f660f482's call 2 -- the Bash `sed` whose
// blocked_on_user sub-span is 544c5f5a2d7e7d3c. It is the case this module was written for: no
// file_path and no earlier call, so the section had nothing computed to show, while the call's own
// tool_result carried the agent's stated intent all along.
import { describe, expect, it } from 'vitest';
import type { LogRow, SpanRow } from '../../api';
import { buildSpanCallFacts, hasAnyFact } from './spanCallFacts';

const TOOL_USE_ID = 'toolu_015Ycvk2Vu8DpxX75AUpYaDc';

const toolCall: SpanRow = {
  spanId: 'tool-2',
  name: 'claude_code.tool',
  callNumber: 2,
  startTimestamp: '2026-09-06T02:47:28.383Z',
  endTimestamp: '2026-09-06T02:47:28.418Z',
  durationNanos: 36e6,
  attributes: { tool_name: 'Bash', full_command: 'sed -n 190,215p SpanInspectorDrawer.tsx',
    tool_use_id: TOOL_USE_ID },
} as unknown as SpanRow;

const executionSpan: SpanRow = {
  spanId: 'exec-2',
  parentSpanId: 'tool-2',
  name: 'claude_code.tool.execution',
  startTimestamp: toolCall.startTimestamp,
  endTimestamp: toolCall.endTimestamp,
  durationNanos: 36e6,
  attributes: {},
} as unknown as SpanRow;

const blockedSpan: SpanRow = {
  spanId: '544c5f5a2d7e7d3c',
  parentSpanId: 'tool-2',
  name: 'claude_code.tool.blocked_on_user',
  startTimestamp: toolCall.startTimestamp,
  endTimestamp: toolCall.endTimestamp,
  durationNanos: 9e6,
  attributes: {},
} as unknown as SpanRow;

const spans = [toolCall, executionSpan, blockedSpan];

const logRow = (attributes: Record<string, unknown>): LogRow =>
  ({ id: 1, attributes } as unknown as LogRow);

const toolResultLog = logRow({
  'event.name': 'tool_result',
  tool_use_id: TOOL_USE_ID,
  tool_input: JSON.stringify({
    command: 'sed -n 190,215p SpanInspectorDrawer.tsx',
    description: 'Verify section placement in drawer',
  }),
  success: true,
  tool_result_size_bytes: 965,
});

const toolDecisionLog = logRow({
  'event.name': 'tool_decision',
  tool_use_id: TOOL_USE_ID,
  decision: 'accept',
  source: 'config',
});

// The backend re-points tool logs onto the call's leaf spans, so they arrive bucketed on the
// CHILDREN -- tool_result on the execution span, tool_decision on the approval-wait span -- and
// never on the wrapper the section resolves to.
const bucketedLogs = new Map<string, LogRow[]>([
  ['exec-2', [toolResultLog]],
  ['544c5f5a2d7e7d3c', [toolDecisionLog]],
]);

describe('buildSpanCallFacts', () => {
  it('reads the agent’s own stated intent out of the tool_result log’s tool_input JSON', () => {
    const facts = buildSpanCallFacts(toolCall, spans, bucketedLogs);

    expect(facts?.description).toBe('Verify section placement in drawer');
  });

  it('finds those logs from the sub-span too, since all three rows are one call', () => {
    const facts = buildSpanCallFacts(blockedSpan, spans, bucketedLogs);

    expect(facts?.description).toBe('Verify section placement in drawer');
    expect(facts?.succeeded).toBe(true);
    expect(facts?.resultSizeBytes).toBe(965);
  });

  it('names the permission decision and whether anyone was actually interrupted', () => {
    const facts = buildSpanCallFacts(toolCall, spans, bucketedLogs);

    expect(facts?.decision).toBe('accept');
    expect(facts?.decisionSource).toBe('config');
  });

  // The bucket is the backend's correlation, but a call's own id is the authority -- a log that
  // landed in the right bucket for the wrong call must not be reported as this call's outcome.
  it('ignores a log whose tool_use_id belongs to a different call', () => {
    const strayLogs = new Map<string, LogRow[]>([
      ['exec-2', [logRow({
        'event.name': 'tool_result',
        tool_use_id: 'toolu_someoneElse',
        tool_input: JSON.stringify({ description: 'A different call entirely' }),
        success: false,
      })]],
    ]);

    const facts = buildSpanCallFacts(toolCall, spans, strayLogs);

    expect(facts?.description).toBeNull();
    expect(facts?.succeeded).toBeNull();
  });

  it('reports a failure with its error text', () => {
    const failedLogs = new Map<string, LogRow[]>([
      ['exec-2', [logRow({
        'event.name': 'tool_result',
        tool_use_id: TOOL_USE_ID,
        success: false,
        error: 'exit status 1',
      })]],
    ]);

    const facts = buildSpanCallFacts(toolCall, spans, failedLogs);

    expect(facts?.succeeded).toBe(false);
    expect(facts?.errorText).toBe('exit status 1');
  });

  // Claude Code truncates long values inside tool_input before export, so a cut-off JSON string is
  // a normal outcome and must not throw.
  it('survives a truncated or malformed tool_input rather than throwing', () => {
    const truncatedLogs = new Map<string, LogRow[]>([
      ['exec-2', [logRow({
        'event.name': 'tool_result',
        tool_use_id: TOOL_USE_ID,
        tool_input: '{"command":"sed -n 190,215p …[1352 chars]',
        success: true,
      })]],
    ]);

    const facts = buildSpanCallFacts(toolCall, spans, truncatedLogs);

    expect(facts?.description).toBeNull();
    expect(facts?.succeeded).toBe(true);
  });

  it('leaves every field null when no logs resolved, rather than inventing a success', () => {
    const facts = buildSpanCallFacts(toolCall, spans, new Map());

    expect(facts?.succeeded).toBeNull();
    expect(facts?.decision).toBeNull();
    expect(hasAnyFact(facts!)).toBe(false);
  });

  it('returns null for a span that is not a tool call', () => {
    const generation = {
      spanId: 'llm-1',
      name: 'claude_code.llm_request',
      startTimestamp: toolCall.startTimestamp,
      endTimestamp: toolCall.endTimestamp,
      durationNanos: 500e6,
      attributes: { model: 'claude-opus-5' },
    } as unknown as SpanRow;

    expect(buildSpanCallFacts(generation, [generation], bucketedLogs)).toBeNull();
  });
});
