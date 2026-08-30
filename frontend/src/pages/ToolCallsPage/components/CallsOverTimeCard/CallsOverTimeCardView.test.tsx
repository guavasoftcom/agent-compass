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
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../../test/renderWithProviders';
import CallsOverTimeCardView, {
  type CallsOverTimeCardViewProps,
  type LineSeries,
} from './CallsOverTimeCardView';

const series: LineSeries[] = [
  {
    label: 'Read',
    data: [4, 6, 5],
    area: true,
    stack: 'tools',
    showMark: false,
    color: '#5AD1E6',
  },
  {
    label: 'Bash',
    data: [1, 2, 3],
    area: true,
    stack: 'tools',
    showMark: false,
    color: '#E65AC9',
  },
];

const baseProps: CallsOverTimeCardViewProps = {
  axisDates: [
    new Date('2026-08-30T00:00:00Z'),
    new Date('2026-08-30T01:00:00Z'),
    new Date('2026-08-30T02:00:00Z'),
  ],
  series,
  hasData: true,
  isLoading: false,
  emptyMessage: 'No data in this window.',
};

describe('CallsOverTimeCardView', () => {
  it('renders the chart title and an interactive legend entry per tool', () => {
    renderWithProviders(<CallsOverTimeCardView {...baseProps} />);

    expect(screen.getByText('Calls over time')).toBeInTheDocument();
    expect(screen.getByText('Read')).toBeInTheDocument();
    expect(screen.getByText('Bash')).toBeInTheDocument();
  });

  it('shows the empty-data message and no legend when there is no data', () => {
    renderWithProviders(
      <CallsOverTimeCardView
        {...baseProps}
        axisDates={[]}
        series={[]}
        hasData={false}
      />,
    );

    expect(screen.getByText('No data in this window.')).toBeInTheDocument();
    expect(screen.queryByText('Read')).not.toBeInTheDocument();
  });

  it('shows the single-bucket empty message instead of the generic one when provided', () => {
    renderWithProviders(
      <CallsOverTimeCardView
        {...baseProps}
        axisDates={[new Date('2026-08-30T00:00:00Z')]}
        hasData={false}
        emptyMessage="Only one bucket in this window — need at least two to plot a trend."
      />,
    );

    expect(
      screen.getByText('Only one bucket in this window — need at least two to plot a trend.'),
    ).toBeInTheDocument();
  });

  it('toggles a series off when its legend entry is clicked', async () => {
    const user = userEvent.setup();
    renderWithProviders(<CallsOverTimeCardView {...baseProps} />);

    const readLegendItem = screen.getByText('Read').closest('[role="button"]');
    expect(readLegendItem).not.toBeNull();
    expect(readLegendItem).toHaveStyle({ opacity: '1' });

    await user.click(readLegendItem as HTMLElement);

    expect(readLegendItem).toHaveStyle({ opacity: '0.42' });
  });
});
