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
import UpdateCheckCard, { type UpdateCheckCardProps } from './UpdateCheckCard';
import type { UpdateCheckStatus } from '../../settingsTypes';

const RELEASE_URL = 'https://github.com/guavasoftcom/agent-compass/releases/tag/v2.8.0';

const upToDate: UpdateCheckStatus = {
  enabled: true,
  currentVersion: '2.8.0',
  latestVersion: '2.8.0',
  updateAvailable: false,
  releaseUrl: RELEASE_URL,
  publishedAt: '2026-09-20T18:31:04Z',
  checkedAt: new Date().toISOString(),
  message: null,
};

const updateAvailable: UpdateCheckStatus = {
  ...upToDate,
  currentVersion: '2.7.1',
  updateAvailable: true,
};

const buildProps = (overrides: Partial<UpdateCheckCardProps> = {}): UpdateCheckCardProps => ({
  updateCheck: upToDate,
  isUpdateCheckLoading: false,
  isCheckingForUpdate: false,
  isSavingEnabled: false,
  onEnabledChange: vi.fn(),
  onCheckNow: vi.fn(),
  error: null,
  ...overrides,
});

describe('UpdateCheckCard', () => {
  it('announces a newer release, names both versions and links to its notes', () => {
    renderWithProviders(<UpdateCheckCard {...buildProps({ updateCheck: updateAvailable })} />);

    expect(screen.getByText(/v2\.8\.0 is available/)).toBeInTheDocument();
    expect(screen.getByText(/you are running v2\.7\.1/)).toBeInTheDocument();
    const releaseNotesLink = screen.getByRole('link', { name: 'Release notes' });
    expect(releaseNotesLink).toHaveAttribute('href', RELEASE_URL);
    expect(releaseNotesLink).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('says so when the running version is already the latest', () => {
    renderWithProviders(<UpdateCheckCard {...buildProps()} />);

    expect(screen.getByText(/You are running the latest release \(v2\.8\.0\)/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Release notes' })).not.toBeInTheDocument();
  });

  it('renders no link for a release URL that is not https', () => {
    renderWithProviders(
      <UpdateCheckCard
        {...buildProps({
          updateCheck: { ...updateAvailable, releaseUrl: 'javascript:alert(1)' },
        })}
      />,
    );

    expect(screen.getByText(/v2\.8\.0 is available/)).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('shows the reason instead of a verdict when the check could not complete', () => {
    renderWithProviders(
      <UpdateCheckCard
        {...buildProps({
          updateCheck: {
            ...upToDate,
            latestVersion: null,
            releaseUrl: null,
            publishedAt: null,
            message: 'Could not reach GitHub. Is this machine offline?',
          },
        })}
      />,
    );

    expect(screen.getByText('Could not reach GitHub. Is this machine offline?')).toBeInTheDocument();
    expect(screen.queryByText(/You are running the latest release/)).not.toBeInTheDocument();
    expect(screen.queryByText(/is available/)).not.toBeInTheDocument();
  });

  it('leaves an unpackaged build unprefixed rather than calling it v-dev', () => {
    renderWithProviders(
      <UpdateCheckCard
        {...buildProps({
          updateCheck: {
            ...upToDate,
            currentVersion: 'dev',
            latestVersion: null,
            message: 'This is a development build, so there is no release to compare against.',
          },
        })}
      />,
    );

    expect(screen.getByText(/development build/)).toBeInTheDocument();
    expect(screen.queryByText('vdev')).not.toBeInTheDocument();
  });

  it('says nothing is sent, and disables Check now, when the check is off', () => {
    renderWithProviders(
      <UpdateCheckCard
        {...buildProps({
          updateCheck: {
            enabled: false,
            currentVersion: '2.8.0',
            latestVersion: null,
            updateAvailable: false,
            releaseUrl: null,
            publishedAt: null,
            checkedAt: null,
            message: null,
          },
        })}
      />,
    );

    expect(screen.getByRole('switch', { name: 'Check for updates' })).not.toBeChecked();
    expect(screen.getByText('Update checks are off. Nothing is sent to GitHub.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Check now' })).toBeDisabled();
  });

  it('reports the new switch position when it is flipped', async () => {
    const user = userEvent.setup();
    const onEnabledChange = vi.fn();
    renderWithProviders(<UpdateCheckCard {...buildProps({ onEnabledChange })} />);

    await user.click(screen.getByRole('switch', { name: 'Check for updates' }));

    expect(onEnabledChange).toHaveBeenCalledWith(false);
  });

  it('holds the switch still while a change is being saved', () => {
    renderWithProviders(<UpdateCheckCard {...buildProps({ isSavingEnabled: true })} />);

    expect(screen.getByRole('switch', { name: 'Check for updates' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Check now' })).toBeDisabled();
  });

  it('asks for a fresh check when Check now is pressed', async () => {
    const user = userEvent.setup();
    const onCheckNow = vi.fn();
    renderWithProviders(<UpdateCheckCard {...buildProps({ onCheckNow })} />);

    await user.click(screen.getByRole('button', { name: 'Check now' }));

    expect(onCheckNow).toHaveBeenCalledTimes(1);
  });

  it('shows progress and blocks a second press while a check is running', () => {
    renderWithProviders(<UpdateCheckCard {...buildProps({ isCheckingForUpdate: true })} />);

    expect(screen.getByRole('button', { name: 'Checking…' })).toBeDisabled();
  });

  it('shows when the answer was last fetched', () => {
    renderWithProviders(<UpdateCheckCard {...buildProps()} />);

    expect(screen.getByText(/Last checked just now/)).toBeInTheDocument();
  });

  it('surfaces a failed save or check', () => {
    renderWithProviders(<UpdateCheckCard {...buildProps({ error: new Error('boom') })} />);

    expect(screen.getByText('boom')).toBeInTheDocument();
  });

  it('renders a placeholder, not a verdict, while the first answer is loading', () => {
    renderWithProviders(
      <UpdateCheckCard {...buildProps({ updateCheck: null, isUpdateCheckLoading: true })} />,
    );

    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
    expect(screen.queryByText(/You are running the latest release/)).not.toBeInTheDocument();
  });
});
