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
import TraceHistogramView, {
  type TraceHistogramViewProps,
} from './TraceHistogramView';
import type { TraceHistogram, TraceHistogramBucket } from '../../tracesApi';

const buckets: TraceHistogramBucket[] = [
  { t0: '2026-08-30T10:00:00.000Z', t1: '2026-08-30T10:05:00.000Z', ok: 12, error: 1, p95Ms: 820 },
  { t0: '2026-08-30T10:05:00.000Z', t1: '2026-08-30T10:10:00.000Z', ok: 18, error: 3, p95Ms: 1200 },
  { t0: '2026-08-30T10:10:00.000Z', t1: '2026-08-30T10:15:00.000Z', ok: 20, error: 0, p95Ms: 640 },
];

const histogramData: TraceHistogram = {
  bucketMs: 300000,
  buckets,
  p50Ms: 400,
  p95Ms: 1200,
  total: 54,
  errorCount: 4,
};

const baseProps: TraceHistogramViewProps = {
  data: histogramData,
  hiddenSeries: new Set(),
  windowLabel: 'Last 1 hour',
  onToggleSeries: vi.fn(),
  onBarClick: vi.fn(),
};

describe('TraceHistogramView', () => {
  it('renders the window label, p50/p95 header stats, and the legend', () => {
    renderWithProviders(<TraceHistogramView {...baseProps} />);

    expect(screen.getByText(/Trace throughput/)).toBeInTheDocument();
    expect(screen.getByText(/Last 1 hour/)).toBeInTheDocument();
    expect(screen.getByText('OK')).toBeInTheDocument();
    expect(screen.getByText('Error')).toBeInTheDocument();
    expect(screen.getByText('p95 latency')).toBeInTheDocument();
  });

  it('renders with an empty bucket list without crashing', () => {
    renderWithProviders(
      <TraceHistogramView
        {...baseProps}
        data={{ bucketMs: 60000, buckets: [], p50Ms: 0, p95Ms: 0, total: 0, errorCount: 0 }}
      />,
    );

    expect(screen.getByText(/Trace throughput/)).toBeInTheDocument();
  });

  it('renders when data is undefined (loading state)', () => {
    renderWithProviders(<TraceHistogramView {...baseProps} data={undefined} />);

    expect(screen.getByText(/Trace throughput/)).toBeInTheDocument();
  });

  it('calls onToggleSeries with the series id when a legend item is clicked', async () => {
    const user = userEvent.setup();
    const onToggleSeries = vi.fn();
    renderWithProviders(
      <TraceHistogramView {...baseProps} onToggleSeries={onToggleSeries} />,
    );

    await user.click(screen.getByText('Error'));

    expect(onToggleSeries).toHaveBeenCalledWith('error');
  });
});
