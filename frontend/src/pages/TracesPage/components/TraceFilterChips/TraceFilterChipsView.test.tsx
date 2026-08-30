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
import TraceFilterChipsView, {
  type TraceFilterChip,
  type TraceFilterChipsViewProps,
} from './TraceFilterChipsView';

const chips: TraceFilterChip[] = [
  { key: 'status', value: 'error', label: 'Status: error' },
  { key: 'service', value: 'claude_code.tool', label: 'Service: claude_code.tool' },
];

const baseProps: TraceFilterChipsViewProps = {
  zoomLabel: null,
  chips,
  onRemoveChip: vi.fn(),
  onClearAll: vi.fn(),
  onClearZoom: vi.fn(),
};

describe('TraceFilterChipsView', () => {
  it('renders the zoom chip and every active filter chip from props', () => {
    renderWithProviders(
      <TraceFilterChipsView {...baseProps} zoomLabel="10:00 – 10:15" />,
    );

    expect(screen.getByText('10:00 – 10:15')).toBeInTheDocument();
    expect(screen.getByText('Status: error')).toBeInTheDocument();
    expect(screen.getByText('Service: claude_code.tool')).toBeInTheDocument();
    expect(screen.getByText('Clear all')).toBeInTheDocument();
  });

  it('renders no zoom chip and no filter chips when there is nothing active', () => {
    renderWithProviders(
      <TraceFilterChipsView {...baseProps} zoomLabel={null} chips={[]} />,
    );

    expect(screen.queryByText('Status: error')).not.toBeInTheDocument();
    expect(screen.getByText('Clear all')).toBeInTheDocument();
  });

  it('calls onRemoveChip with the clicked chip when its close icon is clicked', async () => {
    const user = userEvent.setup();
    const onRemoveChip = vi.fn();
    renderWithProviders(
      <TraceFilterChipsView {...baseProps} onRemoveChip={onRemoveChip} />,
    );

    const closeIcons = document.querySelectorAll('[data-testid="CloseIcon"]');
    await user.click(closeIcons[0].parentElement as Element);

    expect(onRemoveChip).toHaveBeenCalledWith(chips[0]);
  });

  it('calls onClearAll when "Clear all" is clicked', async () => {
    const user = userEvent.setup();
    const onClearAll = vi.fn();
    renderWithProviders(<TraceFilterChipsView {...baseProps} onClearAll={onClearAll} />);

    await user.click(screen.getByText('Clear all'));

    expect(onClearAll).toHaveBeenCalledTimes(1);
  });

  it('calls onClearZoom when the zoom chip close icon is clicked', async () => {
    const user = userEvent.setup();
    const onClearZoom = vi.fn();
    renderWithProviders(
      <TraceFilterChipsView
        {...baseProps}
        zoomLabel="10:00 – 10:15"
        onClearZoom={onClearZoom}
      />,
    );

    const closeIcons = document.querySelectorAll('[data-testid="CloseIcon"]');
    await user.click(closeIcons[0].parentElement as Element);

    expect(onClearZoom).toHaveBeenCalledTimes(1);
  });
});
