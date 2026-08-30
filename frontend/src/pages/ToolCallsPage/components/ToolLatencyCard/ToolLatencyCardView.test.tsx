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
import ToolLatencyCardView, {
  type LatencyBarSeries,
  type ToolLatencyCardViewProps,
} from './ToolLatencyCardView';

const series: LatencyBarSeries[] = [
  {
    label: 'Typical',
    data: [0.12, 0.45],
    stack: 'latency',
    color: '#5AD1E6',
    valueFormatter: (value) => `${value ?? 0}`,
  },
  {
    label: 'Worst 5%',
    data: [0.08, 1.3],
    stack: 'latency',
    color: '#E65AC9',
    valueFormatter: (value) => `${value ?? 0}`,
  },
];

const baseProps: ToolLatencyCardViewProps = {
  toolLabels: ['Read', 'Bash'],
  series,
  hasData: true,
  isLoading: false,
  height: 200,
  yAxisWidth: 80,
};

describe('ToolLatencyCardView', () => {
  it('renders a bar row per tool with the totaled latency label', () => {
    renderWithProviders(<ToolLatencyCardView {...baseProps} />);

    expect(screen.getByText('Read')).toBeInTheDocument();
    expect(screen.getByText('Bash')).toBeInTheDocument();
    // Read: 0.12 + 0.08 = 0.20s -> "200 ms"; Bash: 0.45 + 1.3 = 1.75s -> "1.75 s"
    expect(screen.getByText('200 ms')).toBeInTheDocument();
    expect(screen.getByText('1.75 s')).toBeInTheDocument();
    expect(screen.getByText('Typical')).toBeInTheDocument();
    expect(screen.getByText('Worst 5%')).toBeInTheDocument();
  });

  it('shows the empty-state message when there is no tool-scope span data', () => {
    renderWithProviders(
      <ToolLatencyCardView
        {...baseProps}
        toolLabels={[]}
        series={[]}
        hasData={false}
      />,
    );

    expect(screen.getByText('No tool-scope spans in this window.')).toBeInTheDocument();
  });

  it('does not show the empty-state message while still loading with no data yet', () => {
    renderWithProviders(
      <ToolLatencyCardView
        {...baseProps}
        toolLabels={[]}
        series={[]}
        hasData={false}
        isLoading
      />,
    );

    expect(screen.queryByText('No tool-scope spans in this window.')).not.toBeInTheDocument();
  });
});
