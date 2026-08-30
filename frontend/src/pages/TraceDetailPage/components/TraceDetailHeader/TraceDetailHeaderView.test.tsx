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
import { renderWithProviders } from '../../../../test/renderWithProviders';
import TraceDetailHeaderView, {
  type TraceDetailHeaderViewProps,
} from './TraceDetailHeaderView';
import type { TokenBreakdown } from '../../../TracesPage/tokenBreakdown';
import type { OpGroup } from './SummaryStrip';

const tokenBreakdown: TokenBreakdown = {
  input: 42_000,
  output: 3_000,
  cacheCreate: 9_000,
  cacheRead: 1_100_000,
  total: 1_154_000,
};

const shownOperations: OpGroup[] = [
  { name: 'claude_code.interaction', selfTimeMs: 400, count: 1, errorCount: 0 },
  { name: 'claude_code.tool', selfTimeMs: 100, count: 3, errorCount: 1 },
];

const baseProps: TraceDetailHeaderViewProps = {
  traceId: 'trace-0102',
  sessionId: 'session-abc',
  rootName: 'claude_code.interaction',
  earliestStartMs: Date.parse('2026-08-30T10:00:00.000Z'),
  totalMs: 500,
  errorCount: 2,
  spanCount: 31,
  serviceLabels: ['claude-code'],
  tokenBreakdown,
  modelCallCount: 3,
  toolCallCount: 12,
  maximumDepth: 4,
  totalCostUsd: 0.42,
  backgroundCostUsd: 0,
  shownOperations,
  opCount: 2,
  firstUserPrompt: 'Refactor the Aurora theme overlay so it applies cleanly.',
};

describe('TraceDetailHeaderView', () => {
  it('renders the breadcrumb, KPI tiles, and the first user prompt from props', () => {
    renderWithProviders(<TraceDetailHeaderView {...baseProps} />);

    expect(screen.getByText('Trace detail')).toBeInTheDocument();
    expect(screen.getByText('Traces')).toBeInTheDocument();
    expect(
      screen.getByText('Refactor the Aurora theme overlay so it applies cleanly.'),
    ).toBeInTheDocument();
    expect(screen.getAllByText('$0.420').length).toBeGreaterThan(0);
    expect(screen.getByText('31')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('shows the background-cost info tooltip only when backgroundCostUsd is positive', () => {
    renderWithProviders(
      <TraceDetailHeaderView {...baseProps} backgroundCostUsd={0.1} />,
    );

    expect(
      screen.getByLabelText(/billed after this trace's own root span closed/),
    ).toBeInTheDocument();
  });

  it('shows a dash for the Errors tile when there are no errors and hides the prompt row', () => {
    renderWithProviders(
      <TraceDetailHeaderView
        {...baseProps}
        errorCount={0}
        firstUserPrompt={null}
      />,
    );

    expect(screen.getByText('—')).toBeInTheDocument();
    expect(
      screen.queryByText('Refactor the Aurora theme overlay so it applies cleanly.'),
    ).not.toBeInTheDocument();
  });
});
