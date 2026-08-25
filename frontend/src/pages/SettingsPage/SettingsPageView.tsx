import { useMemo } from 'react';
import type { ReactNode } from 'react';
import { alpha, Box, Button, Tooltip, useTheme } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import RefreshIcon from '@mui/icons-material/Refresh';
import PageLayout from '../../components/PageLayout';
import StatCard from '../../components/StatCard';
import DonutCard from '../../components/DonutCard';
import PillTabs from '../../components/PillTabs';
import { groupForPath } from '../../App/navGroups';
import { colorForIndex } from '../../theme/theme';
import { auroraColors } from '../../theme/colors';
import {
  formatBytes,
  formatCompact,
  formatRelativeTime,
} from '../../lib/format';
import StorageBreakdownCard from './components/StorageBreakdownCard';
import IngestHealthCard from './components/IngestHealthCard';
import SchemaBuildCard from './components/SchemaBuildCard';
import EffectiveConfigurationCard from './components/EffectiveConfigurationCard';
import PurgeDryRunCard from './components/PurgeDryRunCard';
import { daysUntilSize, retentionSpanDays } from './settingsDerivations';
import type {
  EffectiveConfiguration,
  IngestHealth,
  PurgePreview,
  PurgeResult,
  StorageOverview,
  SystemBuild,
} from './settingsTypes';

type SettingsTab =
  | 'storage-ingest'
  | 'schema-build'
  | 'configuration'
  | 'retention';

/** Projection ceiling for the growth KPI's caption. */
const PROJECTION_CEILING_BYTES = 10 * 1024 ** 3;
const PROJECTION_CEILING_LABEL = '10 GB';

const KPI_ICON_SIZE = 16;
const KPI_ICON_SVG_PROPS = {
  width: KPI_ICON_SIZE,
  height: KPI_ICON_SIZE,
  viewBox: '0 0 24 24',
  fill: 'none' as const,
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
};

const DatabaseKpiIcon = () => (
  <svg {...KPI_ICON_SVG_PROPS}>
    <ellipse cx="12" cy="6" rx="8" ry="3" />
    <path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
  </svg>
);

const GrowthKpiIcon = () => (
  <svg {...KPI_ICON_SVG_PROPS}>
    <path d="M4 15l5-6 4 3 7-9" />
    <path d="M15 3h5v5" />
  </svg>
);

const RowsKpiIcon = () => (
  <svg {...KPI_ICON_SVG_PROPS}>
    <path d="M4 6h16M4 12h16M4 18h10" />
  </svg>
);

const ClockKpiIcon = () => (
  <svg {...KPI_ICON_SVG_PROPS}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 3" />
  </svg>
);

/**
 * A KPI card's icon badge: a tinted rounded-square carrying the icon's own
 * color at 14% opacity, top-right of the card. One per `StatCard` — signals
 * the card's meaning (DB / growth / rows / freshness) at a glance.
 */
const KpiIconBadge = ({ color, children }: { color: string; children: ReactNode }) => (
  <Box
    sx={{
      width: 32,
      height: 32,
      borderRadius: '10px',
      display: 'grid',
      placeItems: 'center',
      color,
      bgcolor: alpha(color, 0.14),
    }}
  >
    {children}
  </Box>
);

export interface SettingsPageViewProps {
  storage: StorageOverview | null;
  ingestHealth: IngestHealth | null;
  systemBuild: SystemBuild | null;
  configuration: EffectiveConfiguration | null;
  purgePreview: PurgePreview | null;
  isStorageLoading: boolean;
  isIngestLoading: boolean;
  isBuildLoading: boolean;
  isConfigurationLoading: boolean;
  isPurgePreviewLoading: boolean;
  retentionDays: number;
  onRetentionDaysChange: (retentionDays: number) => void;
  isPurgeDialogOpen: boolean;
  onOpenPurgeDialog: () => void;
  onClosePurgeDialog: () => void;
  onConfirmPurge: () => void;
  isPurging: boolean;
  purgeError: Error | null;
  purgeResult: PurgeResult | null;
  isReloading: boolean;
  onReload: () => void;
  error: Error | null;
  activeTab: SettingsTab;
  onTabChange: (tab: SettingsTab) => void;
}

const SettingsPageView = ({
  storage,
  ingestHealth,
  systemBuild,
  configuration,
  purgePreview,
  isStorageLoading,
  isIngestLoading,
  isBuildLoading,
  isConfigurationLoading,
  isPurgePreviewLoading,
  retentionDays,
  onRetentionDaysChange,
  isPurgeDialogOpen,
  onOpenPurgeDialog,
  onClosePurgeDialog,
  onConfirmPurge,
  isPurging,
  purgeError,
  purgeResult,
  isReloading,
  onReload,
  error,
  activeTab,
  onTabChange,
}: SettingsPageViewProps) => {
  const theme: Theme = useTheme();
  const TABS = [
    { value: 'storage-ingest' as const, label: 'Storage & Ingest' },
    { value: 'schema-build' as const, label: 'Schema & Build' },
    { value: 'configuration' as const, label: 'Effective Configuration' },
    { value: 'retention' as const, label: 'Retention' },
  ];
  // Composition of the whole database, not of one table: three numbers summed
  // across every table, which is what a single ring can honestly represent.
  const compositionSlices = useMemo(() => {
    if (!storage || storage.tables.length === 0) {
      return [];
    }
    const totals = storage.tables.reduce(
      (accumulator, table) => ({
        heap: accumulator.heap + table.heapBytes,
        index: accumulator.index + table.indexBytes,
        toast: accumulator.toast + table.toastBytes,
      }),
      { heap: 0, index: 0, toast: 0 },
    );
    return [
      { label: 'Table data', value: totals.heap, color: colorForIndex(0) },
      { label: 'Indexes', value: totals.index, color: colorForIndex(1) },
      {
        label: 'Large values (TOAST)',
        value: totals.toast,
        color: colorForIndex(2),
      },
    ].filter((slice) => slice.value > 0);
  }, [storage]);

  const totalRowCount = useMemo(
    () =>
      storage
        ? storage.tables.reduce((sum, table) => sum + table.rowCount, 0)
        : 0,
    [storage],
  );

  const longestHistoryDays = useMemo(() => {
    if (!storage) {
      return null;
    }
    const spans = storage.tables
      .map(retentionSpanDays)
      .filter((span): span is number => span !== null);
    return spans.length > 0 ? Math.max(...spans) : null;
  }, [storage]);

  const daysToCeiling = storage
    ? daysUntilSize(
        storage.databaseTotalBytes,
        PROJECTION_CEILING_BYTES,
        storage.estimatedTotalBytesPerDay,
      )
    : null;

  const oldestIngestTimestamp = ingestHealth?.signals
    .map((signal) => signal.newestReceivedAt)
    .filter((timestamp): timestamp is string => Boolean(timestamp))
    .sort()[0];

  return (
    <PageLayout
      eyebrow={groupForPath('/settings')}
      title="Settings"
      subtitle={
        'Storage, ingest health, schema history, and the effective configuration driving every ' +
        'aggregation. Read-only apart from the retention purge at the foot of the page, which ' +
        'deletes permanently and asks first.'
      }
      error={error}
      actions={
        <Tooltip title="Refresh">
          <Button
            variant="outlined"
            size="medium"
            onClick={onReload}
            disabled={isReloading}
            sx={{
              minWidth: 'auto',
              px: 1,
              '@keyframes spin': {
                from: { transform: 'rotate(0deg)' },
                to: { transform: 'rotate(360deg)' },
              },
            }}
          >
            <RefreshIcon
              sx={
                isReloading
                  ? {
                      animation: 'spin 1s linear infinite',
                    }
                  : undefined
              }
            />
          </Button>
        </Tooltip>
      }
    >
      {/* Headline figures */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: {
            xs: '1fr',
            sm: 'repeat(2, 1fr)',
            md: 'repeat(4, 1fr)',
          },
          gap: 2,
        }}
      >
        <StatCard
          label="Database size"
          value={storage ? formatBytes(storage.databaseTotalBytes) : '—'}
          sub={storage ? `across ${storage.tables.length} tables` : undefined}
          accent
          adornment={
            <KpiIconBadge color={theme.palette.primary.main}>
              <DatabaseKpiIcon />
            </KpiIconBadge>
          }
        />
        <StatCard
          label="Estimated growth"
          value={
            storage && storage.estimatedTotalBytesPerDay > 0
              ? `${formatBytes(storage.estimatedTotalBytesPerDay)}/day`
              : '—'
          }
          sub={
            daysToCeiling === null
              ? 'projected from the last 7 days'
              : `~${daysToCeiling.toLocaleString()} days to ${PROJECTION_CEILING_LABEL}`
          }
          adornment={
            <KpiIconBadge color={auroraColors.pink}>
              <GrowthKpiIcon />
            </KpiIconBadge>
          }
        />
        <StatCard
          label="Total rows"
          value={storage ? formatCompact(totalRowCount) : '—'}
          sub={
            longestHistoryDays === null
              ? 'nothing is ever pruned'
              : `${longestHistoryDays.toLocaleString()} days of history, never pruned`
          }
          adornment={
            <KpiIconBadge color={auroraColors.cyan}>
              <RowsKpiIcon />
            </KpiIconBadge>
          }
        />
        <StatCard
          label="Last received"
          value={
            oldestIngestTimestamp
              ? formatRelativeTime(oldestIngestTimestamp)
              : '—'
          }
          sub={
            ingestHealth
              ? `oldest of ${ingestHealth.signals.length} signals`
              : undefined
          }
          adornment={
            <KpiIconBadge color={theme.palette.success.main}>
              <ClockKpiIcon />
            </KpiIconBadge>
          }
        />
      </Box>

      <PillTabs
        tabs={TABS}
        activeValue={activeTab}
        onChange={onTabChange}
        ariaLabel="Settings sections"
      />

      <Box>
        {/* Storage & ingest tab */}
        {activeTab === 'storage-ingest' && (
          <>
            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr', md: '1.6fr 1fr' },
                gap: 2,
                alignItems: 'stretch',
              }}
            >
              <StorageBreakdownCard
                storage={storage}
                isStorageLoading={isStorageLoading}
              />
              <DonutCard
                title="What the space holds"
                slices={compositionSlices}
                centerValue={
                  storage ? formatBytes(storage.databaseTotalBytes) : '—'
                }
                centerLabel="on disk"
                ranked
                formatSliceValue={formatBytes}
                hasData={compositionSlices.length > 0}
                isLoading={isStorageLoading}
                emptyLabel="No storage reported."
              />
            </Box>

            <IngestHealthCard
              ingestHealth={ingestHealth}
              isIngestLoading={isIngestLoading}
            />
          </>
        )}

        {/* Schema & build tab */}
        {activeTab === 'schema-build' && (
          <SchemaBuildCard
            systemBuild={systemBuild}
            isBuildLoading={isBuildLoading}
          />
        )}

        {/* Effective configuration tab */}
        {activeTab === 'configuration' && (
          <EffectiveConfigurationCard
            configuration={configuration}
            isConfigurationLoading={isConfigurationLoading}
          />
        )}

        {/* Retention tab */}
        {activeTab === 'retention' && (
          <PurgeDryRunCard
            purgePreview={purgePreview}
            isPurgePreviewLoading={isPurgePreviewLoading}
            retentionDays={retentionDays}
            onRetentionDaysChange={onRetentionDaysChange}
            isPurgeDialogOpen={isPurgeDialogOpen}
            onOpenPurgeDialog={onOpenPurgeDialog}
            onClosePurgeDialog={onClosePurgeDialog}
            onConfirmPurge={onConfirmPurge}
            isPurging={isPurging}
            purgeError={purgeError}
            purgeResult={purgeResult}
          />
        )}
      </Box>
    </PageLayout>
  );
};

export default SettingsPageView;
