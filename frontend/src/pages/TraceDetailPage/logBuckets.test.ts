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
import type { LogRow, SpanRow } from '../../api';
import { buildSpanTree } from './spanTree';
import { bucketLogsBySpan } from './logBuckets';

const span = (
  spanId: string,
  parentSpanId: string | null,
  name: string,
  attributes: Record<string, unknown> = {},
): SpanRow =>
  ({
    spanId,
    parentSpanId,
    name,
    startTimestamp: '2026-08-23T00:00:00.000Z',
    attributes,
  }) as SpanRow;

const log = (
  spanId: string | null,
  attributes: Record<string, unknown> = {},
  timestamp = '2026-08-23T00:00:00.000Z',
): LogRow =>
  ({
    id: Math.random(),
    timestamp,
    spanId,
    attributes,
  }) as unknown as LogRow;

describe('bucketLogsBySpan', () => {
  it('buckets a log by its own span_id when that span is in the tree', () => {
    const root = span('root', null, 'claude_code.interaction');
    const tree = buildSpanTree([root]);
    const result = bucketLogsBySpan([log('root', { 'event.name': 'user_prompt' })], tree, 'root');
    expect(result.get('root')).toHaveLength(1);
  });

  it('falls back to the root span for a log with no usable span_id', () => {
    const root = span('root', null, 'claude_code.interaction');
    const tree = buildSpanTree([root]);
    const result = bucketLogsBySpan([log(null, { 'event.name': 'user_prompt' })], tree, 'root');
    expect(result.get('root')).toHaveLength(1);
  });

  // The bug this exists for: Claude Code stamps tool_decision/tool_result with
  // whatever span was active when the tool call was issued — here, an event
  // that fired outside any of the Read call's own spans landed on the turn
  // root — not the claude_code.tool span the event is actually about. See the
  // CLAUDE.md Log bucketing section. Both event types carry their own
  // tool_use_id, matching the one on the wrapper tool span itself.
  it('re-points a tool_result log at its own tool span via tool_use_id when its own span_id lands outside the call', () => {
    const root = span('root', null, 'claude_code.interaction');
    const toolSpan = span('read-span', 'root', 'claude_code.tool', { tool_use_id: 'toolu_abc' });
    const tree = buildSpanTree([root, toolSpan]);

    const result = bucketLogsBySpan(
      [log('root', { 'event.name': 'tool_result', tool_use_id: 'toolu_abc' })],
      tree,
      'root',
    );

    expect(result.get('read-span')).toHaveLength(1);
    expect(result.get('root') ?? []).toHaveLength(0);
  });

  // The regression this test guards: a decision made while the tool was
  // genuinely waiting on the user (a real claude_code.tool.blocked_on_user
  // span, which never carries a tool_use_id of its own) is already correctly
  // attributed by Claude Code's own span_id — that's more specific than the
  // wrapper span, and the tool_use_id fallback must not clobber it by
  // re-pointing everything at claude_code.tool.execution or the wrapper.
  it('leaves a tool_decision already on the real blocked_on_user span alone, rather than redirecting it', () => {
    const root = span('root', null, 'claude_code.interaction');
    const toolSpan = span('read-span', 'root', 'claude_code.tool', { tool_use_id: 'toolu_abc' });
    const blockedSpan = span('blocked-span', 'read-span', 'claude_code.tool.blocked_on_user');
    const executionSpan = span('execution-span', 'read-span', 'claude_code.tool.execution', {
      tool_use_id: 'toolu_abc',
    });
    const tree = buildSpanTree([root, toolSpan, blockedSpan, executionSpan]);

    const result = bucketLogsBySpan(
      [log('blocked-span', { 'event.name': 'tool_decision', tool_use_id: 'toolu_abc' })],
      tree,
      'root',
    );

    expect(result.get('blocked-span')).toHaveLength(1);
    expect(result.get('read-span') ?? []).toHaveLength(0);
    expect(result.get('execution-span') ?? []).toHaveLength(0);
  });

  // A log already on a real sub-span of the call is never migrated up to the
  // wrapper — the fallback only fires when the log's own span_id points
  // somewhere outside the whole family (checked in the previous test above).
  it('leaves a tool_result already on the execution sub-span alone', () => {
    const root = span('root', null, 'claude_code.interaction');
    const toolSpan = span('read-span', 'root', 'claude_code.tool', { tool_use_id: 'toolu_abc' });
    const executionSpan = span('execution-span', 'read-span', 'claude_code.tool.execution', {
      tool_use_id: 'toolu_abc',
    });
    const tree = buildSpanTree([root, toolSpan, executionSpan]);

    const result = bucketLogsBySpan(
      [log('execution-span', { 'event.name': 'tool_result', tool_use_id: 'toolu_abc' })],
      tree,
      'root',
    );

    expect(result.get('execution-span')).toHaveLength(1);
    expect(result.get('read-span') ?? []).toHaveLength(0);
  });

  // hook_execution_start/complete carry a hook_name but no tool_use_id — there's
  // no exact key to attach them to one specific tool call among several of the
  // same name, so they stay wherever their own span_id resolves (the root).
  it('leaves a log with no tool_use_id on its own span_id even when a matching tool span exists', () => {
    const root = span('root', null, 'claude_code.interaction');
    const toolSpan = span('read-span', 'root', 'claude_code.tool', { tool_use_id: 'toolu_abc' });
    const tree = buildSpanTree([root, toolSpan]);

    const result = bucketLogsBySpan(
      [log('root', { 'event.name': 'hook_execution_start', hook_name: 'PreToolUse:Read' })],
      tree,
      'root',
    );

    expect(result.get('root')).toHaveLength(1);
    expect(result.get('read-span') ?? []).toHaveLength(0);
  });

  it('ignores a tool_use_id that matches no span in the tree and falls back to span_id resolution', () => {
    const root = span('root', null, 'claude_code.interaction');
    const tree = buildSpanTree([root]);

    const result = bucketLogsBySpan(
      [log('root', { 'event.name': 'tool_result', tool_use_id: 'toolu_unmatched' })],
      tree,
      'root',
    );

    expect(result.get('root')).toHaveLength(1);
  });

  it('sorts each bucket by event.sequence after correlating', () => {
    const root = span('root', null, 'claude_code.interaction');
    const toolSpan = span('read-span', 'root', 'claude_code.tool', { tool_use_id: 'toolu_abc' });
    const tree = buildSpanTree([root, toolSpan]);

    const result = bucketLogsBySpan(
      [
        log('root', { 'event.name': 'tool_result', tool_use_id: 'toolu_abc', 'event.sequence': 2 }),
        log('root', { 'event.name': 'tool_decision', tool_use_id: 'toolu_abc', 'event.sequence': 1 }),
      ],
      tree,
      'root',
    );

    expect(result.get('read-span')?.map((entry) => entry.attributes?.['event.name'])).toEqual([
      'tool_decision',
      'tool_result',
    ]);
  });
});
