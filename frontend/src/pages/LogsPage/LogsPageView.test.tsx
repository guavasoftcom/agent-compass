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
import type { LogRow } from '../../api';
import type { HistogramBucket, LogCursorPage, LogFacets, LogHistogram, LogsListResult } from './logsApi';

const logRow: LogRow = {
  id: 1,
  timestamp: '2026-08-30T10:00:00.000Z',
  severityNumber: null,
  severityText: 'ERROR',
  body: 'Tool call failed with a retryable error',
  scopeName: 'claude-code',
  traceId: 'trace-1',
  spanId: 'span-1',
  attributes: {
    'event.name': 'api_error',
    tool_name: 'Bash',
  },
  resourceAttributes: null,
};

const histogramFixture: LogHistogram = {
  bucketMs: 60_000,
  buckets: [
    { t0: '2026-08-30T09:59:00.000Z', t1: '2026-08-30T10:00:00.000Z', ERROR: 1, WARN: 0, INFO: 3, DEBUG: 0 } satisfies HistogramBucket,
  ],
};

const facetsFixture: LogFacets = {
  severity: [{ value: 'ERROR', count: 1 }],
  event: [{ value: 'api_error', count: 1 }],
  tool: [{ value: 'Bash', count: 1 }],
};

const cursorPageFixture: LogCursorPage = {
  items: [logRow],
  nextCursor: null,
  hasMore: false,
  totalCount: 1,
};

const tablePageFixture: LogsListResult = {
  items: [logRow],
  totalCount: 1,
};

const fetchLogHistogram = vi.fn().mockResolvedValue(histogramFixture);
const fetchLogFacets = vi.fn().mockResolvedValue(facetsFixture);
const fetchLogsCursor = vi.fn().mockResolvedValue(cursorPageFixture);
const fetchLogsPage = vi.fn().mockResolvedValue(tablePageFixture);

vi.mock('./logsApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./logsApi')>();
  return {
    ...actual,
    fetchLogHistogram: (...args: unknown[]) => fetchLogHistogram(...args),
    fetchLogFacets: (...args: unknown[]) => fetchLogFacets(...args),
    fetchLogsCursor: (...args: unknown[]) => fetchLogsCursor(...args),
    fetchLogsPage: (...args: unknown[]) => fetchLogsPage(...args),
  };
});

// Imported after the mock so the view picks up the mocked fetchers.
const { default: LogsPageView } = await import('./LogsPageView');

const baseProps = {
  selection: { kind: 'preset' as const, minutes: 1440 },
  onSelectionChange: vi.fn(),
  windows: [{ label: 'Last 24 hours', value: 1440 }],
  startTimestamp: '2026-08-29T10:00:00.000Z',
  endTimestamp: '2026-08-30T10:01:00.000Z',
  windowLabel: 'Last 24 hours',
  error: null,
  onReload: vi.fn(),
  autoRefresh: false,
  onAutoRefreshChange: vi.fn(),
  isPolling: false,
};

describe('LogsPageView', () => {
  it('renders stream rows, facets, and the events counter once the mocked queries resolve', async () => {
    renderWithProviders(<LogsPageView {...baseProps} />);

    expect(await screen.findByText('Tool call failed with a retryable error')).toBeInTheDocument();
    expect(screen.getAllByText('Bash').length).toBeGreaterThan(0);
    expect(screen.getAllByText('api_error').length).toBeGreaterThan(0);
    expect(screen.getAllByText('1').length).toBeGreaterThan(0);

    expect(fetchLogHistogram).toHaveBeenCalled();
    expect(fetchLogFacets).toHaveBeenCalled();
    expect(fetchLogsCursor).toHaveBeenCalled();
  });

  it('surfaces the PageLayout error slot when the container reports an error', async () => {
    renderWithProviders(<LogsPageView {...baseProps} error={new Error('logs explorer boom')} />);

    expect(await screen.findByText('logs explorer boom')).toBeInTheDocument();
  });
});
