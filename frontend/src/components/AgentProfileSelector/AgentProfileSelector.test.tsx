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
import { renderWithProviders } from '../../test/renderWithProviders';
import AgentProfileSelector from './AgentProfileSelector';
import type { ProfilesResponse } from '../../api';

const profilesResponse: ProfilesResponse = {
  primaryAgentProfile: 'claude-code',
  profiles: [
    { agentProfile: 'claude-code', lastSeen: '2026-09-14T00:00:00Z', count: 900 },
    { agentProfile: 'unknown', lastSeen: '2026-09-13T00:00:00Z', count: 12 },
  ],
};

const fetchProfiles = vi.fn();

vi.mock('../../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api')>();
  return { ...actual, fetchProfiles: (...args: unknown[]) => fetchProfiles(...args) };
});

describe('AgentProfileSelector', () => {
  it('shows the primary profile label with the default marker when value is null', async () => {
    fetchProfiles.mockResolvedValue(profilesResponse);
    renderWithProviders(<AgentProfileSelector value={null} onValueChange={vi.fn()} />);

    expect(
      await screen.findByRole('button', { name: /Claude Code \(default\)/ }),
    ).toBeInTheDocument();
  });

  it('shows a loading label while the profiles query is pending', () => {
    fetchProfiles.mockReturnValue(new Promise(() => {}));
    renderWithProviders(<AgentProfileSelector value={null} onValueChange={vi.fn()} />);

    expect(screen.getByRole('button', { name: /Loading agents/ })).toBeInTheDocument();
  });

  it('lists every profile, labelled, and offers no "all" option', async () => {
    fetchProfiles.mockResolvedValue(profilesResponse);
    const user = userEvent.setup();
    renderWithProviders(<AgentProfileSelector value={null} onValueChange={vi.fn()} />);

    await user.click(await screen.findByRole('button', { name: /Claude Code/ }));

    expect(
      await screen.findByRole('menuitem', { name: 'Claude Code (default)' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('menuitem', { name: 'Unknown (unrecognized agent)' }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('menuitem')).toHaveLength(2);
    expect(screen.queryByRole('menuitem', { name: /all/i })).not.toBeInTheDocument();
  });

  it('calls onValueChange with the unknown profile when it is picked', async () => {
    fetchProfiles.mockResolvedValue(profilesResponse);
    const onValueChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<AgentProfileSelector value={null} onValueChange={onValueChange} />);

    await user.click(await screen.findByRole('button', { name: /Claude Code/ }));
    await user.click(await screen.findByRole('menuitem', { name: 'Unknown (unrecognized agent)' }));

    expect(onValueChange).toHaveBeenCalledWith('unknown');
  });

  it('shows the selected profile label as the button label', async () => {
    fetchProfiles.mockResolvedValue(profilesResponse);
    renderWithProviders(<AgentProfileSelector value="unknown" onValueChange={vi.fn()} />);

    expect(
      await screen.findByRole('button', { name: 'Unknown (unrecognized agent)' }),
    ).toBeInTheDocument();
  });
});
