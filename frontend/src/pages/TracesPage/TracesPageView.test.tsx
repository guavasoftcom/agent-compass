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
import TracesPageView from './TracesPageView';
import { TracesExplorerContext, type TracesExplorerContextValue } from './TracesExplorerContext';
import type { TraceRow } from '../../api';

const traceRows: TraceRow[] = [
  {
    traceId: 'trace-1',
    startTimestamp: '2026-08-30T10:00:00.000Z',
    rootSpanName: 'claude_code.interaction',
    rootSpanId: 'span-1',
    sessionId: 'session-1',
    spanCount: 12,
    durationNanos: 4_500_000_000,
    errorCount: 0,
    totalTokens: 1200,
    totalCostUsd: 0.42,
    firstUserPrompt: 'Fix the flaky test suite',
  },
];

const buildContextValue = (
  overrides: Partial<TracesExplorerContextValue> = {},
): TracesExplorerContextValue => ({
  // filter state
  search: '',
  onSearchChange: vi.fn(),
  facetSelections: {
    status: new Set(),
    operation: new Set(),
    service: new Set(),
    duration: new Set(),
    session: new Set(),
  },
  toggleFacet: vi.fn(),
  clearFacet: vi.fn(),
  serviceForValue: vi.fn(() => null),
  zoom: null,
  clearZoom: vi.fn(),
  clearAllFilters: vi.fn(),
  zoomToBucket: vi.fn(),

  // view / sort / histogram / expansion
  view: 'stream',
  onViewChange: vi.fn(),
  sort: 'new',
  onSortChange: vi.fn(),
  hiddenHistogramSeries: new Set(),
  toggleHistogramSeries: vi.fn(),
  expanded: new Set(),
  toggleExpand: vi.fn(),

  // query results
  histogramData: {
    bucketMs: 60_000,
    buckets: [
      { t0: '2026-08-30T09:59:00.000Z', t1: '2026-08-30T10:00:00.000Z', ok: 5, error: 0, p95Ms: 900 },
    ],
    p50Ms: 400,
    p95Ms: 900,
    total: 5,
    errorCount: 0,
  },
  facetsData: {
    status: [{ value: 'ok', count: 5 }],
    operation: [{ value: 'claude_code.interaction', count: 5 }],
    service: [{ value: 'claude-code', count: 5 }],
    duration: [{ value: 'd1', count: 5 }],
    session: [{ value: 'session-1', count: 5 }],
  },

  // stream
  streamRows: traceRows,
  streamLoading: false,
  streamHasMore: false,
  streamTotal: traceRows.length,
  loadMore: vi.fn(),

  // table
  tableRows: traceRows,
  tableTotal: traceRows.length,
  tableLoading: false,
  page: 0,
  pageSize: 50,
  onTablePageChange: vi.fn(),
  onTablePageSizeChange: vi.fn(),

  // derived flags
  totalCount: traceRows.length,
  tail: false,
  tailLocked: false,
  tailTip: undefined,
  toggleTail: vi.fn(),

  // window chrome
  selection: { kind: 'preset', minutes: 1440 },
  onSelectionChange: vi.fn(),
  windows: [{ label: 'Last 24 hours', value: 1440 }],
  windowLabel: 'Last 24 hours',
  error: null,
  onReload: vi.fn(),
  autoRefresh: false,
  onAutoRefreshChange: vi.fn(),
  isPolling: false,

  ...overrides,
});

const renderView = (overrides: Partial<TracesExplorerContextValue> = {}) => {
  const value = buildContextValue(overrides);
  renderWithProviders(
    <TracesExplorerContext.Provider value={value}>
      <TracesPageView />
    </TracesExplorerContext.Provider>,
  );
  return value;
};

describe('TracesPageView', () => {
  it('renders the page chrome and the stream view fed from context', () => {
    renderView();

    expect(screen.getByText('Traces')).toBeInTheDocument();
    expect(screen.getAllByText('claude_code.interaction').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Fix the flaky test suite').length).toBeGreaterThan(0);
  });

  it('calls the context reload handler when the reload button is clicked', async () => {
    const user = userEvent.setup();
    const value = renderView();

    const reloadButton = screen.getByRole('button', { name: 'Refresh' });
    await user.click(reloadButton);

    expect(value.onReload).toHaveBeenCalledTimes(1);
  });

  it('surfaces the PageLayout error slot when the context carries an error', () => {
    renderView({ error: new Error('trace explorer boom') });

    expect(screen.getByText('trace explorer boom')).toBeInTheDocument();
  });

  it('switches to the table view when the view toggle changes', async () => {
    const user = userEvent.setup();
    const value = renderView({ view: 'table' });

    // Table view renders TraceTable instead of the facet rail + stream grid.
    expect(screen.queryByPlaceholderText(/search trace/i)).not.toBeInTheDocument();

    // Sanity: clicking the Stream toggle option calls onViewChange from context.
    const streamToggle = screen.getByRole('button', { name: /stream/i });
    await user.click(streamToggle);
    expect(value.onViewChange).toHaveBeenCalled();
  });
});
