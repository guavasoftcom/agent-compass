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
import SwitchTraceModalView from './SwitchTraceModalView';
import type { NestedSwitchTraceRow, SwitchTraceRow } from './switchTraceRows';

const flatRows: SwitchTraceRow[] = [
  {
    timestamp: '2026-08-30T10:00:00.000Z',
    prompt: 'Refactor the Aurora theme overlay.',
    traceId: 'trace-older',
    model: 'claude-sonnet-4',
    costUsd: 0.12,
    tokens: { input: 100, output: 50, cacheCreation: 10, cacheRead: 500 },
  },
  {
    timestamp: '2026-08-30T10:05:00.000Z',
    prompt: 'Fix the failing tool-calls chart.',
    traceId: 'trace-current',
    model: 'claude-opus-5',
    costUsd: 0.42,
    tokens: { input: 200, output: 80, cacheCreation: 20, cacheRead: 900 },
  },
];

const rows: NestedSwitchTraceRow[] = flatRows.map((row) => ({ row, depth: 0, railBelow: [] }));

describe('SwitchTraceModalView', () => {
  it('renders the session id, one row per turn, and flags the current trace', () => {
    renderWithProviders(
      <SwitchTraceModalView
        open
        onClose={vi.fn()}
        sessionId="session-abc"
        currentTraceId="trace-current"
        rows={rows}
        isLoading={false}
        onSelectTrace={vi.fn()}
      />,
    );

    expect(screen.getByText(/session-abc/)).toBeInTheDocument();
    expect(screen.getByText('Refactor the Aurora theme overlay.')).toBeInTheDocument();
    expect(screen.getByText('Fix the failing tool-calls chart.')).toBeInTheDocument();
    expect(screen.getByText('current')).toBeInTheDocument();
  });

  it('shows the empty state when the session has no other traces', () => {
    renderWithProviders(
      <SwitchTraceModalView
        open
        onClose={vi.fn()}
        sessionId="session-abc"
        currentTraceId="trace-current"
        rows={[]}
        isLoading={false}
        onSelectTrace={vi.fn()}
      />,
    );

    expect(
      screen.getByText('No other traces in this session.'),
    ).toBeInTheDocument();
  });

  it('shows a loading state while the prompts query is in flight', () => {
    renderWithProviders(
      <SwitchTraceModalView
        open
        onClose={vi.fn()}
        sessionId="session-abc"
        currentTraceId="trace-current"
        rows={[]}
        isLoading
        onSelectTrace={vi.fn()}
      />,
    );

    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('calls onSelectTrace with the clicked row and onClose with the Close button', async () => {
    const user = userEvent.setup();
    const onSelectTrace = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(
      <SwitchTraceModalView
        open
        onClose={onClose}
        sessionId="session-abc"
        currentTraceId="trace-current"
        rows={rows}
        isLoading={false}
        onSelectTrace={onSelectTrace}
      />,
    );

    await user.click(screen.getByText('Refactor the Aurora theme overlay.'));
    expect(onSelectTrace).toHaveBeenCalledWith('trace-older');

    await user.click(screen.getByText('Close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('renders a child row after (visually beneath) its dispatcher row', () => {
    const dispatcher: SwitchTraceRow = flatRows[0];
    const child: SwitchTraceRow = {
      ...flatRows[1],
      traceId: 'trace-child',
      prompt: 'Background subagent turn.',
      dispatchingTraceId: dispatcher.traceId,
    };
    const nestedRows: NestedSwitchTraceRow[] = [
      { row: dispatcher, depth: 0, railBelow: [] },
      { row: child, depth: 1, railBelow: [false] },
    ];

    renderWithProviders(
      <SwitchTraceModalView
        open
        onClose={vi.fn()}
        sessionId="session-abc"
        currentTraceId="trace-child"
        rows={nestedRows}
        isLoading={false}
        onSelectTrace={vi.fn()}
      />,
    );

    const renderedPrompts = screen
      .getAllByText(/Refactor the Aurora theme overlay\.|Background subagent turn\./)
      .map((element) => element.textContent);

    expect(renderedPrompts).toEqual([
      'Refactor the Aurora theme overlay.',
      'Background subagent turn.',
    ]);
  });
});
