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
import McpServersPageView, { type McpServersPageViewProps } from './McpServersPageView';
import type { McpServerUsageRow } from '../../api';
import type { McpServerRollupWithShare } from './mcpDerivations';

const toolRows: McpServerUsageRow[] = [
  {
    server: 'playwright',
    tool: 'browser_click',
    calls: 40,
    failures: 2,
    failureRate: 0.05,
    avgDurationMs: 120,
    p95DurationMs: 450,
    totalBytes: 2048,
    estimatedTokens: 512,
  },
];

const servers: McpServerRollupWithShare[] = [
  {
    server: 'playwright',
    calls: 40,
    failures: 2,
    failureRate: 0.05,
    avgDurationMs: 120,
    p95DurationMs: 450,
    totalBytes: 2048,
    estimatedTokens: 512,
    toolCount: 1,
    share: 100,
  },
];

const baseProps: McpServersPageViewProps = {
  toolRows,
  servers,
  totalCalls: 40,
  totalFailures: 2,
  totalContextBytes: 2048,
  totalEstimatedTokens: 512,
  slowestServer: servers[0],
  serverColorIndexes: new Map([['playwright', 0]]),
  isLoading: false,
  error: null,
};

describe('McpServersPageView', () => {
  it('renders KPI tiles and the per-tool detail row from props', () => {
    renderWithProviders(<McpServersPageView {...baseProps} />);

    expect(screen.getAllByText('40').length).toBeGreaterThan(0);
    expect(screen.getAllByText('playwright').length).toBeGreaterThan(0);
    expect(screen.getByText('browser_click')).toBeInTheDocument();
  });

  it('shows an empty state for the server ranking and detail table when there are no calls', () => {
    renderWithProviders(
      <McpServersPageView
        {...baseProps}
        toolRows={[]}
        servers={[]}
        totalCalls={0}
        totalFailures={0}
        slowestServer={null}
        serverColorIndexes={new Map()}
      />,
    );

    expect(screen.getAllByText('No MCP calls in this window.').length).toBeGreaterThan(0);
  });

  it('surfaces the PageLayout error slot when the query has failed', () => {
    renderWithProviders(<McpServersPageView {...baseProps} error={new Error('boom')} />);

    expect(screen.getByText('boom')).toBeInTheDocument();
  });
});
