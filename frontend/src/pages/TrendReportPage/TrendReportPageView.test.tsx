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
import { renderWithProviders } from '../../test/renderWithProviders';
import TrendReportPageView, {
  type TrendReportPageViewProps,
  type TrendSectionState,
} from './TrendReportPageView';
import type { TrendMetric, TrendReport } from './trendReportApi';
import type { WindowSelection } from '../../api';
import { WINDOWS } from '../../lib/constants';

const selection: WindowSelection = { kind: 'preset', minutes: 1440 };

const metric = (before: number, after: number, directionIsGoodWhen: 'up' | 'down'): TrendMetric => ({
  before,
  after,
  beforeSeries: [before, before, before],
  afterSeries: [after, after, after],
  directionIsGoodWhen,
});

const currentPeriod = { start: '2026-08-29T00:00:00.000Z', end: '2026-08-30T00:00:00.000Z' };
const previousPeriod = { start: '2026-08-28T00:00:00.000Z', end: '2026-08-29T00:00:00.000Z' };

const costReport: TrendReport = {
  current: currentPeriod,
  previous: previousPeriod,
  metrics: {
    total_cost: metric(612.4, 404.18, 'down'),
    cost_per_session: metric(12.4, 9.1, 'down'),
    blended_rate_per_1m: metric(3.2, 2.9, 'down'),
  } as TrendReport['metrics'],
};

const tokenEfficiencyReport: TrendReport = {
  current: currentPeriod,
  previous: previousPeriod,
  metrics: {
    cache_read_ratio_pct: metric(62.1, 71.3, 'up'),
    tokens_total: metric(1_200_000, 1_500_000, 'down'),
    tokens_per_session: metric(4200, 4100, 'down'),
  } as TrendReport['metrics'],
};

const reliabilityReport: TrendReport = {
  current: currentPeriod,
  previous: previousPeriod,
  metrics: {
    tool_errors: metric(48, 12, 'down'),
    error_rate_pct: metric(4.2, 1.1, 'down'),
    session_failures: metric(3, 1, 'down'),
  } as TrendReport['metrics'],
};

const activityReport: TrendReport = {
  current: currentPeriod,
  previous: previousPeriod,
  metrics: {
    sessions: metric(340, 342, 'up'),
    avg_duration_min: metric(18.2, 19.5, 'up'),
  } as TrendReport['metrics'],
};

const loadedSection = (data: TrendReport): TrendSectionState => ({ data, isLoading: false, error: null });
const loadingSection: TrendSectionState = { data: undefined, isLoading: true, error: null };
const erroredSection: TrendSectionState = { data: undefined, isLoading: false, error: new Error('boom') };

const baseProps: TrendReportPageViewProps = {
  selection,
  onSelectionChange: vi.fn(),
  windows: WINDOWS,
  onReload: vi.fn(),
  autoRefresh: false,
  onAutoRefreshChange: vi.fn(),
  isPolling: false,
  sections: {
    cost: loadedSection(costReport),
    tokenEfficiency: loadedSection(tokenEfficiencyReport),
    reliability: loadedSection(reliabilityReport),
    activity: loadedSection(activityReport),
  },
};

describe('TrendReportPageView', () => {
  it('renders every section header and metric row from loaded sections', () => {
    renderWithProviders(<TrendReportPageView {...baseProps} />);

    // "Cost" and "Reliability" each appear twice: once as the section header, once as a
    // summary-callout label — see the dedicated summary-callouts test for that content.
    expect(screen.getAllByText('Cost').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Token efficiency')).toBeInTheDocument();
    expect(screen.getAllByText('Reliability').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Activity')).toBeInTheDocument();
    expect(screen.getByText('Total cost')).toBeInTheDocument();
    expect(screen.getByText('$612.40')).toBeInTheDocument();
    expect(screen.getByText('$404.18')).toBeInTheDocument();
  });

  it('renders the derived summary callouts from the merged section metrics', () => {
    renderWithProviders(<TrendReportPageView {...baseProps} />);

    expect(screen.getByText('Volume')).toBeInTheDocument();
    expect(screen.getByText(/Spend dropped/)).toBeInTheDocument();
    expect(screen.getByText(/Tool errors fell/)).toBeInTheDocument();
  });

  it('renders an inline error message only for a section that failed with no data, leaving siblings intact', () => {
    renderWithProviders(
      <TrendReportPageView
        {...baseProps}
        sections={{ ...baseProps.sections, reliability: erroredSection }}
      />,
    );

    expect(screen.getByText("Couldn't load this section.")).toBeInTheDocument();
    // Cost section still renders its real rows since it has data.
    expect(screen.getByText('Total cost')).toBeInTheDocument();
  });

  it('shows no page-level error banner unless every section has failed with no data', () => {
    renderWithProviders(
      <TrendReportPageView
        {...baseProps}
        sections={{ ...baseProps.sections, reliability: erroredSection }}
      />,
    );

    expect(screen.queryByText('boom')).not.toBeInTheDocument();
  });

  it('surfaces the PageLayout error slot when every section has failed with no data', () => {
    renderWithProviders(
      <TrendReportPageView
        {...baseProps}
        sections={{
          cost: erroredSection,
          tokenEfficiency: erroredSection,
          reliability: erroredSection,
          activity: erroredSection,
        }}
      />,
    );

    expect(screen.getByText('boom')).toBeInTheDocument();
  });

  it('renders skeleton rows while every section is still loading with no data at all', () => {
    const { container } = renderWithProviders(
      <TrendReportPageView
        {...baseProps}
        sections={{
          cost: loadingSection,
          tokenEfficiency: loadingSection,
          reliability: loadingSection,
          activity: loadingSection,
        }}
      />,
    );

    expect(screen.queryByText('Total cost')).not.toBeInTheDocument();
    expect(container.querySelectorAll('.MuiSkeleton-root').length).toBeGreaterThan(0);
  });
});
