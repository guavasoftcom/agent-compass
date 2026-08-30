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
import SettingsPageView, {
  type SettingsPageViewProps,
} from './SettingsPageView';
import type { StorageOverview } from './settingsTypes';

const storage: StorageOverview = {
  tables: [
    {
      tableName: 'log_records',
      rowCount: 1_200_000,
      heapBytes: 400_000_000,
      indexBytes: 100_000_000,
      toastBytes: 900_000_000,
      totalBytes: 1_400_000_000,
      oldestTimestamp: '2026-07-01T00:00:00.000Z',
      newestTimestamp: '2026-08-30T00:00:00.000Z',
      rowsLastSevenDays: 50_000,
      estimatedBytesPerDay: 10_000_000,
    },
  ],
  databaseTotalBytes: 1_400_000_000,
  estimatedTotalBytesPerDay: 10_000_000,
  measuredAt: '2026-08-30T00:00:00.000Z',
};

const baseProps: SettingsPageViewProps = {
  storage,
  ingestHealth: {
    signals: [
      {
        signal: 'logs',
        tableName: 'log_records',
        newestTimestamp: '2026-08-30T00:00:00.000Z',
        newestReceivedAt: '2026-08-30T00:00:00.000Z',
        rowsLastHour: 100,
        rowsLastDay: 2000,
        rowsLastWeek: 50_000,
        nameCardinality: 12,
        nameCardinalityLabel: '12 event names',
        seriesCardinality: null,
      },
    ],
    measuredAt: '2026-08-30T00:00:00.000Z',
  },
  systemBuild: {
    applicationVersion: '2.0.0',
    buildTime: '2026-08-30T00:00:00.000Z',
    javaVersion: '21',
    javaVendor: 'Eclipse Adoptium',
    jvmName: 'OpenJDK 64-Bit Server VM',
    postgresVersion: '16.2',
    migrations: [],
  },
  configuration: {
    groups: [],
    propertyCount: 52,
    overriddenCount: 2,
  },
  purgePreview: {
    retentionDays: 30,
    cutoff: '2026-07-31T00:00:00.000Z',
    tables: [],
    totalRowsToDelete: 0,
    estimatedReclaimableBytes: 0,
    sql: 'SELECT 1',
  },
  isStorageLoading: false,
  isIngestLoading: false,
  isBuildLoading: false,
  isConfigurationLoading: false,
  isPurgePreviewLoading: false,
  retentionDays: 30,
  onRetentionDaysChange: vi.fn(),
  isPurgeDialogOpen: false,
  onOpenPurgeDialog: vi.fn(),
  onClosePurgeDialog: vi.fn(),
  onConfirmPurge: vi.fn(),
  isPurging: false,
  purgeError: null,
  purgeResult: null,
  isReloading: false,
  onReload: vi.fn(),
  error: null,
  activeTab: 'storage-ingest',
  onTabChange: vi.fn(),
};

describe('SettingsPageView', () => {
  it('renders the KPI strip and the storage breakdown from props', () => {
    renderWithProviders(<SettingsPageView {...baseProps} />);

    expect(screen.getByText('Database size')).toBeInTheDocument();
    expect(screen.getAllByText('log_records').length).toBeGreaterThan(0);
  });

  it('shows the empty placeholders when no data has loaded yet', () => {
    renderWithProviders(
      <SettingsPageView
        {...baseProps}
        storage={null}
        ingestHealth={null}
        systemBuild={null}
        configuration={null}
        purgePreview={null}
      />,
    );

    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });

  it('switches to the Schema & Build tab when clicked', async () => {
    const user = userEvent.setup();
    const onTabChange = vi.fn();
    renderWithProviders(
      <SettingsPageView {...baseProps} onTabChange={onTabChange} />,
    );

    await user.click(screen.getByRole('tab', { name: 'Schema & Build' }));

    expect(onTabChange).toHaveBeenCalledWith('schema-build');
  });

  it('surfaces the PageLayout error slot when a query has failed', () => {
    renderWithProviders(
      <SettingsPageView {...baseProps} error={new Error('boom')} />,
    );

    expect(screen.getByText('boom')).toBeInTheDocument();
  });
});
