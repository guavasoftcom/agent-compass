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
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import ToolCallsPageView, {
  type ToolCallsPageViewProps,
  type ToolCallRowWithShare,
} from './ToolCallsPageView';
import type { ToolCallTimeseries, ToolLatencyRow } from '../../api';

const rowsWithShare: ToolCallRowWithShare[] = [
  { tool: 'Read', calls: 100, share: 62.5 },
  { tool: 'Bash', calls: 60, share: 37.5 },
];

const latencyRows: ToolLatencyRow[] = [
  { tool: 'Read', calls: 100, p50Ms: 120, p95Ms: 450 },
  { tool: 'Bash', calls: 60, p50Ms: 300, p95Ms: 1800 },
];

const timeseries: ToolCallTimeseries = {
  bucketSeconds: 3600,
  tools: ['Read', 'Bash'],
  points: [
    { timestamp: '2026-08-30T00:00:00Z', counts: [40, 20] },
    { timestamp: '2026-08-30T01:00:00Z', counts: [60, 40] },
  ],
};

const baseProps: ToolCallsPageViewProps = {
  rowsWithShare,
  total: 160,
  hasData: true,
  isLoading: false,
  error: null,
  timeseries,
  isTimeseriesLoading: false,
  latencyRows,
  isLatencyLoading: false,
};

describe('ToolCallsPageView', () => {
  it('renders the stats row, latency card, and call mix donut from a populated fixture', () => {
    renderWithProviders(<ToolCallsPageView {...baseProps} />);

    expect(screen.getAllByText('160').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Read').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Bash').length).toBeGreaterThan(0);
    expect(screen.getByText('Call mix')).toBeInTheDocument();
    expect(screen.getByText('Latency per tool (s)')).toBeInTheDocument();
    expect(screen.getByText('Calls over time')).toBeInTheDocument();
  });

  it('shows empty states across the sub-cards when there is no data', () => {
    renderWithProviders(
      <ToolCallsPageView
        {...baseProps}
        rowsWithShare={[]}
        total={0}
        hasData={false}
        timeseries={null}
        latencyRows={[]}
      />,
    );

    expect(screen.getAllByText('0').length).toBeGreaterThan(0);
    expect(screen.getByText('No tool-scope spans in this window.')).toBeInTheDocument();
    expect(screen.getAllByText('No data in this window.').length).toBeGreaterThan(0);
  });

  it('surfaces the PageLayout error slot when the queries have failed', () => {
    renderWithProviders(<ToolCallsPageView {...baseProps} error={new Error('boom')} />);

    expect(screen.getByText('boom')).toBeInTheDocument();
  });
});
