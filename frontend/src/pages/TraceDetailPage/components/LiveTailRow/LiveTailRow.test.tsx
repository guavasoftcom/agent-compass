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
import LiveTailRow from './LiveTailRow';

describe('LiveTailRow', () => {
  it('renders the waiting label and the running-indicator status for a given left/right', () => {
    renderWithProviders(
      <LiveTailRow gridColumns="minmax(220px, 40%) 1fr" left={12} right={38} />,
    );

    expect(screen.getByText('waiting for more spans…')).toBeInTheDocument();
    expect(
      screen.getByRole('status', { name: 'More spans are still arriving' }),
    ).toBeInTheDocument();
    expect(screen.getByText('running…')).toBeInTheDocument();
  });
});
