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
import { useMemo } from 'react';
import { Box, Paper, Stack, Tooltip, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import PillTabs, { type PillTabItem } from '../../components/PillTabs';
import AreaTrendChart, {
  AreaTrendLegend,
  useSeriesVisibility,
} from '../../components/AreaTrendChart';
import { colorForIndex } from '../../theme/theme';
import type { WindowOption } from '../../lib/constants';
import type {
  SessionCacheEfficiencyRow,
  TokenUsageSummary,
  ToolContextFootprintRow,
  WindowSelection,
} from '../../api';
import TokenSummaryCards from './components/TokenSummaryCards';
import TokenCostByModelCard from './components/TokenCostByModelCard';
import TokenCompositionCard from './components/TokenCompositionCard';
import CacheEfficiencyRankCard from './components/CacheEfficiencyRankCard';
import ContextFootprintCard from './components/ContextFootprintCard';
import SessionCacheEfficiencyDialog from './components/SessionCacheEfficiencyDialog';
import { formatCompact, shortModelName } from '../../lib/format';
import {
  cacheEfficiencyBand,
  type CacheEfficiencyBand,
} from '../../lib/cacheEfficiency';
import { cacheEfficiencyBandColor } from './components/cacheEfficiencyBandColors';
import { TOKEN_KIND_COLORS, TOKEN_KIND_LABELS } from './tokenKindColors';

/**
 * Mirrors the backend's tuning.cache-efficiency-minimum-input-tokens default.
 * Display-only — the server owns the actual floor and applies it before ranking;
 * this just names it in the card's explanatory copy.
 */
const CACHE_EFFICIENCY_FLOOR_LABEL = '100K';

/**
 * The page's two views. "Overview" answers "what did this window cost and what
 * shape were the tokens"; "Cache & Context" answers "which sessions and tools
 * are wasting it" — a diagnostic pass that only matters once the overview looks
 * wrong, which is why it is a tab rather than more scroll.
 */
export type TokensPageTab = 'overview' | 'cache-context';

const TOKENS_PAGE_TABS: readonly PillTabItem<TokensPageTab>[] = [
  { value: 'overview', label: 'Overview' },
  { value: 'cache-context', label: 'Cache & Context' },
];

export interface TokensPageViewProps {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows: readonly WindowOption[];
  summary: TokenUsageSummary;
  cacheEfficiencyRows: SessionCacheEfficiencyRow[];
  contextFootprintRows: ToolContextFootprintRow[];
  activeTab: TokensPageTab;
  onActiveTabChange: (next: TokensPageTab) => void;
  /** Ranking row whose detail dialog is open; null when it is closed. */
  selectedCacheEfficiencyRow: SessionCacheEfficiencyRow | null;
  onSelectCacheEfficiencyRow: (next: SessionCacheEfficiencyRow | null) => void;
  isLoading: boolean;
  error: Error | null;
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  isPolling: boolean;
}

// The gauge's bands are the shared cache-efficiency bands (lib/cacheEfficiency),
// not a second set local to this page: `summary.cacheReadRatio` is now the
// window-level form of the exact ratio the Sessions grid column renders, so a
// row reading "weak" there and the gauge reading "healthy" here would be a
// contradiction rather than two different measurements.
const BAND_LABELS: Record<CacheEfficiencyBand, string> = {
  strong: 'Healthy — most prompts are reusing cache',
  mixed: 'Mixed — some prompts paying full freight',
  weak: 'Poor — context placement is wasting per-turn spend',
  unknown: 'No cache activity in this window',
};

// Short explanation shown under each token-type in the donut legend.
const TYPE_DESCRIPTIONS: Record<string, string> = {
  'Cache read': 'reused from cache',
  Input: 'non-cached prompt',
  'Cache creation': 'written to cache',
  Output: 'model generated',
};

const TokensPageView = ({
  selection,
  onSelectionChange,
  windows,
  summary,
  cacheEfficiencyRows,
  contextFootprintRows,
  activeTab,
  onActiveTabChange,
  selectedCacheEfficiencyRow,
  onSelectCacheEfficiencyRow,
  isLoading,
  error,
  onReload,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
}: TokensPageViewProps) => {
  const theme = useTheme();
  // The gauge's denominator is the ratio's own denominator — all input-side
  // tokens. Output is excluded (generated, never sent), which is why this is not
  // simply `totalTokens`.
  const inputSideTokens =
    summary.inputTokens + summary.cacheCreationTokens + summary.cacheReadTokens;
  const denominatorEmpty = inputSideTokens === 0;
  const band = cacheEfficiencyBand(
    denominatorEmpty ? null : summary.cacheReadRatio,
  );
  const healthy = band === 'strong';

  // Shared with the rank table's fill/percentage, the session detail dialog's
  // chip/KPI, and the Sessions grid's CacheEfficiencyCell — a session must read
  // the same band color everywhere it appears, and this used to disagree
  // (weak read error.main here, warning.main everywhere else).
  const ratioColor = cacheEfficiencyBandColor(band, theme);

  const totalTokens =
    summary.inputTokens +
    summary.outputTokens +
    summary.cacheCreationTokens +
    summary.cacheReadTokens;

  // Donut mix — ordered largest-first to read like the mockup. Colors come from
  // TOKEN_KIND_COLORS (keyed by kind), not a raw colorForIndex(0-3) call, so a
  // kind's color agrees with the trend chart's series below and the detail
  // dialog's token bar.
  const mixSlices = [
    {
      label: TOKEN_KIND_LABELS.cacheRead,
      value: summary.cacheReadTokens,
      color: TOKEN_KIND_COLORS.cacheRead,
    },
    {
      label: TOKEN_KIND_LABELS.input,
      value: summary.inputTokens,
      color: TOKEN_KIND_COLORS.input,
    },
    {
      label: TOKEN_KIND_LABELS.cacheCreation,
      value: summary.cacheCreationTokens,
      color: TOKEN_KIND_COLORS.cacheCreation,
    },
    {
      label: TOKEN_KIND_LABELS.output,
      value: summary.outputTokens,
      color: TOKEN_KIND_COLORS.output,
    },
  ]
    .sort((left, right) => right.value - left.value)
    .map((slice) => ({
      ...slice,
      description: TYPE_DESCRIPTIONS[slice.label] ?? '',
    }));

  const axisDates = useMemo(
    () => summary.points.map((point) => new Date(point.timestamp)),
    [summary],
  );

  // Same TOKEN_KIND_COLORS map as mixSlices above — the series array order here
  // is deliberately different (drives stacking/legend order) but the color per
  // kind must not be, or the same kind would paint two colors on this page.
  const series = useMemo(
    () => [
      {
        label: TOKEN_KIND_LABELS.cacheRead,
        data: summary.points.map((point) => point.cacheRead),
        color: TOKEN_KIND_COLORS.cacheRead,
      },
      {
        label: TOKEN_KIND_LABELS.cacheCreation,
        data: summary.points.map((point) => point.cacheCreation),
        color: TOKEN_KIND_COLORS.cacheCreation,
      },
      {
        label: TOKEN_KIND_LABELS.input,
        data: summary.points.map((point) => point.input),
        color: TOKEN_KIND_COLORS.input,
      },
      {
        label: TOKEN_KIND_LABELS.output,
        data: summary.points.map((point) => point.output),
        color: TOKEN_KIND_COLORS.output,
      },
    ],
    [summary],
  );

  const hasChartData = axisDates.length >= 2;
  const tokenChartVisibility = useSeriesVisibility(series.length);
  const emptyMessage =
    axisDates.length === 1
      ? 'Only one bucket in this window — need at least two to plot a trend.'
      : 'No data in this window.';

  // Top-row summary KPIs.
  const tokenTypeCount = [
    summary.inputTokens,
    summary.outputTokens,
    summary.cacheCreationTokens,
    summary.cacheReadTokens,
  ].filter((value) => value > 0).length;
  const modelRows = summary.byModel ?? [];
  const topModel = modelRows[0];

  // Merged "Tokens & cost by model" table row set — zips summary.byModel
  // (TokenModelShare[]) with summary.cost.byModel (CostModelShare[]) on
  // `model`. Both halves are pre-formatted by the backend and already sorted
  // by their own metric; re-sort by token share here so the table has one
  // consistent order regardless of how the two arrays individually ordered
  // themselves.
  const tokenCostRows = useMemo(() => {
    const costByModel = new Map(
      summary.cost.byModel.map((row) => [row.model, row]),
    );
    return (summary.byModel ?? [])
      .map((tokenRow) => {
        const costRow = costByModel.get(tokenRow.model);
        return {
          model: tokenRow.model,
          colorIndex: tokenRow.colorIndex,
          tokens: tokenRow.tokens,
          tokenShare: tokenRow.share,
          usd: costRow?.usd ?? '—',
          costShare: costRow?.share ?? 0,
        };
      })
      .sort((left, right) => right.tokenShare - left.tokenShare);
  }, [summary.byModel, summary.cost.byModel]);

  // Window label for cost captions, derived from the selection (never hardcode "24h").
  let windowLabel: string;
  if (selection.kind === 'preset') {
    windowLabel =
      windows.find((option) => option.value === selection.minutes)?.label ??
      'window';
  } else {
    windowLabel = 'selected range';
  }

  // Total cost reads the existing pre-formatted CostSummary strings (summary.cost.*) —
  // NOT a flat number. Delta arrow/color is sign-aware: down + green when spend fell.
  const costDecreased = summary.cost.deltaPct.trim().startsWith('-');
  const costMagnitude = summary.cost.deltaPct.replace(/^[+-]/, '');
  const costSub = (
    <Box
      component="span"
      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}
    >
      <Box
        component="span"
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.25,
          fontWeight: 700,
          color: costDecreased ? 'success.main' : 'text.primary',
        }}
      >
        {costDecreased ? '↓' : '↑'} {costMagnitude}
      </Box>
      {`vs. prev ${windowLabel}`}
    </Box>
  );

  const summaryCards = [
    {
      label: 'Total cost',
      value: summary.cost.spend24h,
      sub: costSub,
      accent: true,
      infoTooltip:
        'Based on a running cost counter, not the exact per-call cost the Cost page uses. '
        + 'The two don\'t line up exactly — expect "Total spend" on the Cost page to read a '
        + 'few percent lower than this for the same window.',
      infoTooltipSeverity: 'warning' as const,
    },
    {
      label: 'Total tokens',
      value: formatCompact(totalTokens),
      sub: `${tokenTypeCount} token types`,
    },
    {
      label: 'Models used',
      value: modelRows.length,
      sub: modelRows.map((row) => shortModelName(row.model)).join(' · ') || '—',
    },
    {
      label: 'Top model',
      value: topModel ? (
        <Box
          component="span"
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 1.1,
            fontSize: 24,
            whiteSpace: 'nowrap',
          }}
        >
          <Box
            sx={{
              width: 11,
              height: 11,
              borderRadius: '3px',
              bgcolor: colorForIndex(topModel.colorIndex),
              flexShrink: 0,
            }}
          />
          {shortModelName(topModel.model)}
        </Box>
      ) : (
        '—'
      ),
      sub: topModel
        ? `${topModel.share}% of tokens · ${topModel.tokens}`
        : undefined,
    },
  ];

  return (
    <PageLayout
      eyebrow="Activity"
      title="Token Usage"
      subtitle={
        'Spend, token volume and composition over the selected window. The cache-read ratio ' +
        'shows how much of every turn reuses prior context vs. paying to rebuild it.'
      }
      error={error}
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
    >
      <PillTabs
        tabs={TOKENS_PAGE_TABS}
        activeValue={activeTab}
        onChange={onActiveTabChange}
        ariaLabel="Token usage views"
      />

      {activeTab === 'overview' && (
        <Stack spacing={3}>
          {/* Top-row summary KPIs */}
          <TokenSummaryCards cards={summaryCards} />

          {/* Merged composition: token-mix donut + cache-read-ratio health */}
          <TokenCompositionCard
            slices={mixSlices}
            centerValue={formatCompact(totalTokens)}
            centerLabel="tokens"
            cacheReadRatio={summary.cacheReadRatio}
            ratioColor={ratioColor}
            ratioLabel={BAND_LABELS[band]}
            healthy={healthy}
            ratioEmpty={denominatorEmpty}
            savedTokens={formatCompact(summary.cacheReadTokens)}
          />

          {/* Merged per-model table — tokens + cost, below the composition card */}
          <TokenCostByModelCard rows={tokenCostRows} />

          {/* Token usage over time — Aurora area chart. Last in the Overview
              stack (not 3rd, between composition and the by-model table). */}
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
                gap: 1,
                mb: 1,
              }}
            >
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
                <Typography variant="subtitle1">
                  Token usage over time
                </Typography>
                <Tooltip
                  title={
                    <Box component="ul" sx={{ m: 0, pl: 2, py: 0.5 }}>
                      <li>
                        Cache read — tokens reused from a previous turn's cached
                        context
                      </li>
                      <li>
                        Cache creation — tokens written into the cache for the
                        first time
                      </li>
                      <li>
                        Input — non-cached prompt tokens sent to the model
                      </li>
                      <li>Output — tokens generated by the model</li>
                    </Box>
                  }
                  arrow
                >
                  <InfoOutlinedIcon
                    sx={{
                      fontSize: 18,
                      color: 'text.secondary',
                      cursor: 'help',
                    }}
                  />
                </Tooltip>
              </Box>
              <AreaTrendLegend
                items={series.map((s) => ({ label: s.label, color: s.color }))}
                visibility={tokenChartVisibility}
              />
            </Box>
            {!hasChartData && !isLoading ? (
              <Box
                sx={{
                  height: 280,
                  display: 'flex',
                  justifyContent: 'center',
                  alignItems: 'center',
                }}
              >
                <Typography color="text.secondary">{emptyMessage}</Typography>
              </Box>
            ) : (
              <AreaTrendChart
                axisDates={axisDates}
                series={series.map((s) => ({
                  label: s.label,
                  data: s.data,
                  color: s.color,
                }))}
                yLabel="tokens (log)"
                formatY={formatCompact}
                height={320}
                stacked={false}
                yScale="log"
                activeStates={tokenChartVisibility.active}
                focusedLabel={tokenChartVisibility.focused}
              />
            )}
          </Paper>
        </Stack>
      )}

      {activeTab === 'cache-context' && (
        <Stack spacing={3}>
          {/* Which sessions are rebuilding context instead of reusing it */}
          <CacheEfficiencyRankCard
            rows={cacheEfficiencyRows}
            minimumInputTokensLabel={CACHE_EFFICIENCY_FLOOR_LABEL}
            onSelectSession={onSelectCacheEfficiencyRow}
          />

          {/* Estimated per-tool context footprint — kept off the Overview tab and
              visually flagged so the estimate never reads as billed spend */}
          <ContextFootprintCard rows={contextFootprintRows} />

          <SessionCacheEfficiencyDialog
            row={selectedCacheEfficiencyRow}
            onClose={() => onSelectCacheEfficiencyRow(null)}
          />
        </Stack>
      )}
    </PageLayout>
  );
};

export default TokensPageView;
