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
import { renderWithProviders } from '../test/renderWithProviders';
import SidebarVersion from './SidebarVersion';
import { fetchSystemBuild } from '../pages/SettingsPage/settingsApi';

vi.mock('../pages/SettingsPage/settingsApi', () => ({
  fetchSystemBuild: vi.fn(),
}));

const buildWithVersion = (applicationVersion: string) => ({
  applicationVersion,
  buildTime: null,
  javaVersion: '25',
  javaVendor: 'Eclipse Adoptium',
  jvmName: 'OpenJDK 64-Bit Server VM',
  postgresVersion: '18.0',
  migrations: [],
});

describe('SidebarVersion', () => {
  it.each([
    ['drops the SNAPSHOT suffix the release workflow leaves on packaged jars', '2.7.2-SNAPSHOT', 'v2.7.2'],
    ['keeps a version with no suffix as-is', '2.7.2', 'v2.7.2'],
    ['leaves the IDE-run placeholder unprefixed', 'dev', 'dev'],
  ])('%s', async (_description, applicationVersion, expectedLabel) => {
    vi.mocked(fetchSystemBuild).mockResolvedValue(buildWithVersion(applicationVersion));

    renderWithProviders(<SidebarVersion />);

    expect(await screen.findByText(expectedLabel)).toBeInTheDocument();
  });

  it('renders nothing when the build info request fails', async () => {
    vi.mocked(fetchSystemBuild).mockRejectedValue(new Error('boom'));

    const { container } = renderWithProviders(<SidebarVersion />);

    await vi.waitFor(() => expect(fetchSystemBuild).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
