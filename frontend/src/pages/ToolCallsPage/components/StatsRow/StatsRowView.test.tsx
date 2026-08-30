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
import StatsRowView, { type StatsRowViewProps } from './StatsRowView';

const baseProps: StatsRowViewProps = {
  total: 240,
  distinctCount: 4,
  topTool: 'Read',
  topShare: 41.7,
  slowestTool: 'Bash',
  slowestP95Ms: 1850,
  toolNames: ['Read', 'Edit', 'Bash', 'Write'],
  spark: [10, 20, 15, 30, 25],
};

describe('StatsRowView', () => {
  it('renders the four KPI tiles from a populated props fixture', () => {
    renderWithProviders(<StatsRowView {...baseProps} />);

    expect(screen.getByText('240')).toBeInTheDocument();
    expect(screen.getAllByText('Read').length).toBeGreaterThan(0);
    expect(screen.getByText('41.7%')).toBeInTheDocument();
    expect(screen.getAllByText('4').length).toBeGreaterThan(0);
    expect(screen.getByText('Read · Edit · Bash')).toBeInTheDocument();
    expect(screen.getByText('1.85 s')).toBeInTheDocument();
    expect(screen.getAllByText('Bash').length).toBeGreaterThan(0);
  });

  it('falls back to placeholders when there is no data', () => {
    renderWithProviders(
      <StatsRowView
        total={0}
        distinctCount={0}
        topTool={null}
        topShare={null}
        slowestTool={null}
        slowestP95Ms={null}
        toolNames={[]}
        spark={[]}
      />,
    );

    expect(screen.getAllByText('0').length).toBeGreaterThan(0);
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });

  it('formats a sub-second p95 latency in milliseconds', () => {
    renderWithProviders(<StatsRowView {...baseProps} slowestP95Ms={420} />);

    expect(screen.getByText('420 ms')).toBeInTheDocument();
  });
});
