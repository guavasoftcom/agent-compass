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
import TraceStreamView, { type TraceStreamViewProps } from './TraceStreamView';
import type { TraceRow } from '../../../../api';

const rows: TraceRow[] = [
  {
    traceId: 'a'.repeat(32),
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
    traceId: 'b'.repeat(32),
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

const baseProps: TraceStreamViewProps = {
  rows,
  total: 2,
  loading: false,
  hasMore: false,
  expanded: new Set(),
  onToggleExpand: vi.fn(),
  onLoadMore: vi.fn(),
};

describe('TraceStreamView', () => {
  it('renders each trace row with its operation, prompt, and totals', () => {
    renderWithProviders(<TraceStreamView {...baseProps} />);

    expect(screen.getByText('session.turn')).toBeInTheDocument();
    expect(screen.getByText('tool.execute')).toBeInTheDocument();
    expect(screen.getByText('Refactor the theme overlay')).toBeInTheDocument();
    expect(screen.getByText('$0.830')).toBeInTheDocument();
    expect(screen.getByText('— end of results · 2 traces —')).toBeInTheDocument();
  });

  it('renders "—" for a trace with no prompt and no tokens', () => {
    renderWithProviders(<TraceStreamView {...baseProps} />);

    const dashes = screen.getAllByText('—');
    expect(dashes.length).toBeGreaterThan(0);
  });

  it('shows the empty state when there are no rows and it is not loading', () => {
    renderWithProviders(<TraceStreamView {...baseProps} rows={[]} total={0} />);

    expect(screen.getByText('No traces match')).toBeInTheDocument();
  });

  it('shows a loading indicator when loading is true', () => {
    renderWithProviders(<TraceStreamView {...baseProps} loading />);

    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('shows "scroll to load more" when hasMore is true', () => {
    renderWithProviders(<TraceStreamView {...baseProps} hasMore total={20} />);

    expect(screen.getByText(/scroll to load more/)).toBeInTheDocument();
  });

  it('calls onToggleExpand with the clicked trace id when a row is clicked', async () => {
    const user = userEvent.setup();
    const onToggleExpand = vi.fn();
    renderWithProviders(
      <TraceStreamView {...baseProps} onToggleExpand={onToggleExpand} />,
    );

    await user.click(screen.getByText('session.turn'));

    expect(onToggleExpand).toHaveBeenCalledWith('a'.repeat(32));
  });
});
