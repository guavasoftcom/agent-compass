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
import ToolReliabilityPageView, {
  type ToolReliabilityPageViewProps,
} from './ToolReliabilityPageView';
import type { ToolFailureRateRow, ToolRepeatStatRow } from '../../api';

const rows: ToolFailureRateRow[] = [
  { tool: 'Bash', calls: 200, failures: 12, failureRate: 0.06 },
  { tool: 'Read', calls: 500, failures: 0, failureRate: 0 },
];

const repeatRows: ToolRepeatStatRow[] = [
  {
    tool: 'Edit',
    scope: '/Users/guadalupegarcia/Projects/repo/src/App.tsx',
    medianRunLength: 3,
    maxRunLength: 8,
    sessions: 4,
  },
];

const baseProps: ToolReliabilityPageViewProps = {
  rows,
  totalCalls: 700,
  totalFailures: 12,
  overallRate: 12 / 700,
  worstTool: rows[0],
  minCallsForRanking: 5,
  isLoading: false,
  error: null,
  repeatRows,
  isRepeatsLoading: false,
};

describe('ToolReliabilityPageView', () => {
  it('renders KPI tiles and the failing-tool ranking from props', () => {
    renderWithProviders(<ToolReliabilityPageView {...baseProps} />);

    expect(screen.getByText('Overall failure rate')).toBeInTheDocument();
    expect(screen.getAllByText('Bash').length).toBeGreaterThan(0);
    expect(screen.getByText('12 / 200')).toBeInTheDocument();
  });

  it('collapses zero-failure tools into a closed disclosure, not a ranked bar', () => {
    renderWithProviders(<ToolReliabilityPageView {...baseProps} />);

    expect(
      screen.getByText('1 tools with no failures · 500 calls'),
    ).toBeInTheDocument();
  });

  it('shows the empty state for both cards when there is no data in the window', () => {
    renderWithProviders(
      <ToolReliabilityPageView
        {...baseProps}
        rows={[]}
        totalCalls={0}
        totalFailures={0}
        overallRate={0}
        worstTool={null}
      />,
    );

    expect(screen.getAllByText('No data in this window.').length).toBe(2);
  });

  it('surfaces the PageLayout error slot when a query has failed', () => {
    renderWithProviders(
      <ToolReliabilityPageView {...baseProps} error={new Error('boom')} />,
    );

    expect(screen.getByText('boom')).toBeInTheDocument();
  });
});
