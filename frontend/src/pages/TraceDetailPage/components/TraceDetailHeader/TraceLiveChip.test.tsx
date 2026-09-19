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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen } from '@testing-library/react';
import { renderWithProviders } from '../../../../test/renderWithProviders';
import TraceLiveChip from './TraceLiveChip';

describe('TraceLiveChip', () => {
  const startMs = Date.parse('2026-08-30T10:00:00.000Z');

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(startMs + 5000);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders the status role/aria-label and a ticking elapsed-time label', () => {
    renderWithProviders(<TraceLiveChip earliestStartMs={startMs} />);

    expect(screen.getByRole('status', { name: 'Trace still running' })).toBeInTheDocument();
    expect(screen.getByText('LIVE')).toBeInTheDocument();
    expect(screen.getByText('5.00 s')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(1000);
    });

    expect(screen.getByText('6.00 s')).toBeInTheDocument();
  });
});
