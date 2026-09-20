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
import Sparkline from './Sparkline';

describe('Sparkline', () => {
  it('renders nothing for an empty series', () => {
    renderWithProviders(<Sparkline values={[]} />);
    expect(screen.queryByTestId('sparkline-bar')).not.toBeInTheDocument();
  });

  it('draws one bar per value and no placeholders by default', () => {
    renderWithProviders(<Sparkline values={[1, 2, 3]} />);
    expect(screen.getAllByTestId('sparkline-bar')).toHaveLength(3);
    expect(screen.queryByTestId('sparkline-placeholder-bar')).not.toBeInTheDocument();
  });

  it('turns every bar from placeholderFromIndex on into a placeholder', () => {
    renderWithProviders(<Sparkline values={[4, 2, 0, 0]} placeholderFromIndex={2} />);
    expect(screen.getAllByTestId('sparkline-placeholder-bar')).toHaveLength(2);
  });

  it('keeps future placeholder values out of the normalization', () => {
    // Without the exclusion the 1000 would flatten the two real bars to the 8% floor.
    renderWithProviders(<Sparkline values={[10, 5, 1000]} placeholderFromIndex={2} />);
    const bars = screen.getAllByTestId('sparkline-bar');
    expect(getComputedStyle(bars[0]).height).toBe('100%');
    expect(getComputedStyle(bars[1]).height).toBe('50%');
  });

  it('gives placeholders a fixed two-pixel height regardless of their value', () => {
    renderWithProviders(<Sparkline values={[10, 999]} placeholderFromIndex={1} />);
    expect(getComputedStyle(screen.getByTestId('sparkline-placeholder-bar')).height).toBe('2px');
  });
});
