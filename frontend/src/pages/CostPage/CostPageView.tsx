import { Box, Paper, Tooltip, Typography } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import PillTabs from '../../components/PillTabs';
import StatCard from '../../components/StatCard';
import DonutCard from '../../components/DonutCard';
import AreaTrendChart, { AreaTrendLegend, useSeriesVisibility } from '../../components/AreaTrendChart';
import type {
  CostBreakdown,
  CostIdentifierShare,
  CostSessionShare,
  WindowSelection,
} from '../../api';
import type { WindowOption } from '../../lib/constants';
import { USD_FORMATTER, shortModelName } from '../../lib/format';
import { colorForIndex } from '../../theme/theme';
import { fontFamilies } from '../../theme/typography';
import MoneyMapCard from './components/MoneyMapCard';
import CostDriversCard from './components/CostDriversCard';
import TopSessionsCard from './components/TopSessionsCard';
import SessionCostDialog from './components/SessionCostDialog';
import { buildTrendSeries, buildModelMix, CATEGORY_LABELS, COST_SOURCE_INFO_TOOLTIP } from './costDerivations';

export type CostPageTab = 'overview' | 'drivers';

const COST_PAGE_TABS: { value: CostPageTab; label: string }[] = [
  { value: 'overview', label: 'Where it went' },
  { value: 'drivers', label: 'What drove it' },
];

export interface CostPageViewProps {
  selection: WindowSelection;
  onSelectionChange: (selection: WindowSelection) => void;
  windows: readonly WindowOption[];
  breakdown: CostBreakdown;
  activeTab: CostPageTab;
  onActiveTabChange: (tab: CostPageTab) => void;
  /** Session whose cost detail dialog is open; null when it is closed. */
  selectedSession: CostSessionShare | null;
  onSelectSession: (session: CostSessionShare | null) => void;
  isLoading: boolean;
  error: Error | null;
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (autoRefresh: boolean) => void;
  isPolling: boolean;
}

/** Leading characters shown for a session id in the space-constrained "Priciest
 *  session" KPI tile — the one legitimate exception to this page's
 *  never-truncate-a-session-id rule (see the CLAUDE.md gotcha): the full id is
 *  still available via the tile's tooltip, and the "Most expensive sessions"
 *  table row below always prints it in full. */
const SESSION_ID_PREVIEW_LENGTH = 8;

const usdOrDash = (value: number): string => (Number.isFinite(value) ? USD_FORMATTER.format(value) : '—');

// Cost per 1k tokens is usually well under a cent — cache-read tokens dominate
// the denominator but cost comparatively little (see AGENTS.md) — so the shared
// 2-decimal USD_FORMATTER rounds real, nonzero figures down to "$0.00". On a
// cache-heavy window even 3 decimals isn't enough (real figures routinely land
// around $0.0003/1k, which still rounds to "$0.000" — indistinguishable from a
// genuine zero), so this is fixed at 5 decimals instead, matching the Tokens
// page's identical figure: the backend's `MetricService.formatCostPer1k`
// formats its own (server-computed) cost-per-1k as `"$%.5f"`. This one is
// computed client-side (see costPer1k below) so it needs its own formatter,
// but the precision matches on purpose.
const COST_PER_1K_FORMATTER = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 5,
  maximumFractionDigits: 5,
});
const costPer1kOrDash = (value: number): string =>
  (Number.isFinite(value) ? COST_PER_1K_FORMATTER.format(value) : '—');

const toDonutSlices = (rows: CostIdentifierShare[]) =>
  rows
    .filter((row) => row.costUsd > 0)
    .map((row, index) => ({
      label: row.identifier,
      value: row.costUsd,
      color: colorForIndex(index),
    }));

const CostPageView = ({
  selection,
  onSelectionChange,
  windows,
  breakdown,
  activeTab,
  onActiveTabChange,
  selectedSession,
  onSelectSession,
  isLoading,
  error,
  onReload,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
}: CostPageViewProps) => {
  const { axisDates, series } = buildTrendSeries(breakdown.trend);
  const visibility = useSeriesVisibility(series.length);

  const totalTokens =
    breakdown.totalInputTokens
    + breakdown.totalOutputTokens
    + breakdown.totalCacheCreationTokens
    + breakdown.totalCacheReadTokens;
  const costPer1k = totalTokens === 0 ? 0 : (breakdown.totalCostUsd / totalTokens) * 1000;
  const burnRatePerDay = breakdown.burnRatePerHour * 24;

  // The Skill mix / Subagent mix donuts read the SAME drilldown the money map's
  // SKILL/SUBAGENT category rows already carry -- no separate query needed. See
  // CostCategoryShare's doc: identifiedCostUsd is the drilldown's own sum, which can
  // be less than the category's costUsd (e.g. a subagent dispatch with no matching
  // execution span), so the donut center value intentionally reads identifiedCostUsd,
  // not costUsd.
  const skillCategory = breakdown.categories.find((category) => category.category === 'SKILL');
  const subagentCategory = breakdown.categories.find((category) => category.category === 'SUBAGENT');
  const skillRows = skillCategory?.drilldown ?? [];
  const subagentRows = subagentCategory?.drilldown ?? [];
  const skillTotal = skillCategory?.identifiedCostUsd ?? 0;
  const subagentTotal = subagentCategory?.identifiedCostUsd ?? 0;

  // Single source for the Model mix donut, the Top model KPI, and CostDriversCard's
  // per-row dot color — see buildModelMix's doc for why a model's color must not be
  // derived independently in more than one place.
  const modelMix = buildModelMix(breakdown.modelEffort);
  const modelColorByName = new Map(modelMix.map((entry) => [entry.model, entry.colorIndex]));
  const modelColorIndex = (model: string): number => modelColorByName.get(model) ?? 0;

  const topCategory = breakdown.categories[0];
  const topModel = modelMix[0];
  const topModelShare =
    topModel && breakdown.totalCostUsd > 0 ? (topModel.costUsd / breakdown.totalCostUsd) * 100 : 0;
  const priciestSession = breakdown.topSessions[0];

  return (
    <PageLayout
      title="Cost"
      subtitle={
        'Where spend went over the selected window, measured from api_request records — the '
        + 'exact per-call figure, not the cumulative counter shown on Tokens and Sessions. The '
        + 'two do not reconcile; expect this total to read a few percent lower.'
      }
      actions={
        <PageActions
          selection={selection}
          onSelectionChange={onSelectionChange}
          windows={windows}
          onReload={onReload}
          autoRefresh={autoRefresh}
          onAutoRefreshChange={onAutoRefreshChange}
          isPolling={isPolling}
        />
      }
      error={error}
    >
      <PillTabs tabs={COST_PAGE_TABS} activeValue={activeTab} onChange={onActiveTabChange} ariaLabel="Cost page views" />

      {activeTab === 'overview' ? (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' }, gap: 2 }}>
          <StatCard
            label="Total spend"
            value={usdOrDash(breakdown.totalCostUsd)}
            accent
            infoTooltip={COST_SOURCE_INFO_TOOLTIP}
            infoTooltipSeverity="warning"
            trend={
              breakdown.priorCostUsd > 0
                ? {
                    delta: `${breakdown.deltaPct >= 0 ? '+' : ''}${breakdown.deltaPct.toFixed(1)}%`,
                    direction: breakdown.deltaPct >= 0 ? 'up' : 'down',
                  }
                : undefined
            }
            sub="vs. prior window"
          />
          <StatCard label="Burn rate" value={`${usdOrDash(burnRatePerDay)}/day`} />
          <StatCard label="Projected 30d" value={usdOrDash(breakdown.projected30dUsd)} />
          <StatCard
            label="Top category"
            value={topCategory ? CATEGORY_LABELS[topCategory.category] : '—'}
            sub={
              topCategory ? (
                <>
                  <Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}>
                    {usdOrDash(topCategory.costUsd)}
                  </Box>{' '}
                  · {topCategory.share.toFixed(1)}% of spend
                </>
              ) : undefined
            }
          />
        </Box>
      ) : (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' }, gap: 2 }}>
          <StatCard label="Requests" value={breakdown.totalRequests.toLocaleString()} />
          <StatCard label="Cost per 1k tokens" value={costPer1kOrDash(costPer1k)} />
          <StatCard
            label="Top model"
            value={topModel ? shortModelName(topModel.model) : '—'}
            sub={
              topModel ? (
                <>
                  <Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}>
                    {usdOrDash(topModel.costUsd)}
                  </Box>{' '}
                  · {topModelShare.toFixed(1)}% of spend
                </>
              ) : undefined
            }
          />
          <StatCard
            label="Priciest session"
            value={priciestSession ? usdOrDash(priciestSession.costUsd) : '—'}
            sub={
              priciestSession ? (
                <Box component="span">
                  {priciestSession.requests.toLocaleString()} requests ·{' '}
                  <Tooltip title={priciestSession.sessionId} arrow>
                    <Box component="span" sx={{ fontFamily: fontFamilies.mono }}>
                      {priciestSession.sessionId.length > SESSION_ID_PREVIEW_LENGTH
                        ? `${priciestSession.sessionId.slice(0, SESSION_ID_PREVIEW_LENGTH)}…`
                        : priciestSession.sessionId}
                    </Box>
                  </Tooltip>
                </Box>
              ) : undefined
            }
          />
        </Box>
      )}

      {activeTab === 'overview' ? (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <MoneyMapCard categories={breakdown.categories} isLoading={isLoading} />

          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 2 }}>
            <DonutCard
              title="Skill mix"
              slices={toDonutSlices(skillRows)}
              ranked
              centerValue={usdOrDash(skillTotal)}
              centerLabel="skill spend"
              hasData={skillRows.some((row) => row.costUsd > 0)}
              isLoading={isLoading}
              emptyLabel="No priced skill invocations in this window."
              formatSliceValue={(value) => USD_FORMATTER.format(value)}
            />
            <DonutCard
              title="Subagent mix"
              slices={toDonutSlices(subagentRows)}
              ranked
              centerValue={usdOrDash(subagentTotal)}
              centerLabel="subagent spend"
              hasData={subagentRows.some((row) => row.costUsd > 0)}
              isLoading={isLoading}
              emptyLabel="No priced subagent calls in this window."
              formatSliceValue={(value) => USD_FORMATTER.format(value)}
            />
          </Box>

          <Paper variant="outlined" sx={{ p: 2 }}>
            <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', mb: 1, flexWrap: 'wrap', gap: 1 }}>
              <Typography variant="subtitle1">Spend over time</Typography>
              <AreaTrendLegend
                items={series.map((item) => ({ label: item.label, color: item.color }))}
                visibility={visibility}
              />
            </Box>
            {axisDates.length >= 2 ? (
              <AreaTrendChart
                axisDates={axisDates}
                series={series}
                yLabel="USD (stacked)"
                formatY={(value) => `$${value.toFixed(0)}`}
                activeStates={visibility.active}
                focusedLabel={visibility.focused}
                stacked
              />
            ) : (
              <Box sx={{ py: 4, textAlign: 'center', color: 'text.secondary' }}>
                Not enough buckets to plot a trend for this window.
              </Box>
            )}
          </Paper>
        </Box>
      ) : (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <DonutCard
            title="Model mix"
            description={`${modelMix.length} model${modelMix.length === 1 ? '' : 's'} identified, ranked by spend`}
            slices={modelMix.map((entry) => ({
              label: shortModelName(entry.model),
              value: entry.costUsd,
              color: colorForIndex(entry.colorIndex),
            }))}
            ranked
            orientation="horizontal"
            showBars
            centerValue={usdOrDash(breakdown.totalCostUsd)}
            centerLabel="total spend"
            hasData={modelMix.length > 0}
            isLoading={isLoading}
            emptyLabel="No priced requests in this window."
            formatSliceValue={(value) => USD_FORMATTER.format(value)}
          />
          <CostDriversCard cells={breakdown.modelEffort} isLoading={isLoading} modelColorIndex={modelColorIndex} />
          <TopSessionsCard
            sessions={breakdown.topSessions}
            totalCostUsd={breakdown.totalCostUsd}
            isLoading={isLoading}
            onSelectSession={onSelectSession}
          />
          <SessionCostDialog
            session={selectedSession}
            totalCostUsd={breakdown.totalCostUsd}
            onClose={() => onSelectSession(null)}
          />
        </Box>
      )}
    </PageLayout>
  );
};

export default CostPageView;
