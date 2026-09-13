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
import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import SettingsPageView from './SettingsPageView';
import {
  fetchEffectiveConfiguration,
  fetchIngestHealth,
  fetchOllamaModels,
  fetchOllamaSettings,
  fetchPurgePreview,
  fetchStorageOverview,
  fetchSystemBuild,
  purgeTelemetry,
  saveOllamaSettings,
  testOllamaConnection,
} from './settingsApi';

/** Retention windows offered by the purge dry-run's segmented toggle. */
const DEFAULT_RETENTION_DAYS = 30;

/** How long the Ollama tab's "Settings saved." confirmation stays up before auto-dismissing. */
const OLLAMA_SAVE_CONFIRMATION_DISPLAY_MS = 4000;

/**
 * Settings container.
 *
 * Two deliberate deviations from every other page, both documented in CLAUDE.md:
 * this page's data is not window-scoped, so its query keys carry no window key
 * and it renders a bare refresh button instead of `PageActions`; and it runs
 * five independent queries rather than one, so the cheap configuration and build
 * blocks paint immediately while the ~1.3s ingest aggregation resolves.
 */
type SettingsTab = 'storage-ingest' | 'schema-build' | 'configuration' | 'retention' | 'ollama';

export default function SettingsPage() {
  const [activeTab, setActiveTab] = useState<SettingsTab>('storage-ingest');
  const [retentionDays, setRetentionDays] = useState(DEFAULT_RETENTION_DAYS);
  const [isPurgeDialogOpen, setIsPurgeDialogOpen] = useState(false);
  const [ollamaBaseUrl, setOllamaBaseUrl] = useState('');
  const [ollamaModel, setOllamaModel] = useState('');
  const [ollamaEnabled, setOllamaEnabled] = useState(false);
  // Tracks unsaved edits to the port, model, or Enabled toggle since the form was last
  // seeded/saved, so leaving the Ollama tab can ask before discarding them.
  const [isOllamaFormDirty, setIsOllamaFormDirty] = useState(false);
  const [pendingTab, setPendingTab] = useState<SettingsTab | null>(null);
  const saveConfirmationTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearSaveConfirmationTimeout = useCallback(() => {
    if (saveConfirmationTimeoutRef.current !== null) {
      clearTimeout(saveConfirmationTimeoutRef.current);
      saveConfirmationTimeoutRef.current = null;
    }
  }, []);

  // Cleans up a pending auto-dismiss timer if the page unmounts before it fires.
  useEffect(() => clearSaveConfirmationTimeout, [clearSaveConfirmationTimeout]);

  const storageQuery = useQuery({
    queryKey: ['system-storage'],
    queryFn: fetchStorageOverview,
  });
  const ingestQuery = useQuery({
    queryKey: ['system-ingest'],
    queryFn: fetchIngestHealth,
  });
  const buildQuery = useQuery({
    queryKey: ['system-build'],
    queryFn: fetchSystemBuild,
  });
  const configurationQuery = useQuery({
    queryKey: ['system-configuration'],
    queryFn: fetchEffectiveConfiguration,
  });
  const purgePreviewQuery = useQuery({
    queryKey: ['system-purge-preview', retentionDays],
    queryFn: () => fetchPurgePreview(retentionDays),
  });
  const ollamaSettingsQuery = useQuery({
    queryKey: ['system-ollama-settings'],
    queryFn: fetchOllamaSettings,
  });

  // Auto-fetches the installed-model list for the Model field's Autocomplete,
  // keyed on the debounced base URL so editing the port re-fetches
  // automatically — not gated behind the "Test connection" button, and not a
  // mutation, since it's meant to run on load and on every base-URL edit
  // rather than only on an explicit user action. Debounced (not the raw,
  // per-keystroke `ollamaBaseUrl`) the same way the Logs/Traces search boxes
  // are: without it, typing a base URL fires a real POST /api/system/ollama/
  // models — and the backend forwards it to Ollama's own GET /api/tags — on
  // every keystroke instead of once the operator pauses. A failed fetch is a
  // normal, silent-degrade outcome (the Autocomplete still works as free
  // text), so it deliberately does not feed the page's top-level `error`
  // prop or `handleReload`/`isReloading` the way the other five queries do.
  const debouncedOllamaBaseUrl = useDebouncedValue(ollamaBaseUrl);
  const ollamaModelsQuery = useQuery({
    queryKey: ['system-ollama-models', debouncedOllamaBaseUrl],
    queryFn: () => fetchOllamaModels(debouncedOllamaBaseUrl),
    enabled: debouncedOllamaBaseUrl.trim().length > 0,
  });

  // Seeds the editable fields from the effective settings once they load, but
  // only the first time — an operator mid-edit shouldn't have their unsaved
  // typing clobbered by a background refetch. Same render-time-diff idiom
  // `PurgeDryRunCard` uses to reset its SQL disclosure: no extra render, and
  // no "setState in an effect" lint violation.
  const [hasInitializedOllamaForm, setHasInitializedOllamaForm] = useState(false);
  if (ollamaSettingsQuery.data && !hasInitializedOllamaForm) {
    setOllamaBaseUrl(ollamaSettingsQuery.data.baseUrl);
    setOllamaModel(ollamaSettingsQuery.data.model);
    setOllamaEnabled(ollamaSettingsQuery.data.enabled);
    setHasInitializedOllamaForm(true);
  }

  // The one mutation in the app. On success every figure on the page is stale by
  // definition, so all five queries are refetched rather than just the estimate.
  const purgeMutation = useMutation({
    mutationFn: () => purgeTelemetry(retentionDays),
    onSuccess: () => {
      setIsPurgeDialogOpen(false);
      void storageQuery.refetch();
      void ingestQuery.refetch();
      void purgePreviewQuery.refetch();
    },
  });

  const saveOllamaSettingsMutation = useMutation({
    mutationFn: () => saveOllamaSettings(ollamaBaseUrl, ollamaModel, ollamaEnabled),
    onSuccess: (settings) => {
      // Reflects the server-normalized result (e.g. a cleared field falling
      // back to the default) straight back into the form.
      setOllamaBaseUrl(settings.baseUrl);
      setOllamaModel(settings.model);
      setOllamaEnabled(settings.enabled);
      setIsOllamaFormDirty(false);
      void ollamaSettingsQuery.refetch();
      // The "Settings saved." banner reads the mutation's own `isSuccess`, so
      // auto-dismissing it means resetting the mutation itself after a delay
      // — clearing any earlier pending timeout first in case Save was clicked
      // again before the previous confirmation finished its run.
      clearSaveConfirmationTimeout();
      saveConfirmationTimeoutRef.current = setTimeout(() => {
        saveOllamaSettingsMutation.reset();
      }, OLLAMA_SAVE_CONFIRMATION_DISPLAY_MS);
    },
  });

  const testOllamaConnectionMutation = useMutation({
    mutationFn: () => testOllamaConnection(ollamaBaseUrl, ollamaModel),
  });

  // Shared tail for every Ollama field edit: marks the form dirty and clears
  // out any test/save result that now describes stale, pre-edit values.
  const markOllamaFieldDirty = useCallback(() => {
    setIsOllamaFormDirty(true);
    testOllamaConnectionMutation.reset();
    saveOllamaSettingsMutation.reset();
    clearSaveConfirmationTimeout();
  }, [testOllamaConnectionMutation, saveOllamaSettingsMutation, clearSaveConfirmationTimeout]);

  const handleOllamaBaseUrlChange = useCallback(
    (nextBaseUrl: string) => {
      setOllamaBaseUrl(nextBaseUrl);
      markOllamaFieldDirty();
    },
    [markOllamaFieldDirty],
  );

  const handleOllamaModelChange = useCallback(
    (nextModel: string) => {
      setOllamaModel(nextModel);
      markOllamaFieldDirty();
    },
    [markOllamaFieldDirty],
  );

  const handleOllamaEnabledChange = useCallback(
    (nextEnabled: boolean) => {
      setOllamaEnabled(nextEnabled);
      markOllamaFieldDirty();
    },
    [markOllamaFieldDirty],
  );

  // Guards a tab switch away from the Ollama tab while its form is dirty: rather than switching
  // immediately, the target tab is parked in `pendingTab` and `UnsavedOllamaChangesDialog` opens.
  // Any other tab change (including switching TO Ollama) proceeds immediately.
  const handleTabChange = useCallback(
    (nextTab: SettingsTab) => {
      if (activeTab === 'ollama' && isOllamaFormDirty && nextTab !== 'ollama') {
        setPendingTab(nextTab);
        return;
      }
      setActiveTab(nextTab);
    },
    [activeTab, isOllamaFormDirty],
  );

  const handleCancelOllamaTabSwitch = useCallback(() => setPendingTab(null), []);

  // Discards the unsaved edits by reverting the form to the last-loaded effective settings
  // (mirroring what a refetch would show), then completes the tab switch that was parked above.
  const handleDiscardOllamaTabSwitch = useCallback(() => {
    if (ollamaSettingsQuery.data) {
      setOllamaBaseUrl(ollamaSettingsQuery.data.baseUrl);
      setOllamaModel(ollamaSettingsQuery.data.model);
      setOllamaEnabled(ollamaSettingsQuery.data.enabled);
    }
    setIsOllamaFormDirty(false);
    testOllamaConnectionMutation.reset();
    saveOllamaSettingsMutation.reset();
    clearSaveConfirmationTimeout();
    if (pendingTab) {
      setActiveTab(pendingTab);
    }
    setPendingTab(null);
  }, [
    ollamaSettingsQuery.data,
    pendingTab,
    testOllamaConnectionMutation,
    saveOllamaSettingsMutation,
    clearSaveConfirmationTimeout,
  ]);

  const handleOpenPurgeDialog = useCallback(() => {
    purgeMutation.reset();
    setIsPurgeDialogOpen(true);
  }, [purgeMutation]);

  const handleClosePurgeDialog = useCallback(() => setIsPurgeDialogOpen(false), []);

  const handleConfirmPurge = useCallback(() => purgeMutation.mutate(), [purgeMutation]);

  // Changing the window invalidates any result on screen — it described a
  // different cutoff, and leaving it up would read as if it applied to this one.
  const handleRetentionDaysChange = useCallback(
    (nextRetentionDays: number) => {
      setRetentionDays(nextRetentionDays);
      purgeMutation.reset();
    },
    [purgeMutation],
  );

  const handleReload = useCallback(() => {
    void storageQuery.refetch();
    void ingestQuery.refetch();
    void buildQuery.refetch();
    void configurationQuery.refetch();
    void purgePreviewQuery.refetch();
    void ollamaSettingsQuery.refetch();
  }, [
    storageQuery,
    ingestQuery,
    buildQuery,
    configurationQuery,
    purgePreviewQuery,
    ollamaSettingsQuery,
  ]);

  const isReloading =
    storageQuery.isFetching ||
    ingestQuery.isFetching ||
    buildQuery.isFetching ||
    configurationQuery.isFetching ||
    purgePreviewQuery.isFetching ||
    ollamaSettingsQuery.isFetching;

  return (
    <SettingsPageView
      storage={storageQuery.data ?? null}
      ingestHealth={ingestQuery.data ?? null}
      systemBuild={buildQuery.data ?? null}
      configuration={configurationQuery.data ?? null}
      purgePreview={purgePreviewQuery.data ?? null}
      isStorageLoading={storageQuery.isLoading}
      isIngestLoading={ingestQuery.isLoading}
      isBuildLoading={buildQuery.isLoading}
      isConfigurationLoading={configurationQuery.isLoading}
      isPurgePreviewLoading={purgePreviewQuery.isLoading}
      retentionDays={retentionDays}
      onRetentionDaysChange={handleRetentionDaysChange}
      isPurgeDialogOpen={isPurgeDialogOpen}
      onOpenPurgeDialog={handleOpenPurgeDialog}
      onClosePurgeDialog={handleClosePurgeDialog}
      onConfirmPurge={handleConfirmPurge}
      isPurging={purgeMutation.isPending}
      purgeError={purgeMutation.error as Error | null}
      purgeResult={purgeMutation.data ?? null}
      isReloading={isReloading}
      onReload={handleReload}
      error={
        (storageQuery.error ??
          ingestQuery.error ??
          buildQuery.error ??
          configurationQuery.error ??
          purgePreviewQuery.error ??
          ollamaSettingsQuery.error) as Error | null
      }
      activeTab={activeTab}
      onTabChange={handleTabChange}
      ollamaSettings={ollamaSettingsQuery.data ?? null}
      isOllamaSettingsLoading={ollamaSettingsQuery.isLoading}
      ollamaBaseUrl={ollamaBaseUrl}
      ollamaModel={ollamaModel}
      ollamaEnabled={ollamaEnabled}
      onOllamaBaseUrlChange={handleOllamaBaseUrlChange}
      onOllamaModelChange={handleOllamaModelChange}
      onOllamaEnabledChange={handleOllamaEnabledChange}
      onSaveOllamaSettings={() => saveOllamaSettingsMutation.mutate()}
      isSavingOllamaSettings={saveOllamaSettingsMutation.isPending}
      saveOllamaSettingsError={saveOllamaSettingsMutation.error as Error | null}
      isOllamaSettingsSaved={saveOllamaSettingsMutation.isSuccess}
      isTestingOllamaConnection={testOllamaConnectionMutation.isPending}
      ollamaConnectionTestResult={testOllamaConnectionMutation.data ?? null}
      ollamaConnectionTestError={testOllamaConnectionMutation.error as Error | null}
      onTestOllamaConnection={() => testOllamaConnectionMutation.mutate()}
      ollamaModels={ollamaModelsQuery.data?.models ?? []}
      isOllamaModelsLoading={ollamaModelsQuery.isFetching}
      isUnsavedOllamaChangesDialogOpen={pendingTab !== null}
      onCancelOllamaTabSwitch={handleCancelOllamaTabSwitch}
      onDiscardOllamaTabSwitch={handleDiscardOllamaTabSwitch}
    />
  );
}
