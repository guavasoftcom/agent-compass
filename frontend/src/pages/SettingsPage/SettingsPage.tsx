import { useCallback, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import SettingsPageView from './SettingsPageView';
import {
  fetchEffectiveConfiguration,
  fetchIngestHealth,
  fetchPurgePreview,
  fetchStorageOverview,
  fetchSystemBuild,
  purgeTelemetry,
} from './settingsApi';

/** Retention windows offered by the purge dry-run's segmented toggle. */
const DEFAULT_RETENTION_DAYS = 30;

/**
 * Settings container.
 *
 * Two deliberate deviations from every other page, both documented in CLAUDE.md:
 * this page's data is not window-scoped, so its query keys carry no window key
 * and it renders a bare refresh button instead of `PageActions`; and it runs
 * five independent queries rather than one, so the cheap configuration and build
 * blocks paint immediately while the ~1.3s ingest aggregation resolves.
 */
export default function SettingsPage() {
  const [activeTab, setActiveTab] = useState<'storage-ingest' | 'schema-build' | 'configuration' | 'retention'>('storage-ingest');
  const [retentionDays, setRetentionDays] = useState(DEFAULT_RETENTION_DAYS);
  const [isPurgeDialogOpen, setIsPurgeDialogOpen] = useState(false);

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
  }, [storageQuery, ingestQuery, buildQuery, configurationQuery, purgePreviewQuery]);

  const isReloading =
    storageQuery.isFetching ||
    ingestQuery.isFetching ||
    buildQuery.isFetching ||
    configurationQuery.isFetching ||
    purgePreviewQuery.isFetching;

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
          purgePreviewQuery.error) as Error | null
      }
      activeTab={activeTab}
      onTabChange={setActiveTab}
    />
  );
}
