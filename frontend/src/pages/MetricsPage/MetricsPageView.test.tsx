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
import MetricsPageView, { type MetricsPageViewProps } from './MetricsPageView';
import { METRICS } from './components/metricsSampleData';
import type { WindowSelection } from '../../api';
import { WINDOWS } from '../../lib/constants';


const selection: WindowSelection = { kind: 'preset', minutes: 1440 };

const baseProps: MetricsPageViewProps = {
  selection,
  onSelectionChange: vi.fn(),
  windows: WINDOWS,
  onReload: vi.fn(),
  autoRefresh: false,
  onAutoRefreshChange: vi.fn(),
  isPolling: false,
  metrics: METRICS,
  isLoading: false,
  error: null,
};

describe('MetricsPageView', () => {
  it('renders the KPI strip and the first metric selected by default', () => {
    renderWithProviders(<MetricsPageView {...baseProps} />);

    expect(screen.getByText('claude_code.token.usage')).toBeInTheDocument();
    expect(screen.getAllByText('token.usage').length).toBeGreaterThan(0);
    expect(screen.getByText('Split by')).toBeInTheDocument();
  });

  it('selects a different metric card and resets its split to None', async () => {
    const user = userEvent.setup();
    renderWithProviders(<MetricsPageView {...baseProps} />);

    await user.click(screen.getByText('session.count'));

    expect(screen.getByText('claude_code.session.count')).toBeInTheDocument();
    // session.count has no splits, so the Split-by toggle disappears entirely.
    expect(screen.queryByText('Split by')).not.toBeInTheDocument();
  });

  it('renders nothing in the detail section when there are no metrics', () => {
    renderWithProviders(<MetricsPageView {...baseProps} metrics={[]} />);

    expect(screen.queryByText('Split by')).not.toBeInTheDocument();
    expect(screen.getByText('Metrics · claude_code')).toBeInTheDocument();
  });

  it('surfaces the PageLayout error slot when the query has failed', () => {
    renderWithProviders(<MetricsPageView {...baseProps} error={new Error('metrics query failed')} />);

    expect(screen.getByText('metrics query failed')).toBeInTheDocument();
  });
});
