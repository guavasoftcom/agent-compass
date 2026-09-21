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
import RepositorySelector from './RepositorySelector';
import type { RepositorySummary } from '../../api';

const repositories: RepositorySummary[] = [
  {
    repositoryUrl: 'https://github.com/guavasoftcom/agent-compass',
    lastSeen: '2026-09-14T00:00:00Z',
    count: 10_796,
  },
  {
    repositoryUrl: 'https://github.com/guavasoftcom/spring-batch-dashboard',
    lastSeen: '2026-09-13T00:00:00Z',
    count: 52,
  },
];

const fetchRepositories = vi.fn();

vi.mock('../../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api')>();
  return { ...actual, fetchRepositories: (...args: unknown[]) => fetchRepositories(...args) };
});

describe('RepositorySelector', () => {
  it('defaults the button label to "All repositories" when value is null', async () => {
    fetchRepositories.mockResolvedValue(repositories);
    renderWithProviders(<RepositorySelector value={null} onValueChange={vi.fn()} />);

    expect(await screen.findByRole('button', { name: /All repositories/i })).toBeInTheDocument();
  });

  it('renders every fetched repository as a menu option, short-labeled', async () => {
    fetchRepositories.mockResolvedValue(repositories);
    const user = userEvent.setup();
    renderWithProviders(<RepositorySelector value={null} onValueChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /All repositories/i }));

    expect(await screen.findByRole('menuitem', { name: 'Unattributed' })).toBeInTheDocument();
    expect(
      screen.getByRole('menuitem', { name: 'guavasoftcom/agent-compass' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('menuitem', { name: 'guavasoftcom/spring-batch-dashboard' }),
    ).toBeInTheDocument();
  });

  it('calls onValueChange with the picked repository URL and closes the menu', async () => {
    fetchRepositories.mockResolvedValue(repositories);
    const onValueChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<RepositorySelector value={null} onValueChange={onValueChange} />);

    await user.click(screen.getByRole('button', { name: /All repositories/i }));
    await user.click(await screen.findByRole('menuitem', { name: 'guavasoftcom/agent-compass' }));

    expect(onValueChange).toHaveBeenCalledWith(
      'https://github.com/guavasoftcom/agent-compass',
    );
  });

  it('calls onValueChange with the reserved sentinel when Unattributed is picked', async () => {
    fetchRepositories.mockResolvedValue(repositories);
    const onValueChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<RepositorySelector value={null} onValueChange={onValueChange} />);

    await user.click(screen.getByRole('button', { name: /All repositories/i }));
    await user.click(await screen.findByRole('menuitem', { name: 'Unattributed' }));

    expect(onValueChange).toHaveBeenCalledWith('__unattributed__');
  });

  it('shows the short label of the selected repository as the button label', async () => {
    fetchRepositories.mockResolvedValue(repositories);
    renderWithProviders(
      <RepositorySelector
        value="https://github.com/guavasoftcom/agent-compass"
        onValueChange={vi.fn()}
      />,
    );

    expect(
      await screen.findByRole('button', { name: 'guavasoftcom/agent-compass' }),
    ).toBeInTheDocument();
  });
});
