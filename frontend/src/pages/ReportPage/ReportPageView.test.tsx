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
import ReportPageView, { type ReportPageViewProps } from './ReportPageView';
import type { WindowSelection } from '../../api';

// ReportPageView always passes onRepositoryUrlChange, so PageActions renders
// RepositorySelector, which fetches the repository list itself (it is not
// window-scoped and has no page-level query to stub via props). Stub the
// fetcher rather than let it hit the network in jsdom.
vi.mock('../../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api')>();
  return { ...actual, fetchRepositories: vi.fn().mockResolvedValue([]) };
});

const selection: WindowSelection = { kind: 'preset', minutes: 1440 };

const baseProps: ReportPageViewProps = {
  selection,
  onSelectionChange: vi.fn(),
  repositoryUrl: null,
  onRepositoryUrlChange: vi.fn(),
  data: '# Tuning report\n\nEverything looks fine.',
  isLoading: false,
  error: null,
  copied: false,
  onCopy: vi.fn(),
  onReload: vi.fn(),
};

describe('ReportPageView', () => {
  it('renders the raw markdown verbatim in the preformatted block', () => {
    renderWithProviders(<ReportPageView {...baseProps} />);

    expect(screen.getByText(/Everything looks fine\./)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy markdown' })).toBeEnabled();
  });

  it('shows a loading placeholder and disables the copy button while loading', () => {
    renderWithProviders(<ReportPageView {...baseProps} data={undefined} isLoading />);

    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy markdown' })).toBeDisabled();
  });

  it('calls onCopy when the copy button is clicked and reflects the copied state', async () => {
    const user = userEvent.setup();
    const onCopy = vi.fn();
    renderWithProviders(<ReportPageView {...baseProps} onCopy={onCopy} />);

    await user.click(screen.getByRole('button', { name: 'Copy markdown' }));

    expect(onCopy).toHaveBeenCalledTimes(1);
  });

  it('shows "Copied" in place of the button label once copied is true', () => {
    renderWithProviders(<ReportPageView {...baseProps} copied />);

    expect(screen.getByRole('button', { name: 'Copied' })).toBeInTheDocument();
  });

  it('surfaces the PageLayout error slot when the query has failed', () => {
    renderWithProviders(<ReportPageView {...baseProps} error={new Error('report query failed')} />);

    expect(screen.getByText('report query failed')).toBeInTheDocument();
  });
});
