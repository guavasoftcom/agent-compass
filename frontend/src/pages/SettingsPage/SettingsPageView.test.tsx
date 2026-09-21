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
import type { OllamaModel, OllamaSettings, StorageOverview } from './settingsTypes';

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

const ollamaSettings: OllamaSettings = {
  baseUrl: 'http://localhost:11434',
  model: 'llama3.1',
  enabled: true,
  overridden: false,
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
  ollamaSettings,
  isOllamaSettingsLoading: false,
  ollamaBaseUrl: 'http://localhost:11434',
  ollamaModel: 'llama3.1',
  ollamaEnabled: true,
  onOllamaBaseUrlChange: vi.fn(),
  onOllamaModelChange: vi.fn(),
  onOllamaEnabledChange: vi.fn(),
  onSaveOllamaSettings: vi.fn(),
  isSavingOllamaSettings: false,
  saveOllamaSettingsError: null,
  isOllamaSettingsSaved: false,
  isTestingOllamaConnection: false,
  ollamaConnectionTestResult: null,
  ollamaConnectionTestError: null,
  onTestOllamaConnection: vi.fn(),
  ollamaModels: [],
  isOllamaModelsLoading: false,
  isUnsavedOllamaChangesDialogOpen: false,
  onCancelOllamaTabSwitch: vi.fn(),
  onDiscardOllamaTabSwitch: vi.fn(),
  updateCheck: {
    enabled: true,
    currentVersion: '2.7.1',
    latestVersion: '2.8.0',
    updateAvailable: true,
    releaseUrl: 'https://github.com/guavasoftcom/agent-compass/releases/tag/v2.8.0',
    publishedAt: '2026-09-20T18:31:04.000Z',
    checkedAt: '2026-09-21T09:00:00.000Z',
    message: null,
  },
  isUpdateCheckLoading: false,
  isCheckingForUpdate: false,
  isSavingUpdateCheckEnabled: false,
  onUpdateCheckEnabledChange: vi.fn(),
  onCheckForUpdate: vi.fn(),
  updateCheckError: null,
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

  it('shows the update check above the migration history on the Schema & Build tab', () => {
    renderWithProviders(<SettingsPageView {...baseProps} activeTab="schema-build" />);

    expect(screen.getByRole('switch', { name: 'Check for updates' })).toBeChecked();
    expect(screen.getByText(/v2\.8\.0 is available/)).toBeInTheDocument();
    expect(screen.getByText('Application')).toBeInTheDocument();
  });

  it('does not render the update check on the other tabs', () => {
    renderWithProviders(<SettingsPageView {...baseProps} activeTab="storage-ingest" />);

    expect(screen.queryByRole('switch', { name: 'Check for updates' })).not.toBeInTheDocument();
  });

  it('surfaces the PageLayout error slot when a query has failed', () => {
    renderWithProviders(
      <SettingsPageView {...baseProps} error={new Error('boom')} />,
    );

    expect(screen.getByText('boom')).toBeInTheDocument();
  });
});

describe('SettingsPageView — Ollama configuration tab', () => {
  const withOllamaTab = (overrides: Partial<SettingsPageViewProps> = {}) => ({
    ...baseProps,
    activeTab: 'ollama' as const,
    ...overrides,
  });

  it('shows the loaded port and model fields', () => {
    renderWithProviders(<SettingsPageView {...withOllamaTab()} />);

    expect(screen.getByLabelText('Port')).toHaveValue('11434');
    expect(screen.getByLabelText('Model')).toHaveValue('llama3.1');
    expect(screen.getByText('Using default')).toBeInTheDocument();
  });

  it('flags an overridden configuration with a chip', () => {
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          ollamaSettings: { ...ollamaSettings, overridden: true },
        })}
      />,
    );

    expect(screen.getByText('Overridden')).toBeInTheDocument();
  });

  it('shows the Enabled toggle checked and the fields interactive when enabled', () => {
    renderWithProviders(<SettingsPageView {...withOllamaTab({ ollamaEnabled: true })} />);

    expect(screen.getByRole('switch', { name: 'Ollama enabled' })).toBeChecked();
    expect(screen.getByLabelText('Port')).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Test connection' })).toBeEnabled();
  });

  it('dims and disables the form and Test connection when Ollama is disabled, but keeps Save enabled', () => {
    renderWithProviders(<SettingsPageView {...withOllamaTab({ ollamaEnabled: false })} />);

    expect(screen.getByRole('switch', { name: 'Ollama enabled' })).not.toBeChecked();
    expect(screen.getByLabelText('Port')).toBeDisabled();
    // Save must stay enabled while disabled: it's the only way to persist turning
    // the toggle off in the first place.
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Test connection' })).toBeDisabled();
  });

  it('calls onOllamaEnabledChange when the toggle is clicked', async () => {
    const user = userEvent.setup();
    const onOllamaEnabledChange = vi.fn();
    renderWithProviders(
      <SettingsPageView {...withOllamaTab({ onOllamaEnabledChange })} />,
    );

    await user.click(screen.getByRole('switch', { name: 'Ollama enabled' }));

    expect(onOllamaEnabledChange).toHaveBeenCalledWith(false);
  });

  it('calls onOllamaBaseUrlChange and onOllamaModelChange as the fields are edited', async () => {
    const user = userEvent.setup();
    const onOllamaBaseUrlChange = vi.fn();
    renderWithProviders(
      <SettingsPageView {...withOllamaTab({ onOllamaBaseUrlChange })} />,
    );

    await user.type(screen.getByLabelText('Port'), '1');
    expect(onOllamaBaseUrlChange).toHaveBeenCalled();
  });

  it('disables Save and shows "Saving…" while a save is in flight', () => {
    renderWithProviders(
      <SettingsPageView {...withOllamaTab({ isSavingOllamaSettings: true })} />,
    );

    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled();
  });

  it('calls onSaveOllamaSettings when Save is clicked', async () => {
    const user = userEvent.setup();
    const onSaveOllamaSettings = vi.fn();
    renderWithProviders(
      <SettingsPageView {...withOllamaTab({ onSaveOllamaSettings })} />,
    );

    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(onSaveOllamaSettings).toHaveBeenCalledTimes(1);
  });

  it('shows the save error message when saving fails', () => {
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({ saveOllamaSettingsError: new Error('Invalid base URL') })}
      />,
    );

    expect(screen.getByText('Invalid base URL')).toBeInTheDocument();
  });

  it('shows a confirmation after a successful save', () => {
    renderWithProviders(
      <SettingsPageView {...withOllamaTab({ isOllamaSettingsSaved: true })} />,
    );

    expect(screen.getByText('Settings saved.')).toBeInTheDocument();
  });

  it('does not show a save confirmation alongside a save error', () => {
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          isOllamaSettingsSaved: true,
          saveOllamaSettingsError: new Error('Invalid base URL'),
        })}
      />,
    );

    expect(screen.queryByText('Settings saved.')).not.toBeInTheDocument();
  });

  it('disables Test connection and shows "Testing…" while a probe is in flight', () => {
    renderWithProviders(
      <SettingsPageView {...withOllamaTab({ isTestingOllamaConnection: true })} />,
    );

    expect(screen.getByRole('button', { name: 'Testing…' })).toBeDisabled();
  });

  it('calls onTestOllamaConnection when Test connection is clicked', async () => {
    const user = userEvent.setup();
    const onTestOllamaConnection = vi.fn();
    renderWithProviders(
      <SettingsPageView {...withOllamaTab({ onTestOllamaConnection })} />,
    );

    await user.click(screen.getByRole('button', { name: 'Test connection' }));
    expect(onTestOllamaConnection).toHaveBeenCalledTimes(1);
  });

  it('shows a success message when the connection test succeeds', () => {
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          ollamaConnectionTestResult: { success: true, message: 'Reachable' },
        })}
      />,
    );

    expect(screen.getByText('Reachable')).toBeInTheDocument();
  });

  it('shows a failure message when the connection test fails', () => {
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          ollamaConnectionTestResult: {
            success: false,
            message: 'Could not reach Ollama at http://localhost:11434 — is it running?',
          },
        })}
      />,
    );

    expect(
      screen.getByText('Could not reach Ollama at http://localhost:11434 — is it running?'),
    ).toBeInTheDocument();
  });

  it('shows the network-level test error separately from a failed-probe result', () => {
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          ollamaConnectionTestError: new Error('/api/system/ollama/test-connection → 500'),
        })}
      />,
    );

    expect(
      screen.getByText('/api/system/ollama/test-connection → 500'),
    ).toBeInTheDocument();
  });

  it('shows a not-tested placeholder before any probe has run', () => {
    renderWithProviders(<SettingsPageView {...withOllamaTab()} />);

    expect(screen.getByText('Not tested since load.')).toBeInTheDocument();
  });

  it('shows a loading skeleton before the effective settings have loaded', () => {
    const { container } = renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({ ollamaSettings: null, isOllamaSettingsLoading: true })}
      />,
    );

    expect(container.querySelectorAll('.MuiSkeleton-root').length).toBeGreaterThan(0);
  });

  const smallModel: OllamaModel = {
    name: 'llama3.1:latest',
    parameterSize: '8.0B',
    parameterCountBillions: 8.0,
  };
  const largeModel: OllamaModel = {
    name: 'qwen2.5:32b',
    parameterSize: '32.0B',
    parameterCountBillions: 32.0,
  };
  const unsizedModel: OllamaModel = {
    name: 'custom:latest',
    parameterSize: null,
    parameterCountBillions: null,
  };

  it('renders the Model field as an Autocomplete populated with the fetched options, annotated with size', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          ollamaModel: '',
          ollamaModels: [smallModel, largeModel, unsizedModel],
        })}
      />,
    );

    await user.click(screen.getByLabelText('Model'));
    expect(await screen.findByText('llama3.1:latest')).toBeInTheDocument();
    expect(screen.getByText('8.0B')).toBeInTheDocument();
    expect(screen.getByText('qwen2.5:32b')).toBeInTheDocument();
    expect(screen.getByText('32.0B')).toBeInTheDocument();
    expect(screen.getByText('custom:latest')).toBeInTheDocument();
  });

  it('calls onOllamaModelChange when typing a model name that is not in the fetched options', async () => {
    const user = userEvent.setup();
    const onOllamaModelChange = vi.fn();
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          ollamaModel: '',
          ollamaModels: [smallModel],
          onOllamaModelChange,
        })}
      />,
    );

    await user.type(screen.getByLabelText('Model'), 'mistral');

    expect(onOllamaModelChange).toHaveBeenCalled();
  });

  it('shows a warning when the current model matches a fetched entry at or above 13B parameters', () => {
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          ollamaModel: 'qwen2.5:32b',
          ollamaModels: [smallModel, largeModel],
        })}
      />,
    );

    expect(screen.getByText(/This is a 32\.0B model/)).toBeInTheDocument();
  });

  it('shows no warning when the current model matches a fetched entry below 13B parameters', () => {
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          ollamaModel: 'llama3.1:latest',
          ollamaModels: [smallModel, largeModel],
        })}
      />,
    );

    expect(screen.queryByText(/local inference may be slow/)).not.toBeInTheDocument();
  });

  it('shows no warning when the current model is not in the fetched list', () => {
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          ollamaModel: 'not-yet-pulled:latest',
          ollamaModels: [smallModel, largeModel],
        })}
      />,
    );

    expect(screen.queryByText(/local inference may be slow/)).not.toBeInTheDocument();
  });

  it('does not show the unsaved-changes dialog by default', () => {
    renderWithProviders(<SettingsPageView {...withOllamaTab()} />);

    expect(screen.queryByText('Leave without saving?')).not.toBeInTheDocument();
  });

  it('shows the unsaved-changes dialog when open, and wires Cancel/Leave to their callbacks', async () => {
    const user = userEvent.setup();
    const onCancelOllamaTabSwitch = vi.fn();
    const onDiscardOllamaTabSwitch = vi.fn();
    renderWithProviders(
      <SettingsPageView
        {...withOllamaTab({
          isUnsavedOllamaChangesDialogOpen: true,
          onCancelOllamaTabSwitch,
          onDiscardOllamaTabSwitch,
        })}
      />,
    );

    expect(screen.getByText('Leave without saving?')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancelOllamaTabSwitch).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Leave without saving' }));
    expect(onDiscardOllamaTabSwitch).toHaveBeenCalledTimes(1);
  });
});
