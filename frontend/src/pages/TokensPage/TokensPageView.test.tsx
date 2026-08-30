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
import { renderWithProviders } from '../../test/renderWithProviders';
import TokensPageView, { type TokensPageViewProps } from './TokensPageView';
import type {
  SessionCacheEfficiencyRow,
  TokenUsageSummary,
} from '../../api';
import { WINDOWS } from '../../lib/constants';

const summary: TokenUsageSummary = {
  inputTokens: 1_000_000,
  outputTokens: 200_000,
  cacheCreationTokens: 300_000,
  cacheReadTokens: 8_000_000,
  cacheReadRatio: 0.9,
  bucketSeconds: 3600,
  points: [
    {
      timestamp: '2026-08-29T00:00:00.000Z',
      input: 500_000,
      output: 100_000,
      cacheCreation: 150_000,
      cacheRead: 4_000_000,
    },
    {
      timestamp: '2026-08-29T01:00:00.000Z',
      input: 500_000,
      output: 100_000,
      cacheCreation: 150_000,
      cacheRead: 4_000_000,
    },
  ],
  byModel: [
    { model: 'claude-sonnet-5', tokens: '9.5M', share: 95, colorIndex: 0 },
  ],
  cost: {
    spend24h: '$4.23',
    deltaPct: '-5%',
    burnRate: '$0.18/hr',
    projected30d: '$126.90',
    costPer1k: '$0.001',
    trend: [1, 2, 3],
    byModel: [{ model: 'claude-sonnet-5', usd: '$4.23', share: 100, colorIndex: 0 }],
    note: 'note',
  },
};

const cacheEfficiencyRows: SessionCacheEfficiencyRow[] = [
  {
    sessionId: '8f2a91cd-6b34-4e02-9a71-c5d0e8f21a44',
    cacheEfficiency: 0.18,
    cacheReadTokens: 210_000,
    inputSideTokens: 1_200_000,
    inputTokens: 825_000,
    cacheCreationTokens: 145_000,
    outputTokens: 38_000,
    totalTokens: 1_238_000,
    costUsd: 9.42,
    endTimestamp: '2026-08-29T22:00:00.000Z',
    firstUserPrompt: 'Fix the auth middleware timeout bug',
  },
];

const baseProps: TokensPageViewProps = {
  selection: { kind: 'preset', minutes: 1440 },
  onSelectionChange: vi.fn(),
  windows: WINDOWS,
  summary,
  cacheEfficiencyRows,
  contextFootprintRows: [],
  activeTab: 'overview',
  onActiveTabChange: vi.fn(),
  selectedCacheEfficiencyRow: null,
  onSelectCacheEfficiencyRow: vi.fn(),
  isLoading: false,
  error: null,
  onReload: vi.fn(),
  autoRefresh: false,
  onAutoRefreshChange: vi.fn(),
  isPolling: false,
};

describe('TokensPageView', () => {
  it('renders KPI cards and the by-model row on the Overview tab', () => {
    renderWithProviders(<TokensPageView {...baseProps} />);

    expect(screen.getByText('Total cost')).toBeInTheDocument();
    expect(screen.getAllByText('$4.23').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Sonnet 5/).length).toBeGreaterThan(0);
  });

  it('shows the "not enough data" chart message when only one bucket is in the window', () => {
    renderWithProviders(
      <TokensPageView
        {...baseProps}
        summary={{ ...summary, points: [summary.points[0]] }}
      />,
    );

    expect(
      screen.getByText(
        'Only one bucket in this window — need at least two to plot a trend.',
      ),
    ).toBeInTheDocument();
  });

  it('calls onActiveTabChange when the Cache & Context tab is clicked', async () => {
    const user = userEvent.setup();
    const onActiveTabChange = vi.fn();
    renderWithProviders(
      <TokensPageView {...baseProps} onActiveTabChange={onActiveTabChange} />,
    );

    await user.click(screen.getByRole('tab', { name: 'Cache & Context' }));

    expect(onActiveTabChange).toHaveBeenCalledWith('cache-context');
  });

  it('opens the session detail dialog when a cache-efficiency row is clicked', async () => {
    const user = userEvent.setup();
    const onSelectCacheEfficiencyRow = vi.fn();
    renderWithProviders(
      <TokensPageView
        {...baseProps}
        activeTab="cache-context"
        onSelectCacheEfficiencyRow={onSelectCacheEfficiencyRow}
      />,
    );

    await user.click(
      screen.getByText('Fix the auth middleware timeout bug'),
    );

    expect(onSelectCacheEfficiencyRow).toHaveBeenCalledWith(
      cacheEfficiencyRows[0],
    );
  });

  it('surfaces the PageLayout error slot when the query has failed', () => {
    renderWithProviders(
      <TokensPageView {...baseProps} error={new Error('boom')} />,
    );

    expect(screen.getByText('boom')).toBeInTheDocument();
  });
});
