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
import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../../test/renderWithProviders';
import type { LogRow, SpanRow } from '../../../../api';
import CallContextSection from './CallContextSection';

const BASE_MS = Date.parse('2026-09-05T12:00:00.000Z');

const toolCall = (callNumber: number, toolName: string, attributes: Record<string, unknown>): SpanRow =>
  ({
    spanId: `span-${callNumber}`,
    name: 'claude_code.tool',
    callNumber,
    startTimestamp: new Date(BASE_MS + callNumber * 1000).toISOString(),
    endTimestamp: new Date(BASE_MS + callNumber * 1000 + 100).toISOString(),
    durationNanos: 100 * 1e6,
    statusCode: 'ok',
    attributes: { tool_name: toolName, ...attributes },
  }) as unknown as SpanRow;

const read = toolCall(1, 'Read', { file_path: 'backend/LogService.java' });
const edit = toolCall(4, 'Edit', { file_path: 'backend/LogService.java' });
const spans = [read, toolCall(2, 'Grep', {}), toolCall(3, 'Grep', {}), edit];

const NO_LOGS = new Map<string, LogRow[]>();
const noop = () => {};

describe('CallContextSection', () => {
  it('states the computed relation, naming how far away the related call is', () => {
    renderWithProviders(
      <CallContextSection span={edit} spans={spans} logsBySpanId={NO_LOGS} onRevealSpan={noop} />,
    );

    expect(screen.getByText(/Same file: Read/)).toBeInTheDocument();
    expect(screen.getByText(/3 calls earlier/)).toBeInTheDocument();
  });

  // The reader who sees "read 3 calls earlier" wants to go and look at that call. revealSpan is
  // what handles the row being scrolled away, folded inside a dispatch, or outside the zoom.
  it('links the call number to the span it names', async () => {
    const onRevealSpan = vi.fn();
    renderWithProviders(
      <CallContextSection
        span={edit}
        spans={spans}
        logsBySpanId={NO_LOGS}
        onRevealSpan={onRevealSpan}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'call 1' }));

    expect(onRevealSpan).toHaveBeenCalledWith('span-1');
  });

  it('states what the call did, from its own logs', () => {
    const bashCall = toolCall(1, 'Bash', {
      full_command: 'sed -n 190,215p SpanInspectorDrawer.tsx',
      tool_use_id: 'toolu_1',
    });
    const execution = {
      spanId: 'exec-1',
      parentSpanId: bashCall.spanId,
      name: 'claude_code.tool.execution',
      startTimestamp: bashCall.startTimestamp,
      endTimestamp: bashCall.endTimestamp,
      durationNanos: bashCall.durationNanos,
      attributes: {},
    } as unknown as SpanRow;
    const logs = new Map<string, LogRow[]>([
      ['exec-1', [{
        id: 1,
        attributes: {
          'event.name': 'tool_result',
          tool_use_id: 'toolu_1',
          tool_input: JSON.stringify({ description: 'Verify section placement in drawer' }),
          success: true,
          tool_result_size_bytes: 965,
        },
      } as unknown as LogRow]],
      ['exec-1-decision', []],
    ]);

    renderWithProviders(
      <CallContextSection
        span={bashCall}
        spans={[bashCall, execution]}
        logsBySpanId={logs}
        onRevealSpan={noop}
      />,
    );

    expect(screen.getByText('Verify section placement in drawer')).toBeInTheDocument();
    expect(screen.getByText('Succeeded — returned 965 bytes')).toBeInTheDocument();
  });

  it('says whether a permission decision interrupted the reader or was pre-authorized', () => {
    const bashCall = toolCall(1, 'Bash', { full_command: 'git status', tool_use_id: 'toolu_1' });
    const blocked = {
      spanId: 'blocked-1',
      parentSpanId: bashCall.spanId,
      name: 'claude_code.tool.blocked_on_user',
      startTimestamp: bashCall.startTimestamp,
      endTimestamp: bashCall.endTimestamp,
      durationNanos: 9e6,
      attributes: {},
    } as unknown as SpanRow;
    const logs = new Map<string, LogRow[]>([
      ['blocked-1', [{
        id: 2,
        attributes: {
          'event.name': 'tool_decision',
          tool_use_id: 'toolu_1',
          decision: 'accept',
          source: 'config',
        },
      } as unknown as LogRow]],
    ]);

    renderWithProviders(
      <CallContextSection
        span={bashCall}
        spans={[bashCall, blocked]}
        logsBySpanId={logs}
        onRevealSpan={noop}
      />,
    );

    expect(screen.getByText('Permission: accept (pre-authorized)')).toBeInTheDocument();
  });

  it('resolves a tool.execution sub-span to the tool call it belongs to', () => {
    // The waterfall stacks three rows per call and two of them carry no tool_name, so a sub-span
    // resolves to the call it belongs to rather than falling through to the no-context branch.
    const execution = {
      spanId: 'exec-4',
      name: 'claude_code.tool.execution',
      parentSpanId: edit.spanId,
      startTimestamp: edit.startTimestamp,
      endTimestamp: edit.endTimestamp,
      durationNanos: edit.durationNanos,
      attributes: {},
    } as unknown as SpanRow;

    renderWithProviders(
      <CallContextSection
        span={execution}
        spans={[...spans, execution]}
        logsBySpanId={NO_LOGS}
        onRevealSpan={noop}
      />,
    );

    expect(screen.getByText(/Same file: Read/)).toBeInTheDocument();
  });

  it('renders nothing on a span that is not a tool call', () => {
    const generation = {
      spanId: 'llm-1',
      name: 'claude_code.llm_request',
      startTimestamp: new Date(BASE_MS).toISOString(),
      endTimestamp: new Date(BASE_MS + 500).toISOString(),
      durationNanos: 500 * 1e6,
      attributes: { model: 'claude-opus-5' },
    } as unknown as SpanRow;

    const { container } = renderWithProviders(
      <CallContextSection
        span={generation}
        spans={[generation]}
        logsBySpanId={NO_LOGS}
        onRevealSpan={noop}
      />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  // No button, no request, no model: this section is entirely computed now.
  it('asks nothing of the network', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    renderWithProviders(
      <CallContextSection span={edit} spans={spans} logsBySpanId={NO_LOGS} onRevealSpan={noop} />,
    );

    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});
