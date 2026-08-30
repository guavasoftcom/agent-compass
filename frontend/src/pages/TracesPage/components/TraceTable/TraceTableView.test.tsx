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
import TraceTableView, { type TraceTableViewProps } from './TraceTableView';
import type { TraceRow } from '../../../../api';

const rows: TraceRow[] = [
  {
    traceId: 'd'.repeat(32),
    startTimestamp: '2026-08-30T10:00:00.000Z',
    rootSpanName: 'session.turn',
    rootSpanId: 'span-1',
    sessionId: 'sess_abc123',
    spanCount: 12,
    durationNanos: 820_000_000,
    errorCount: 0,
    totalTokens: 42000,
    totalCostUsd: 0.83,
    firstUserPrompt: 'Refactor the theme overlay',
  },
  {
    traceId: 'e'.repeat(32),
    startTimestamp: '2026-08-30T10:02:00.000Z',
    rootSpanName: 'tool.execute',
    rootSpanId: 'span-2',
    sessionId: 'sess_def456',
    spanCount: 4,
    durationNanos: 120_000_000,
    errorCount: 1,
    totalTokens: 0,
    totalCostUsd: 0,
    firstUserPrompt: null,
  },
];

const baseProps: TraceTableViewProps = {
  rows,
  total: 2,
  page: 0,
  pageSize: 25,
  loading: false,
  expanded: new Set(),
  onToggleExpand: vi.fn(),
  onPageChange: vi.fn(),
  onPageSizeChange: vi.fn(),
};

describe('TraceTableView', () => {
  it('renders a table row per trace with root span, prompt, cost, and error count', () => {
    renderWithProviders(<TraceTableView {...baseProps} />);

    expect(screen.getByText('session.turn')).toBeInTheDocument();
    expect(screen.getByText('tool.execute')).toBeInTheDocument();
    expect(screen.getByText('Refactor the theme overlay')).toBeInTheDocument();
    expect(screen.getByText('$0.830')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
  });

  it('shows the empty state when there are no rows and it is not loading', () => {
    renderWithProviders(<TraceTableView {...baseProps} rows={[]} total={0} />);

    expect(screen.getByText('No traces match')).toBeInTheDocument();
  });

  it('calls onToggleExpand with the clicked trace id when a row is clicked', async () => {
    const user = userEvent.setup();
    const onToggleExpand = vi.fn();
    renderWithProviders(
      <TraceTableView {...baseProps} onToggleExpand={onToggleExpand} />,
    );

    await user.click(screen.getByText('session.turn'));

    expect(onToggleExpand).toHaveBeenCalledWith('d'.repeat(32));
  });

  it('calls onPageChange when the pager advances to the next page', async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    renderWithProviders(
      <TraceTableView
        {...baseProps}
        total={100}
        onPageChange={onPageChange}
      />,
    );

    const iconButtons = screen
      .getAllByRole('button')
      .filter((button) => button.textContent === '');
    const nextButton = iconButtons[1];
    await user.click(nextButton);

    expect(onPageChange).toHaveBeenCalledWith(1);
  });
});
