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
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import MetricsPageView, { type MetricsPageViewProps } from './MetricsPageView';
import { METRICS } from './components/metricsSampleData';
import { buildMetricDistributionSample } from './components/metricDistributionSampleData';
import { buildMetricFacetsSample } from './components/metricFacetsSampleData';
import {
  DISTRIBUTION_POINT_CAP,
  type AttributeFilter,
  type DistributionPoint,
  type MetricAggregation,
  type MetricDistribution,
} from './metricsApi';
import {
  buildValueScale,
  formatAxisValue,
  formatDistributionValue,
  formatPercentileValue,
  summarizeValues,
} from './components/MetricDistributionCard/distributionScatter';
import type { WindowSelection } from '../../api';
import { WINDOWS } from '../../lib/constants';

// MetricsPageView always passes onRepositoryUrlChange, so PageActions renders
// RepositorySelector, which fetches the repository list itself (it is not
// window-scoped and has no page-level query to stub via props). Stub the
// fetcher rather than let it hit the network in jsdom.
vi.mock('../../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api')>();
  return { ...actual, fetchRepositories: vi.fn().mockResolvedValue([]) };
});

const selection: WindowSelection = { kind: 'preset', minutes: 1440 };

const WINDOW_START = '2026-09-18T12:00:00.000Z';
const WINDOW_END = '2026-09-19T12:00:00.000Z';

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
  selectedId: 'token',
  onSelectedIdChange: vi.fn(),
  windowFrom: WINDOW_START,
  windowTo: WINDOW_END,
  onOpenTrace: vi.fn(),
  openTraceId: null,
  onCloseTrace: vi.fn(),
  onOpenTraceInTraces: vi.fn(),
  repositoryUrl: null,
  onRepositoryUrlChange: vi.fn(),
  filters: [],
  onFiltersChange: vi.fn(),
  onFacetPickerOpenChange: vi.fn(),
  aggregation: 'sum',
  onAggregationChange: vi.fn(),
};

// MetricsPageView's selection, filters and aggregation are controlled by its container, so a test
// that needs a click to actually change them renders it through this tiny stateful stand-in for
// that container. Like the container, a real metric switch clears the filters and resets the
// aggregation to sum. It still forwards each change to any spy the test passed.
const ControlledMetricsPageView = (overrides: Partial<MetricsPageViewProps>) => {
  const [selectedId, setSelectedId] = useState<string | undefined>(
    'selectedId' in overrides ? overrides.selectedId : baseProps.selectedId,
  );
  const [filters, setFilters] = useState<AttributeFilter[]>(overrides.filters ?? baseProps.filters);
  const [aggregation, setAggregation] = useState<MetricAggregation>(
    overrides.aggregation ?? baseProps.aggregation,
  );
  return (
    <MetricsPageView
      {...baseProps}
      {...overrides}
      selectedId={selectedId}
      onSelectedIdChange={(nextId) => {
        overrides.onSelectedIdChange?.(nextId);
        if (nextId !== selectedId) {
          setFilters([]);
          setAggregation('sum');
        }
        setSelectedId(nextId);
      }}
      filters={filters}
      onFiltersChange={(nextFilters) => {
        overrides.onFiltersChange?.(nextFilters);
        setFilters(nextFilters);
      }}
      aggregation={aggregation}
      onAggregationChange={(nextAggregation) => {
        overrides.onAggregationChange?.(nextAggregation);
        setAggregation(nextAggregation);
      }}
    />
  );
};

const renderView = (overrides: Partial<MetricsPageViewProps> = {}) =>
  renderWithProviders(<ControlledMetricsPageView {...overrides} />);

// The rail row is the role="button" element wrapping a metric's display name.
const railRowFor = (displayName: string): HTMLElement =>
  screen.getByText(displayName).closest('[role="button"]') as HTMLElement;

const searchBox = (): HTMLElement => screen.getByPlaceholderText('Search metrics…');

// The fully-qualified name shows in the header AND in the distribution card's title (or its
// placeholder copy), so a bare getByText would find several. These assert presence/absence
// across all of them.
const expectMetricNameShown = (fullName: string) => {
  expect(screen.getAllByText(fullName).length).toBeGreaterThan(0);
};
const expectMetricNameHidden = (fullName: string) => {
  expect(screen.queryAllByText(fullName)).toHaveLength(0);
};

// The rail renders figures for every metric alongside the header, so a bare '1' or 'series' would
// be ambiguous — scope the lookup to the header's "Series" stat and match its combined text
// (the count and the unit word are sibling nodes, so no single element holds just one of them).
const expectSeriesStatText = (expectedText: string) => {
  const seriesStat = screen.getByText('Series').parentElement as HTMLElement;
  expect(
    within(seriesStat).getByText((_content, element) => element?.textContent?.trim() === expectedText),
  ).toBeInTheDocument();
};

const SCATTER_LABEL = 'Per-request scatter plot';
const tokenDistribution: MetricDistribution = buildMetricDistributionSample(
  'claude_code.token.usage',
  WINDOW_START,
  WINDOW_END,
);

describe('MetricsPageView', () => {
  it('renders the catalog rail with every metric and the first one selected by default', () => {
    renderView();

    // Group header with a count chip of the metrics in the group.
    const groupHeader = screen.getByText('Claude Code');
    expect(within(groupHeader).getByText(String(METRICS.length))).toBeInTheDocument();

    // Rows strip the claude_code. prefix; the header keeps the fully-qualified name.
    expect(railRowFor('token.usage')).toHaveAttribute('aria-pressed', 'true');
    expect(railRowFor('session.count')).toHaveAttribute('aria-pressed', 'false');
    expectMetricNameShown('claude_code.token.usage');
    expect(screen.getByText('Split by')).toBeInTheDocument();
  });

  it("shows each row's type badge, cardinality figure, and health dot", () => {
    renderView();

    const lineOfCodeRow = railRowFor('lines_of_code.count');
    expect(within(lineOfCodeRow).getByText('coun')).toBeInTheDocument();
    expect(within(lineOfCodeRow).getByText('1.6K ser')).toBeInTheDocument();
    expect(
      within(lineOfCodeRow).getByRole('img', { name: 'High cardinality — watch this series' }),
    ).toBeInTheDocument();
    expect(
      within(railRowFor('code_edit_tool.decision')).getByRole('img', {
        name: 'Cardinality spike — labels exploding',
      }),
    ).toBeInTheDocument();
  });

  it('reports a rail click through onSelectedIdChange and follows the controlled selectedId prop', async () => {
    const user = userEvent.setup();
    const onSelectedIdChange = vi.fn();
    // Rendered bare (no stateful wrapper): the view must not move its own selection.
    const { rerender } = renderWithProviders(
      <MetricsPageView {...baseProps} selectedId="token" onSelectedIdChange={onSelectedIdChange} />,
    );

    await user.click(screen.getByText('session.count'));

    expect(onSelectedIdChange).toHaveBeenCalledWith('session');
    expect(onSelectedIdChange).toHaveBeenCalledTimes(1);
    expect(railRowFor('token.usage')).toHaveAttribute('aria-pressed', 'true');

    // The container answers the callback by passing the new id back down.
    rerender(
      <MetricsPageView {...baseProps} selectedId="session" onSelectedIdChange={onSelectedIdChange} />,
    );
    expect(railRowFor('session.count')).toHaveAttribute('aria-pressed', 'true');
    expectMetricNameShown('claude_code.session.count');
    expectMetricNameHidden('claude_code.token.usage');
  });

  it('falls back to the first metric when selectedId is undefined or unknown', () => {
    const { rerender } = renderWithProviders(<MetricsPageView {...baseProps} selectedId={undefined} />);
    expect(railRowFor('token.usage')).toHaveAttribute('aria-pressed', 'true');

    rerender(<MetricsPageView {...baseProps} selectedId="no-such-metric" />);
    expect(railRowFor('token.usage')).toHaveAttribute('aria-pressed', 'true');
  });

  it('selects a different metric row and updates the header', async () => {
    const user = userEvent.setup();
    const onSelectedIdChange = vi.fn();
    renderView({ onSelectedIdChange });

    await user.click(screen.getByText('session.count'));

    expect(onSelectedIdChange).toHaveBeenCalledWith('session');
    expectMetricNameShown('claude_code.session.count');
    expectMetricNameHidden('claude_code.token.usage');
    expect(railRowFor('session.count')).toHaveAttribute('aria-pressed', 'true');
    expect(railRowFor('token.usage')).toHaveAttribute('aria-pressed', 'false');
    // session.count has no splits, so the Split-by toggle disappears entirely.
    expect(screen.queryByText('Split by')).not.toBeInTheDocument();
  });

  it('selects a row from the keyboard with Enter', async () => {
    const user = userEvent.setup();
    renderView();

    railRowFor('cost.usage').focus();
    await user.keyboard('{Enter}');

    expectMetricNameShown('claude_code.cost.usage');
    expect(railRowFor('cost.usage')).toHaveAttribute('aria-pressed', 'true');
  });

  it('switches the chart from a total series to the split series via the facet-bar toggle', async () => {
    const user = userEvent.setup();
    renderView();

    // No split active: the legend shows the single total series.
    expect(screen.getByText('total · tokens')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Model' }));

    expect(screen.queryByText('total · tokens')).not.toBeInTheDocument();
    expect(screen.getAllByText('claude-sonnet-4').length).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: 'None' }));

    expect(screen.getByText('total · tokens')).toBeInTheDocument();
  });

  it('resets the split to None when another metric is selected', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Model' }));
    expectSeriesStatText('3 models');

    // cost.usage also has a Model split, so the toggle stays — but it must be back on None.
    await user.click(screen.getByText('cost.usage'));

    expect(screen.getByText('Split by')).toBeInTheDocument();
    expectSeriesStatText('1 series');
  });

  it('filters the rail rows by a case-insensitive substring of the metric name', async () => {
    const user = userEvent.setup();
    renderView();

    await user.type(searchBox(), 'TOKEN');

    expect(screen.getByText('token.usage')).toBeInTheDocument();
    expect(screen.queryByText('session.count')).not.toBeInTheDocument();
    expect(screen.queryByText('cost.usage')).not.toBeInTheDocument();
    // The group chip counts the metrics left after filtering.
    expect(within(screen.getByText('Claude Code')).getByText('1')).toBeInTheDocument();
  });

  it('shows a "No metrics match" line, and no group header, for a query that matches nothing', async () => {
    const user = userEvent.setup();
    renderView();

    await user.type(searchBox(), 'zzz-nonsense');

    expect(screen.getByText('No metrics match')).toBeInTheDocument();
    expect(screen.queryByText('Claude Code')).not.toBeInTheDocument();
    expect(screen.queryByText('session.count')).not.toBeInTheDocument();
  });

  it('keeps the selected metric selected when the search filters its row out', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByText('session.count'));
    await user.type(searchBox(), 'cost');

    // Its row is gone from the rail, but the detail pane still shows it...
    expect(screen.queryByText('session.count')).not.toBeInTheDocument();
    expectMetricNameShown('claude_code.session.count');

    // ...and clearing the search brings the row back still selected.
    await user.clear(searchBox());
    expect(railRowFor('session.count')).toHaveAttribute('aria-pressed', 'true');
  });

  it("shows the selected metric's cardinality and health in the header", async () => {
    const user = userEvent.setup();
    renderView();

    // Default selection is the token metric fixture: cardinality '1.2K', health 'ok'.
    expect(screen.getByText('1.2K')).toBeInTheDocument();
    const healthLabel = screen.getByText('Healthy');
    expect(healthLabel).toBeInTheDocument();

    // Full HEALTH_LABEL copy is available as a tooltip on hover.
    await user.hover(healthLabel);
    expect(await screen.findByText('Healthy · ingesting')).toBeInTheDocument();
  });

  it('formats a numeric cardinality compactly in both the header and the rail row', () => {
    const metricsWithRawCardinality = METRICS.map((metric) =>
      metric.id === 'token' ? { ...metric, cardinality: 12345 } : metric.id === 'session' ? { ...metric, cardinality: 987 } : metric,
    );
    renderView({ metrics: metricsWithRawCardinality });

    // Header (token is selected): 12345 -> 12.3K; rail rows append " ser".
    expect(screen.getByText('12.3K')).toBeInTheDocument();
    expect(within(railRowFor('token.usage')).getByText('12.3K ser')).toBeInTheDocument();
    // Below a thousand the number is printed as-is.
    expect(within(railRowFor('session.count')).getByText('987 ser')).toBeInTheDocument();
  });

  it('shows Series = 1 with singular copy for a metric with no splits', async () => {
    const user = userEvent.setup();
    renderView();

    // session.count has no splits, so the chart always draws its one total series.
    await user.click(screen.getByText('session.count'));

    expectSeriesStatText('1 series');
  });

  it('shows the active split row count with the pluralized split name', async () => {
    const user = userEvent.setup();
    renderView();

    // token starts with no split active: one total series.
    expectSeriesStatText('1 series');

    // Splitting token by Model stacks three rows (sonnet / opus / haiku).
    await user.click(screen.getByRole('button', { name: 'Model' }));

    expectSeriesStatText('3 models');
  });

  it('renders nothing in the detail section when there are no metrics', () => {
    renderView({ metrics: [] });

    expect(screen.queryByText('Split by')).not.toBeInTheDocument();
    expect(screen.queryByText('Claude Code')).not.toBeInTheDocument();
    expect(screen.getByText('No metrics')).toBeInTheDocument();
    expect(screen.queryByText(/per request · distribution over time/)).not.toBeInTheDocument();
    expect(searchBox()).toBeInTheDocument();
  });

  it('surfaces the PageLayout error slot when the query has failed', () => {
    renderView({ error: new Error('metrics query failed') });

    expect(screen.getByText('metrics query failed')).toBeInTheDocument();
  });

  describe('attribute filters', () => {
    const modelFilter: AttributeFilter = { key: 'model', value: 'claude-sonnet-4' };
    const terminalFilter: AttributeFilter = { key: 'terminal.type', value: 'vscode' };
    const tokenFacets = buildMetricFacetsSample('claude_code.token.usage');

    // Opens the picker and chooses `value` under `key`, the way a user would.
    const pickFilter = async (
      user: ReturnType<typeof userEvent.setup>,
      key: string,
      valuePattern: RegExp,
    ) => {
      await user.click(screen.getByRole('button', { name: '+ Add filter' }));
      await user.click(await screen.findByRole('menuitem', { name: key }));
      await user.click(await screen.findByRole('menuitem', { name: valuePattern }));
      // The closing popover hides the page (aria-hidden) until its exit transition ends.
      await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
    };

    it('applies a value chosen in the picker and shows it as a chip, with the pill still available', async () => {
      const user = userEvent.setup();
      const onFiltersChange = vi.fn();
      const onFacetPickerOpenChange = vi.fn();
      renderView({ facets: tokenFacets, onFiltersChange, onFacetPickerOpenChange });

      await user.click(screen.getByRole('button', { name: '+ Add filter' }));
      expect(onFacetPickerOpenChange).toHaveBeenLastCalledWith(true);
      await user.click(await screen.findByRole('menuitem', { name: 'model' }));
      await user.click(await screen.findByRole('menuitem', { name: /claude-opus-4/ }));

      expect(onFiltersChange).toHaveBeenCalledWith([{ key: 'model', value: 'claude-opus-4' }]);
      expect(onFacetPickerOpenChange).toHaveBeenLastCalledWith(false);
      expect(
        await screen.findByRole('button', { name: 'Remove filter model = claude-opus-4' }),
      ).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '+ Add filter' })).toBeInTheDocument();
    });

    it('ANDs several filters: a second key adds a second chip next to the first', async () => {
      const user = userEvent.setup();
      const onFiltersChange = vi.fn();
      renderView({ facets: tokenFacets, onFiltersChange });

      await pickFilter(user, 'model', /claude-opus-4/);
      await pickFilter(user, 'terminal.type', /vscode/);

      expect(onFiltersChange).toHaveBeenLastCalledWith([
        { key: 'model', value: 'claude-opus-4' },
        { key: 'terminal.type', value: 'vscode' },
      ]);
      expect(screen.getByRole('button', { name: 'Remove filter model = claude-opus-4' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Remove filter terminal.type = vscode' })).toBeInTheDocument();
    });

    it('replaces the chip when another value of the same key is picked, instead of adding a second', async () => {
      const user = userEvent.setup();
      const onFiltersChange = vi.fn();
      renderView({ facets: tokenFacets, filters: [modelFilter, terminalFilter], onFiltersChange });

      await pickFilter(user, 'model', /claude-opus-4/);

      expect(onFiltersChange).toHaveBeenLastCalledWith([{ key: 'model', value: 'claude-opus-4' }, terminalFilter]);
      expect(screen.getByRole('button', { name: 'Remove filter model = claude-opus-4' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Remove filter model = claude-sonnet-4' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Remove filter terminal.type = vscode' })).toBeInTheDocument();
    });

    it('does not offer the exact key = value pair that is already active', async () => {
      const user = userEvent.setup();
      renderView({ facets: tokenFacets, filters: [modelFilter] });

      await user.click(screen.getByRole('button', { name: '+ Add filter' }));
      await user.click(await screen.findByRole('menuitem', { name: 'model' }));

      expect(await screen.findByRole('menuitem', { name: /claude-opus-4/ })).toBeInTheDocument();
      expect(screen.queryByRole('menuitem', { name: /^claude-sonnet-4,/ })).not.toBeInTheDocument();
    });

    it('removes only the chip whose x was clicked', async () => {
      const user = userEvent.setup();
      const onFiltersChange = vi.fn();
      renderView({ filters: [modelFilter, terminalFilter], onFiltersChange });

      await user.click(screen.getByRole('button', { name: 'Remove filter model = claude-sonnet-4' }));

      expect(onFiltersChange).toHaveBeenCalledWith([terminalFilter]);
      expect(screen.queryByRole('button', { name: 'Remove filter model = claude-sonnet-4' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Remove filter terminal.type = vscode' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '+ Add filter' })).toBeInTheDocument();
    });

    it('renders "+ Add filter" disabled, still visible, once a metric has no filterable attributes', () => {
      renderView({ selectedId: 'session', facets: [] });

      expect(screen.getByText('Filter')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '+ Add filter' })).toBeDisabled();
    });

    it('keeps the add pill enabled until the facets are known, since they are fetched lazily', () => {
      renderView({ selectedId: 'session' });

      expect(screen.getByRole('button', { name: '+ Add filter' })).toBeEnabled();
    });

    it('keeps the filter bar for a metric with no splits (Split by is hidden, Filter stays)', async () => {
      const user = userEvent.setup();
      renderView();

      await user.click(screen.getByText('session.count'));

      expect(screen.getByText('Filter')).toBeInTheDocument();
      expect(screen.getByText('Agg')).toBeInTheDocument();
      expect(screen.queryByText('Split by')).not.toBeInTheDocument();
    });

    it('notes that the distribution ignores attribute filters only while a filter is active', () => {
      const { unmount } = renderView({ distribution: tokenDistribution, filters: [modelFilter, terminalFilter] });
      expect(screen.getByText('Distribution ignores attribute filters.')).toBeInTheDocument();
      unmount();

      renderView({ distribution: tokenDistribution });
      expect(screen.queryByText('Distribution ignores attribute filters.')).not.toBeInTheDocument();
    });
  });

  describe('aggregation', () => {
    it('reports an Agg choice and relabels the trend chart as a single unstacked aggregate', async () => {
      const user = userEvent.setup();
      const onAggregationChange = vi.fn();
      renderView({ onAggregationChange });

      await user.click(screen.getByRole('button', { name: 'avg' }));

      expect(onAggregationChange).toHaveBeenCalledWith('avg');
      expect(screen.getByText('avg per data point')).toBeInTheDocument();
      expect(screen.getByText('tokens, avg per point')).toBeInTheDocument();
      expect(screen.getByText('token.usage over time (avg)')).toBeInTheDocument();
      expect(screen.queryByText('total · tokens')).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'p95' }));
      expect(screen.getByText('p95 per data point')).toBeInTheDocument();
      expect(screen.getByText('tokens, p95 per point')).toBeInTheDocument();

      // count is a number of points, not tokens.
      await user.click(screen.getByRole('button', { name: 'count' }));
      expect(onAggregationChange).toHaveBeenLastCalledWith('count');
      expect(screen.getByText('data points per bucket')).toBeInTheDocument();
      expect(screen.getByText('points')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'sum' }));
      expect(screen.getByText('total · tokens')).toBeInTheDocument();
      expect(screen.queryByText('points')).not.toBeInTheDocument();
    });

    it('explains non-sum aggregates in a tooltip on the trend card, and only then', async () => {
      const user = userEvent.setup();
      renderView();
      // With sum, the only info icon is the facet bar's Split-by one.
      const infoIconCountWithSum = screen.getAllByTestId('InfoOutlinedIcon').length;

      await user.click(screen.getByRole('button', { name: 'avg' }));
      const infoIcons = screen.getAllByTestId('InfoOutlinedIcon');
      expect(infoIcons).toHaveLength(infoIconCountWithSum + 1);

      // The trend card sits after the facet bar, so its icon is the last one in the document.
      await user.hover(infoIcons[infoIcons.length - 1]);
      expect(
        await screen.findByText(/describe the individual data-point increments in each bucket/),
      ).toBeInTheDocument();
    });

    it('disables Group by and drops "(stacked)" under a non-sum aggregation, restoring the split on sum', async () => {
      const user = userEvent.setup();
      renderView();

      await user.click(screen.getByRole('button', { name: 'Model' }));
      expect(screen.getByText('tokens (stacked)')).toBeInTheDocument();
      expect(screen.getByRole('group', { name: 'Group by' })).not.toHaveAttribute('aria-disabled');

      await user.click(screen.getByRole('button', { name: 'avg' }));

      const groupBy = screen.getByRole('group', { name: 'Group by' });
      expect(groupBy).toHaveAttribute('aria-disabled', 'true');
      expect(groupBy).toHaveAttribute('inert');
      expect(screen.queryByText('tokens (stacked)')).not.toBeInTheDocument();
      expect(screen.queryByText('claude-sonnet-4')).not.toBeInTheDocument();
      expectSeriesStatText('1 series');

      await user.hover(groupBy);
      expect(
        await screen.findByText(
          'Averages and percentiles are not additive across groups - switch Agg back to sum to group',
        ),
      ).toBeInTheDocument();

      // The stored split was never touched, so sum brings the stacked Model chart back.
      await user.click(screen.getByRole('button', { name: 'sum' }));
      expect(screen.getByRole('group', { name: 'Group by' })).not.toHaveAttribute('aria-disabled');
      expect(screen.getByText('tokens (stacked)')).toBeInTheDocument();
      expectSeriesStatText('3 models');
    });

    it('shows the Summary, with a sum-based note, instead of a split breakdown under a non-sum aggregation', async () => {
      const user = userEvent.setup();
      renderView();

      await user.click(screen.getByRole('button', { name: 'Model' }));
      expect(screen.getByText('By model')).toBeInTheDocument();
      expect(screen.queryByText('Summary')).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'avg' }));

      expect(screen.queryByText('By model')).not.toBeInTheDocument();
      expect(screen.getByText('Summary')).toBeInTheDocument();
      expect(screen.getByText(/This summary is sum-based/)).toBeInTheDocument();
    });
  });

  describe('metric switch', () => {
    it('resets every filter and the aggregation (the container does; the view only reports the switch)', async () => {
      const user = userEvent.setup();
      const onSelectedIdChange = vi.fn();
      const onFiltersChange = vi.fn();
      const onAggregationChange = vi.fn();
      renderView({
        filters: [
          { key: 'model', value: 'claude-sonnet-4' },
          { key: 'terminal.type', value: 'vscode' },
        ],
        aggregation: 'avg',
        onSelectedIdChange,
        onFiltersChange,
        onAggregationChange,
      });
      expect(screen.getByRole('button', { name: 'Remove filter model = claude-sonnet-4' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Remove filter terminal.type = vscode' })).toBeInTheDocument();
      expect(screen.getByText('avg per data point')).toBeInTheDocument();

      await user.click(screen.getByText('cost.usage'));

      expect(onSelectedIdChange).toHaveBeenCalledWith('cost');
      // The view never clears them itself: that is the container's job on onSelectedIdChange.
      expect(onFiltersChange).not.toHaveBeenCalled();
      expect(onAggregationChange).not.toHaveBeenCalled();
      expect(screen.queryByRole('button', { name: /^Remove filter/ })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: '+ Add filter' })).toBeInTheDocument();
      expect(screen.queryByText('avg per data point')).not.toBeInTheDocument();
      expect(screen.getByText('total · USD')).toBeInTheDocument();
    });
  });

  describe('per-request distribution card', () => {
    it('shows a persistent placeholder for a metric that has no per-request distribution', async () => {
      const user = userEvent.setup();
      renderView({ distribution: tokenDistribution });

      await user.click(screen.getByText('session.count'));

      expect(screen.getByText(/No per-request distribution is available for/)).toBeInTheDocument();
      expect(
        screen.getByText(
          /Distributions only make sense for metrics with a per-request value \(tokens, cost\)/,
        ),
      ).toBeInTheDocument();
      // The old heatmap wording is gone.
      expect(screen.queryByText(/Heatmaps/)).not.toBeInTheDocument();
      // The placeholder names the metric, in addition to the header.
      expect(screen.getAllByText('claude_code.session.count')).toHaveLength(2);
      expect(screen.queryByRole('img', { name: SCATTER_LABEL })).not.toBeInTheDocument();
      expect(screen.queryByText('Loading distribution…')).not.toBeInTheDocument();
    });

    describe('scatter (token metric, log axis)', () => {
      const tokenValues = tokenDistribution.points.map((point) => point.value);
      const tokenExemplars = tokenDistribution.points.filter((point) => point.traceId !== null);
      const tokenSummary = summarizeValues(tokenValues);
      const tokenScale = buildValueScale('log', tokenValues);

      it('draws one small dot per non-exemplar request, three dashed percentile lines, and one button per exemplar', () => {
        renderView({ distribution: tokenDistribution });

        const scatter = screen.getByRole('img', { name: SCATTER_LABEL });
        expect(scatter.querySelectorAll('circle')).toHaveLength(
          tokenDistribution.points.length - tokenExemplars.length,
        );
        const percentileLines = scatter.querySelectorAll('line[data-percentile]');
        expect(Array.from(percentileLines).map((line) => line.getAttribute('data-percentile'))).toEqual([
          'p50',
          'p95',
          'p99',
        ]);
        percentileLines.forEach((line) => {
          expect(line).toHaveAttribute('stroke-dasharray');
        });
        expect(tokenExemplars).toHaveLength(6);
        expect(screen.getAllByRole('button', { name: /^Open trace for / })).toHaveLength(6);
        // No heatmap cells any more.
        expect(scatter.querySelectorAll('rect')).toHaveLength(0);
      });

      it('shows the request count in the header, with the log-scale note for tokens', () => {
        renderView({ distribution: tokenDistribution });

        expect(screen.getByText('per request · distribution over time')).toBeInTheDocument();
        expect(screen.getByText(`${tokenDistribution.points.length} requests · log scale`)).toBeInTheDocument();
      });

      it('says "latest 2,000 requests" once the server-side cap is reached, and singular for one', () => {
        const cappedDistribution: MetricDistribution = {
          points: Array.from({ length: DISTRIBUTION_POINT_CAP }, (_, index) => ({
            ts: new Date(Date.parse(WINDOW_START) + index * 1000).toISOString(),
            value: 1000 + index,
            traceId: null,
          })),
        };
        const { unmount } = renderView({ distribution: cappedDistribution });
        expect(screen.getByText('latest 2,000 requests · log scale')).toBeInTheDocument();
        unmount();

        renderView({ distribution: { points: [tokenDistribution.points[0]] } });
        expect(screen.getByText('1 request · log scale')).toBeInTheDocument();
      });

      it('shows p50 / p95 / p99 chips computed client-side from the points, plus an exemplar legend chip', () => {
        renderView({ distribution: tokenDistribution });

        // The Agg control also has a p95 button, so the chip labels are pinned by selector.
        (['p50', 'p95', 'p99'] as const).forEach((label) => {
          const chip = screen.getByText(label, { selector: 'span' }).parentElement as HTMLElement;
          expect(within(chip).getByText(formatPercentileValue(tokenSummary[label], 'tokens'))).toBeInTheDocument();
        });
        expect(screen.getByText('exemplar')).toBeInTheDocument();
      });

      it('derives the y axis from the data: the top tick is a nice ceiling above the largest value', () => {
        renderView({ distribution: tokenDistribution });

        const scatter = screen.getByRole('img', { name: SCATTER_LABEL });
        // Scoped to the plot: the trend chart above draws its own numeric ticks.
        expect(
          within(scatter).getByText(formatAxisValue(tokenScale.maximum, 'tokens', tokenScale.maximum)),
        ).toBeInTheDocument();
        expect(tokenScale.maximum).toBeGreaterThan(Math.max(...tokenValues));
        // Not the mockup's hardcoded 256000 -> "256K".
        expect(within(scatter).queryByText('256K')).not.toBeInTheDocument();
      });

      it('explains the plot and states that it and the counter Sum come from separate pipelines', () => {
        renderView({ distribution: tokenDistribution });

        expect(
          screen.getByText(/Each dot is one real request, plotted at its own time and value — gaps are simply idle time, not missing data/),
        ).toBeInTheDocument();
        expect(screen.getByText('exemplars')).toBeInTheDocument();
        expect(
          screen.getByText(/Built from per-request API logs, so totals here will not match the counter-based Sum above/),
        ).toBeInTheDocument();
      });

      it('plots each dot at its real timestamp within the request window, so idle time is a gap', () => {
        // Two requests at exactly 25% and 75% of the window: no matter how many requests there
        // are, x is time, not index. The SVG is 900px wide (jsdom never resizes) with 58/18 padding.
        const windowSpanMs = Date.parse(WINDOW_END) - Date.parse(WINDOW_START);
        const positionedDistribution: MetricDistribution = {
          points: [0.25, 0.75].map((fraction) => ({
            ts: new Date(Date.parse(WINDOW_START) + fraction * windowSpanMs).toISOString(),
            value: 5000,
            traceId: null,
          })),
        };
        renderView({ distribution: positionedDistribution });

        const scatter = screen.getByRole('img', { name: SCATTER_LABEL });
        const dotXPositions = Array.from(scatter.querySelectorAll('circle')).map((dot) =>
          Number(dot.getAttribute('cx')),
        );
        expect(dotXPositions).toEqual([58 + 0.25 * 824, 58 + 0.75 * 824]);
      });

      it('labels each exemplar with its value and shows a tooltip on hover', async () => {
        const user = userEvent.setup();
        renderView({ distribution: tokenDistribution });

        const exemplarButton = screen.getByRole('button', {
          name: `Open trace for ${formatDistributionValue(tokenExemplars[4].value, 'tokens')} request`,
        });

        await user.hover(exemplarButton);
        expect(await screen.findByText('click to open trace')).toBeInTheDocument();
      });

      it('calls onOpenTrace with the exemplar trace id when a dot is clicked', async () => {
        const user = userEvent.setup();
        const onOpenTrace = vi.fn();
        renderView({ distribution: tokenDistribution, onOpenTrace });

        const exemplar = tokenExemplars[2];
        await user.click(
          screen.getByRole('button', {
            name: `Open trace for ${formatDistributionValue(exemplar.value, 'tokens')} request`,
          }),
        );

        expect(onOpenTrace).toHaveBeenCalledTimes(1);
        expect(onOpenTrace).toHaveBeenCalledWith(exemplar.traceId);
      });

      it('calls onOpenTrace when a dot is activated from the keyboard', async () => {
        const user = userEvent.setup();
        const onOpenTrace = vi.fn();
        renderView({ distribution: tokenDistribution, onOpenTrace });

        const exemplar = tokenExemplars[0];
        screen
          .getByRole('button', {
            name: `Open trace for ${formatDistributionValue(exemplar.value, 'tokens')} request`,
          })
          .focus();
        await user.keyboard('{Enter}');
        expect(onOpenTrace).toHaveBeenCalledWith(exemplar.traceId);

        await user.keyboard(' ');
        expect(onOpenTrace).toHaveBeenCalledTimes(2);
      });

      it('draws no exemplar buttons when no point carries a trace id', () => {
        const withoutExemplars: MetricDistribution = {
          points: tokenDistribution.points.map((point): DistributionPoint => ({ ...point, traceId: null })),
        };
        renderView({ distribution: withoutExemplars });

        expect(screen.queryByRole('button', { name: /^Open trace for / })).not.toBeInTheDocument();
        expect(
          screen.getByRole('img', { name: SCATTER_LABEL }).querySelectorAll('circle'),
        ).toHaveLength(withoutExemplars.points.length);
      });
    });

    describe('scatter (cost metric, linear axis from zero)', () => {
      const costDistribution = buildMetricDistributionSample('claude_code.cost.usage', WINDOW_START, WINDOW_END);
      const costScale = buildValueScale(
        'linear',
        costDistribution.points.map((point) => point.value),
      );

      it('formats a USD distribution with dollar ticks from $0.00 up to a ceiling derived from the data', () => {
        renderView({ selectedId: 'cost', distribution: costDistribution });

        const scatter = screen.getByRole('img', { name: SCATTER_LABEL });
        expect(within(scatter).getByText('$0.00')).toBeInTheDocument();
        expect(
          within(scatter).getByText(formatAxisValue(costScale.maximum, 'USD', costScale.maximum)),
        ).toBeInTheDocument();
        expect(costScale.minimum).toBe(0);
        expect(costScale.maximum).toBeGreaterThan(
          Math.max(...costDistribution.points.map((point) => point.value)),
        );
        // A linear axis: no log-scale note, just the count.
        expect(screen.getByText(`${costDistribution.points.length} requests`)).toBeInTheDocument();
        expect(screen.queryByText(/log scale/)).not.toBeInTheDocument();
      });

      it('formats the exemplar aria-label and chips in dollars', () => {
        renderView({ selectedId: 'cost', distribution: costDistribution });

        const [firstExemplar] = costDistribution.points.filter((point) => point.traceId !== null);
        expect(
          screen.getByRole('button', {
            name: `Open trace for ${formatDistributionValue(firstExemplar.value, 'USD')} request`,
          }),
        ).toBeInTheDocument();
        const p99Chip = screen.getByText('p99').parentElement as HTMLElement;
        expect(within(p99Chip).getByText(/^\$\d/)).toBeInTheDocument();
      });
    });

    it('prints an em dash for a percentile that cannot be computed', () => {
      // A distribution whose only value is non-finite: it has a point (so the scatter renders)
      // but no percentile to report.
      const nonFiniteDistribution = {
        points: [{ ts: '2026-09-19T00:00:00.000Z', value: null, traceId: null }],
      } as unknown as MetricDistribution;
      renderView({ distribution: nonFiniteDistribution });

      const p95Chip = screen.getByText('p95', { selector: 'span' }).parentElement as HTMLElement;
      expect(within(p95Chip).getByText('—')).toBeInTheDocument();
    });

    it('shows a loading state with the metric title while the distribution is fetching', () => {
      renderView({ isDistributionLoading: true });

      expect(screen.getByText('Loading distribution…')).toBeInTheDocument();
      expect(screen.queryByRole('img', { name: SCATTER_LABEL })).not.toBeInTheDocument();
      expect(screen.getByText('per request · distribution over time')).toBeInTheDocument();
    });

    it('treats a missing distribution with no error as still loading', () => {
      renderView();

      expect(screen.getByText('Loading distribution…')).toBeInTheDocument();
    });

    it('shows the error message when the distribution query failed', () => {
      renderView({ distributionError: 'distribution request failed' });

      expect(screen.getByText('distribution request failed')).toBeInTheDocument();
      expect(screen.queryByText('Loading distribution…')).not.toBeInTheDocument();
      expect(screen.queryByRole('img', { name: SCATTER_LABEL })).not.toBeInTheDocument();
    });

    it('shows "No requests in this window" when the distribution has no requests', () => {
      renderView({ distribution: { points: [] } });

      expect(screen.getByText('No requests in this window')).toBeInTheDocument();
      expect(screen.queryByRole('img', { name: SCATTER_LABEL })).not.toBeInTheDocument();
    });
  });

  describe('exemplar trace drawer', () => {
    const exemplar = tokenDistribution.points.find((point) => point.traceId !== null) as DistributionPoint;
    const exemplarTraceId = exemplar.traceId as string;

    it('stays closed until an exemplar is open', () => {
      renderView({ distribution: tokenDistribution });

      expect(screen.queryByText('Exemplar → Trace')).not.toBeInTheDocument();
    });

    it('opens on the exemplar, naming its trace and the request the dot was drawn for', async () => {
      renderView({ distribution: tokenDistribution, openTraceId: exemplarTraceId, isExemplarLoading: true });

      expect(await screen.findByText('Exemplar → Trace')).toBeInTheDocument();
      expect(screen.getByText(`trace ${exemplarTraceId}`)).toBeInTheDocument();
      expect(screen.getByText('claude_code.token.usage', { selector: 'b' })).toBeInTheDocument();
      expect(screen.getByText(formatDistributionValue(exemplar.value, 'tokens'), { selector: 'div' })).toBeInTheDocument();
      expect(screen.getByText('Loading trace…')).toBeInTheDocument();
    });

    it('forwards the close and Open in Traces actions', async () => {
      const user = userEvent.setup();
      const onCloseTrace = vi.fn();
      const onOpenTraceInTraces = vi.fn();
      renderView({
        distribution: tokenDistribution,
        openTraceId: exemplarTraceId,
        onCloseTrace,
        onOpenTraceInTraces,
      });

      await user.click(await screen.findByRole('button', { name: /Open in Traces/ }));
      expect(onOpenTraceInTraces).toHaveBeenCalledWith(exemplarTraceId, null);

      await user.click(screen.getByRole('button', { name: 'Close exemplar trace' }));
      expect(onCloseTrace).toHaveBeenCalledTimes(1);
    });
  });
});
