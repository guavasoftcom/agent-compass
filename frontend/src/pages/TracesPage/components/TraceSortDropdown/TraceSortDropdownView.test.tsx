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
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../../test/renderWithProviders';
import TraceSortDropdownView, {
  type TraceSortDropdownViewProps,
} from './TraceSortDropdownView';

const baseProps: TraceSortDropdownViewProps = {
  sort: 'new',
  isOpen: false,
  onToggleOpen: vi.fn(),
  onClose: vi.fn(),
  onSelect: vi.fn(),
};

describe('TraceSortDropdownView', () => {
  it('renders the current sort label and hides the menu when closed', () => {
    renderWithProviders(<TraceSortDropdownView {...baseProps} />);

    expect(screen.getByText('Newest')).toBeInTheDocument();
    expect(screen.queryByText('Slowest first')).not.toBeInTheDocument();
  });

  it('renders every sort option when open, marking the active one', () => {
    renderWithProviders(<TraceSortDropdownView {...baseProps} isOpen sort="slow" />);

    expect(screen.getByText('Slowest first')).toBeInTheDocument();
    expect(screen.getByText('Fastest first')).toBeInTheDocument();
    expect(screen.getByText('Most spans')).toBeInTheDocument();
    expect(screen.getByText('Highest cost')).toBeInTheDocument();
    expect(screen.getByText('Errors first')).toBeInTheDocument();
  });

  it('calls onToggleOpen when the trigger button is clicked', async () => {
    const user = userEvent.setup();
    const onToggleOpen = vi.fn();
    renderWithProviders(
      <TraceSortDropdownView {...baseProps} onToggleOpen={onToggleOpen} />,
    );

    await user.click(screen.getByText('Newest'));

    expect(onToggleOpen).toHaveBeenCalledTimes(1);
  });

  it('calls onSelect with the chosen sort key when a menu option is clicked', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    renderWithProviders(
      <TraceSortDropdownView {...baseProps} isOpen onSelect={onSelect} />,
    );

    await user.click(screen.getByText('Most tokens'));

    expect(onSelect).toHaveBeenCalledWith('tokens');
  });
});
