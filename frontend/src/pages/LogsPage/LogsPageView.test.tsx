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
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ColorModeProvider } from '../../theme/colorMode';
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
  repositoryUrl: null,
  onRepositoryUrlChange: vi.fn(),
};

// `renderWithProviders` nests its provider JSX directly, so its returned `rerender` would drop
// the providers on a rerender with new props. The repository-change cursor-reset test below needs
// a real rerender within the same mounted tree (an effect re-firing on a changed dependency, not
// a remount), so it renders through RTL's own `wrapper` option instead, which re-wraps every
// `rerender` call automatically.
const renderLogsPageViewWithRerenderableProviders = (props: typeof baseProps) => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <ColorModeProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    </ColorModeProvider>
  );
  return render(<LogsPageView {...props} />, { wrapper: Wrapper });
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

  // Mirrors how a window-selection change already resets the stream cursor: both ride the same
  // `filters` object, so both changes produce a new `filtersKey` and re-trigger the "reset stream
  // + collapse rows" effect in LogsPageView, which starts the stream over with `cursor: null`
  // rather than paging on a cursor built under the old repository's result set.
  it('resets the stream cursor (fresh cursor: null fetch) when repositoryUrl changes, the same way a window change already does', async () => {
    const { rerender } = renderLogsPageViewWithRerenderableProviders({
      ...baseProps,
      repositoryUrl: 'https://github.com/example/repo-a',
    });

    await screen.findByText('Tool call failed with a retryable error');
    const callCountBeforeRepositoryChange = fetchLogsCursor.mock.calls.length;
    expect(callCountBeforeRepositoryChange).toBeGreaterThan(0);

    rerender(<LogsPageView {...baseProps} repositoryUrl="https://github.com/example/repo-b" />);

    await waitFor(() => {
      expect(fetchLogsCursor.mock.calls.length).toBeGreaterThan(callCountBeforeRepositoryChange);
    });

    const [filtersArg, pageArg] = fetchLogsCursor.mock.calls[fetchLogsCursor.mock.calls.length - 1];
    // resetStream always requests a fresh first page, never a continuation of the old cursor.
    expect(pageArg).toEqual({ cursor: null, limit: 60 });
    expect(filtersArg).toMatchObject({ repositoryUrl: 'https://github.com/example/repo-b' });
  });
});
