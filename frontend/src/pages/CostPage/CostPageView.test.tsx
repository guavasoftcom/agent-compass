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
import CostPageView, { type CostPageViewProps } from './CostPageView';
import type { CostBreakdown, CostSessionShare, WindowSelection } from '../../api';
import { WINDOWS } from '../../lib/constants';

const selection: WindowSelection = { kind: 'preset', minutes: 1440 };

const topSession: CostSessionShare = {
  sessionId: '8467bd17-5940-4b2d-9eed-cdcff18faa34',
  costUsd: 58.4,
  requests: 12,
  firstUserPrompt: 'Migrate the billing service off the legacy Stripe SDK',
  mainLoopCostUsd: 41.9,
  subagentCostUsd: 14.2,
  skillCostUsd: 2.0,
  auxiliaryCostUsd: 0.3,
};

const breakdown: CostBreakdown = {
  totalCostUsd: 407.22,
  priorCostUsd: 380.0,
  deltaPct: 7.2,
  burnRatePerHour: 4.5,
  projected30dUsd: 3240.5,
  totalRequests: 512,
  totalInputTokens: 1_000_000,
  totalOutputTokens: 250_000,
  totalCacheCreationTokens: 50_000,
  totalCacheReadTokens: 4_000_000,
  categories: [
    { category: 'MAIN_LOOP', costUsd: 291.67, requests: 300, share: 71.5, drilldown: [], identifiedCostUsd: null },
    {
      category: 'SUBAGENT',
      costUsd: 82.43,
      requests: 150,
      share: 20.2,
      drilldown: [{ identifier: 'code-reviewer', costUsd: 82.43, share: 100 }],
      identifiedCostUsd: 82.43,
    },
    {
      category: 'SKILL',
      costUsd: 13.82,
      requests: 50,
      share: 3.4,
      drilldown: [{ identifier: 'pdf-fill', costUsd: 13.82, share: 100 }],
      identifiedCostUsd: 13.82,
    },
    { category: 'AUXILIARY', costUsd: 1.3, requests: 12, share: 0.3, drilldown: [], identifiedCostUsd: null },
  ],
  trend: [
    { timestamp: '2026-08-29T00:00:00Z', costByCategory: { MAIN_LOOP: 100, SUBAGENT: 20 } },
    { timestamp: '2026-08-30T00:00:00Z', costByCategory: { MAIN_LOOP: 191.67, SUBAGENT: 62.43, SKILL: 13.82, AUXILIARY: 1.3 } },
  ],
  modelEffort: [
    {
      model: 'claude-sonnet-4-5',
      effort: 'high',
      costUsd: 198.4,
      requests: 200,
      inputTokens: 500_000,
      outputTokens: 100_000,
      cacheCreationTokens: 20_000,
      cacheReadTokens: 2_000_000,
    },
    {
      model: 'claude-haiku-4-5',
      effort: null,
      costUsd: 40.0,
      requests: 100,
      inputTokens: 100_000,
      outputTokens: 20_000,
      cacheCreationTokens: 5_000,
      cacheReadTokens: 500_000,
    },
  ],
  topSessions: [topSession],
  bucketSeconds: 3600,
};

const emptyBreakdown: CostBreakdown = {
  totalCostUsd: 0,
  priorCostUsd: 0,
  deltaPct: 0,
  burnRatePerHour: 0,
  projected30dUsd: 0,
  totalRequests: 0,
  totalInputTokens: 0,
  totalOutputTokens: 0,
  totalCacheCreationTokens: 0,
  totalCacheReadTokens: 0,
  categories: [],
  trend: [],
  modelEffort: [],
  topSessions: [],
  bucketSeconds: 3600,
};

const baseProps: CostPageViewProps = {
  selection,
  onSelectionChange: vi.fn(),
  windows: WINDOWS,
  breakdown,
  activeTab: 'overview',
  onActiveTabChange: vi.fn(),
  selectedSession: null,
  onSelectSession: vi.fn(),
  isLoading: false,
  error: null,
  onReload: vi.fn(),
  autoRefresh: false,
  onAutoRefreshChange: vi.fn(),
  isPolling: false,
};

describe('CostPageView', () => {
  it('renders the "Where it went" tab KPIs and money map from props', () => {
    renderWithProviders(<CostPageView {...baseProps} />);

    expect(screen.getByText('Total spend')).toBeInTheDocument();
    expect(screen.getByText('$407.22')).toBeInTheDocument();
    expect(screen.getByText('Where the money went')).toBeInTheDocument();
    expect(screen.getAllByText('Main loop').length).toBeGreaterThan(0);
  });

  it('calls onActiveTabChange when the "What drove it" tab is clicked', async () => {
    const user = userEvent.setup();
    const onActiveTabChange = vi.fn();
    renderWithProviders(<CostPageView {...baseProps} onActiveTabChange={onActiveTabChange} />);

    await user.click(screen.getByRole('tab', { name: 'What drove it' }));

    expect(onActiveTabChange).toHaveBeenCalledWith('drivers');
  });

  it('shows the "What drove it" KPIs and most expensive sessions table when that tab is active', () => {
    renderWithProviders(<CostPageView {...baseProps} activeTab="drivers" />);

    expect(screen.getAllByText('Requests').length).toBeGreaterThan(0);
    expect(screen.getByText('Most expensive sessions')).toBeInTheDocument();
    expect(screen.getByText('Migrate the billing service off the legacy Stripe SDK')).toBeInTheDocument();
  });

  it('opens the session cost dialog when a session row is clicked', async () => {
    const user = userEvent.setup();
    const onSelectSession = vi.fn();
    renderWithProviders(
      <CostPageView {...baseProps} activeTab="drivers" onSelectSession={onSelectSession} />,
    );

    await user.click(screen.getByText('Migrate the billing service off the legacy Stripe SDK'));

    expect(onSelectSession).toHaveBeenCalledWith(topSession);
  });

  it('renders empty-state messaging when there is no priced activity in the window', () => {
    renderWithProviders(<CostPageView {...baseProps} breakdown={emptyBreakdown} activeTab="drivers" />);

    expect(screen.getAllByText('No priced requests in this window.').length).toBeGreaterThan(0);
  });

  it('surfaces the PageLayout error slot when the query has failed', () => {
    renderWithProviders(<CostPageView {...baseProps} error={new Error('cost query failed')} />);

    expect(screen.getByText('cost query failed')).toBeInTheDocument();
  });
});
