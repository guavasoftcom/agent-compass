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
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import SessionsPageView, {
  type SessionsPageViewProps,
} from './SessionsPageView';
import type { SessionPromptRow, SessionSummaryRow } from '../../api';
import { WINDOWS } from '../../lib/constants';

// SessionsPageView always passes onRepositoryUrlChange, so PageActions renders
// RepositorySelector, which fetches the repository list itself (it is not
// window-scoped and has no page-level query to stub via props). Stub the
// fetcher rather than let it hit the network in jsdom.
vi.mock('../../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api')>();
  return { ...actual, fetchRepositories: vi.fn().mockResolvedValue([]) };
});

const rows: SessionSummaryRow[] = [
  {
    sessionId: '690cb902-1234-4e02-9a71-9ddc290d9200',
    costUsd: 4.38,
    activeTimeSeconds: 1800,
    startTimestamp: '2026-08-29T20:26:00.000Z',
    endTimestamp: '2026-08-29T20:56:00.000Z',
    wallSeconds: 3600,
    toolCallCount: 42,
    denialCount: 1,
    tokens: 5_300_000,
    tokenBreakdown: {
      input: 100_000,
      output: 50_000,
      cacheCreation: 150_000,
      cacheRead: 5_000_000,
    },
    terminalType: 'interactive',
    startType: 'fresh',
    firstUserPrompt: 'apply the design handoff to the sessions page',
    userPromptCount: 12,
    inProgress: false,
  },
];

const finishedTurn: SessionPromptRow = {
  timestamp: '2026-08-29T20:26:55.000Z',
  prompt: 'apply the design handoff to the sessions page',
  traceId: '0102030405060708090a0b0c0d0e0f10',
  model: 'claude-opus-5',
  costUsd: 0.41,
  tools: [{ name: 'Edit', count: 3 }],
  attribution: 'REQUEST',
  requestCount: 4,
  inProgress: false,
};

const baseProps: SessionsPageViewProps = {
  selection: { kind: 'preset', minutes: 1440 },
  onSelectionChange: vi.fn(),
  windows: WINDOWS,
  repositoryUrl: null,
  onRepositoryUrlChange: vi.fn(),
  rows,
  rowCount: 1,
  paginationModel: { page: 0, pageSize: 25 },
  onPaginationModelChange: vi.fn(),
  sortModel: { field: 'endTimestamp', direction: 'desc' },
  onSortModelChange: vi.fn(),
  kpis: {
    totalSessions: 1,
    medianCostUsd: 4.38,
    p95CostUsd: 8,
    medianCostPerActiveMinuteUsd: 0.15,
    sessionsTrend: [1, 2, 1],
  },
  isLoading: false,
  error: null,
  onReload: vi.fn(),
  autoRefresh: false,
  onAutoRefreshChange: vi.fn(),
  isPolling: false,
  openSessionId: null,
  onToggleSessionDetail: vi.fn(),
  onCloseSessionDetail: vi.fn(),
  promptTimeline: null,
  promptTimelineLoading: false,
  promptTimelineError: null,
};

describe('SessionsPageView', () => {
  it('renders the KPI strip and the sessions table row from props', () => {
    renderWithProviders(<SessionsPageView {...baseProps} />);

    expect(
      screen.getByText('apply the design handoff to the sessions page'),
    ).toBeInTheDocument();
    expect(screen.getAllByText('42').length).toBeGreaterThan(0);
  });

  it('shows a running indicator on the grid row of an in-progress session', () => {
    renderWithProviders(
      <SessionsPageView
        {...baseProps}
        rows={[{ ...rows[0], inProgress: true }]}
      />,
    );

    expect(screen.getByRole('status', { name: 'Session still running' })).toBeInTheDocument();
  });

  it('shows no running indicator once the session has finished', () => {
    renderWithProviders(<SessionsPageView {...baseProps} rows={rows} />);

    expect(screen.queryByRole('status', { name: 'Session still running' })).not.toBeInTheDocument();
  });

  it('shows an empty state when there are no sessions in the window', () => {
    renderWithProviders(
      <SessionsPageView
        {...baseProps}
        rows={[]}
        rowCount={0}
        kpis={{ ...baseProps.kpis, totalSessions: 0, sessionsTrend: [] }}
      />,
    );

    expect(screen.getByText('No sessions in this window.')).toBeInTheDocument();
  });

  it('calls onToggleSessionDetail with the clicked row session id', async () => {
    const user = userEvent.setup();
    const onToggleSessionDetail = vi.fn();
    renderWithProviders(
      <SessionsPageView
        {...baseProps}
        onToggleSessionDetail={onToggleSessionDetail}
      />,
    );

    await user.click(
      screen.getByText('apply the design handoff to the sessions page'),
    );

    expect(onToggleSessionDetail).toHaveBeenCalledWith(
      '690cb902-1234-4e02-9a71-9ddc290d9200',
    );
  });

  it('marks only the turn the backend reports as still running with a running indicator', () => {
    const promptTimeline: SessionPromptRow[] = [
      finishedTurn,
      { ...finishedTurn, timestamp: '2026-08-29T20:40:00.000Z', prompt: 'now fix the tests', inProgress: true },
    ];
    renderWithProviders(
      <SessionsPageView
        {...baseProps}
        openSessionId={rows[0].sessionId}
        promptTimeline={promptTimeline}
      />,
    );

    expect(screen.getAllByRole('status', { name: 'Prompt still running' })).toHaveLength(1);
  });

  it('shows no running indicator once every turn has finished', () => {
    renderWithProviders(
      <SessionsPageView
        {...baseProps}
        openSessionId={rows[0].sessionId}
        promptTimeline={[finishedTurn]}
      />,
    );

    expect(screen.queryByRole('status', { name: 'Prompt still running' })).not.toBeInTheDocument();
  });

  it('offers a New prompt pill when a polled turn arrives while the reader is scrolled up', () => {
    const openProps = {
      ...baseProps,
      openSessionId: rows[0].sessionId,
      promptTimeline: [finishedTurn],
    };
    const { rerender } = renderWithProviders(<SessionsPageView {...openProps} />);

    // Scroll the drawer body well away from the bottom (jsdom does no layout,
    // so give the region real dimensions first).
    const timelineRegion = screen.getByRole('region', { name: 'Session prompt timeline' });
    Object.defineProperty(timelineRegion, 'scrollHeight', { configurable: true, value: 2000 });
    Object.defineProperty(timelineRegion, 'clientHeight', { configurable: true, value: 500 });
    timelineRegion.scrollTop = 0;
    fireEvent.scroll(timelineRegion);

    expect(screen.queryByRole('button', { name: /new prompt/i })).not.toBeInTheDocument();

    rerender(
      <SessionsPageView
        {...openProps}
        promptTimeline={[
          finishedTurn,
          { ...finishedTurn, timestamp: '2026-08-29T20:40:00.000Z', prompt: 'a brand new prompt', inProgress: true },
        ]}
      />,
    );

    expect(screen.getByRole('button', { name: /new prompt/i })).toBeInTheDocument();
  });

  it('surfaces the PageLayout error slot when the query has failed', () => {
    renderWithProviders(
      <SessionsPageView {...baseProps} error={new Error('boom')} />,
    );

    expect(screen.getByText('boom')).toBeInTheDocument();
  });
});
