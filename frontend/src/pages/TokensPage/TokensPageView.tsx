import { useMemo } from 'react';
import { Box, Paper, Tooltip, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import AreaTrendChart, { AreaTrendLegend, useSeriesVisibility } from '../../components/AreaTrendChart';
import { colorForIndex } from '../../theme/theme';
import type { WindowOption } from '../../lib/constants';
import type {
  SessionCacheEfficiencyRow,
  TokenUsageSummary,
  ToolContextFootprintRow,
  WindowSelection,
} from '../../api';
import TokenSummaryCards from './components/TokenSummaryCards';
import TokenByModelCard from './components/TokenByModelCard';
import TokenCompositionCard from './components/TokenCompositionCard';
import CacheEfficiencyRankCard from './components/CacheEfficiencyRankCard';
import ContextFootprintCard from './components/ContextFootprintCard';
import { formatCompact, shortModelName } from '../../lib/format';
import { cacheEfficiencyBand, type CacheEfficiencyBand } from '../../lib/cacheEfficiency';

/**
 * Mirrors the backend's tuning.cache-efficiency-minimum-input-tokens default.
 * Display-only — the server owns the actual floor and applies it before ranking;
 * this just names it in the card's explanatory copy.
 */
const CACHE_EFFICIENCY_FLOOR_LABEL = '100K';

export interface TokensPageViewProps {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows: readonly WindowOption[];
  summary: TokenUsageSummary;
  cacheEfficiencyRows: SessionCacheEfficiencyRow[];
  contextFootprintRows: ToolContextFootprintRow[];
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
  const band = cacheEfficiencyBand(denominatorEmpty ? null : summary.cacheReadRatio);
  const healthy = band === 'strong';

  const ratioColor = {
    strong: theme.palette.success.main,
    mixed: theme.palette.warning.main,
    weak: theme.palette.error.main,
    unknown: theme.palette.text.disabled,
  }[band];

  const totalTokens =
    summary.inputTokens
    + summary.outputTokens
    + summary.cacheCreationTokens
    + summary.cacheReadTokens;

  // Donut mix — ordered largest-first to read like the mockup.
  const mixSlices = [
    { label: 'Cache read', value: summary.cacheReadTokens, color: colorForIndex(0) },
    { label: 'Input', value: summary.inputTokens, color: colorForIndex(1) },
    { label: 'Cache creation', value: summary.cacheCreationTokens, color: colorForIndex(2) },
    { label: 'Output', value: summary.outputTokens, color: colorForIndex(3) },
  ]
    .sort((left, right) => right.value - left.value)
    .map((slice) => ({ ...slice, description: TYPE_DESCRIPTIONS[slice.label] ?? '' }));

  const axisDates = useMemo(
    () => summary.points.map((point) => new Date(point.timestamp)),
    [summary],
  );

  const series = useMemo(
    () => [
      { label: 'Cache read', data: summary.points.map((point) => point.cacheRead), color: colorForIndex(0) },
      { label: 'Cache creation', data: summary.points.map((point) => point.cacheCreation), color: colorForIndex(1) },
      { label: 'Input', data: summary.points.map((point) => point.input), color: colorForIndex(2) },
      { label: 'Output', data: summary.points.map((point) => point.output), color: colorForIndex(3) },
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

  // Window label for cost captions, derived from the selection (never hardcode "24h").
  let windowLabel: string;
  if (selection.kind === 'preset') {
    windowLabel = windows.find((option) => option.value === selection.minutes)?.label ?? 'window';
  } else {
    windowLabel = 'selected range';
  }

  // Total cost reads the existing pre-formatted CostSummary strings (summary.cost.*) —
  // NOT a flat number. Delta arrow/color is sign-aware: down + green when spend fell.
  const costDecreased = summary.cost.deltaPct.trim().startsWith('-');
  const costMagnitude = summary.cost.deltaPct.replace(/^[+-]/, '');
  const costSub = (
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
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
        <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 1.1, fontSize: 24, whiteSpace: 'nowrap' }}>
          <Box sx={{ width: 11, height: 11, borderRadius: '3px', bgcolor: colorForIndex(topModel.colorIndex), flexShrink: 0 }} />
          {shortModelName(topModel.model)}
        </Box>
      ) : '—',
      sub: topModel ? `${topModel.share}% of tokens · ${topModel.tokens}` : undefined,
    },
  ];

  return (
    <PageLayout
      eyebrow="Activity"
      title="Token Usage"
      subtitle={
        'Spend, token volume and composition over the selected window. The cache-read ratio '
        + 'shows how much of every turn reuses prior context vs. paying to rebuild it.'
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
      {/* Top-row summary KPIs */}
      <TokenSummaryCards cards={summaryCards} />

      {/* Token usage over time — Aurora area chart */}
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1, mb: 1 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
            <Typography variant="subtitle1">Token usage over time</Typography>
            <Tooltip
              title={
                <Box component="ul" sx={{ m: 0, pl: 2, py: 0.5 }}>
                  <li>Cache read — tokens reused from a previous turn's cached context</li>
                  <li>Cache creation — tokens written into the cache for the first time</li>
                  <li>Input — non-cached prompt tokens sent to the model</li>
                  <li>Output — tokens generated by the model</li>
                </Box>
              }
              arrow
            >
              <InfoOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }} />
            </Tooltip>
          </Box>
          <AreaTrendLegend
            items={series.map((s) => ({ label: s.label, color: s.color }))}
            visibility={tokenChartVisibility}
          />
        </Box>
        {!hasChartData && !isLoading ? (
          <Box sx={{ height: 280, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
            <Typography color="text.secondary">{emptyMessage}</Typography>
          </Box>
        ) : (
          <AreaTrendChart
            axisDates={axisDates}
            series={series.map((s) => ({ label: s.label, data: s.data, color: s.color }))}
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

      {/* Which sessions are rebuilding context instead of reusing it */}
      <CacheEfficiencyRankCard
        rows={cacheEfficiencyRows}
        minimumInputTokensLabel={CACHE_EFFICIENCY_FLOOR_LABEL}
      />

      {/* Estimated per-tool context footprint — kept below the exact-token cards
          and visually flagged so the estimate never reads as billed spend */}
      <ContextFootprintCard rows={contextFootprintRows} />

      {/* Per-model token sums — full width, below the composition card */}
      <TokenByModelCard rows={modelRows} />
    </PageLayout>
  );
};

export default TokensPageView;
