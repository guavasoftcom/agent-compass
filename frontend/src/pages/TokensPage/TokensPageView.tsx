import { useMemo } from 'react';
import { Box, Paper, Tooltip, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import AreaTrendChart, { AreaTrendLegend, useSeriesVisibility } from '../../components/AreaTrendChart';
import { colorForIndex } from '../../theme/theme';
import type { WindowOption } from '../../lib/constants';
import type { TokenUsageSummary, WindowSelection } from '../../api';
import TokenSummaryCards from './components/TokenSummaryCards';
import TokenByModelCard from './components/TokenByModelCard';
import TokenCompositionCard from './components/TokenCompositionCard';
import { formatCompact } from '../../lib/format';

export interface TokensPageViewProps {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows: readonly WindowOption[];
  summary: TokenUsageSummary;
  isLoading: boolean;
  error: Error | null;
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  isPolling: boolean;
}

// Short, human display name for a model id, e.g. "claude-sonnet-4" → "Sonnet 4".
const shortModelName = (model: string): string => {
  const parts = model.replace(/^claude-/, '').split('-');
  if (parts.length === 0 || parts[0] === '') {
    return model;
  }
  const [family, ...rest] = parts;
  return [family.charAt(0).toUpperCase() + family.slice(1), ...rest].join(' ');
};

const ratioBandColor = (
  ratio: number,
  palette: { healthy: string; mixed: string; poor: string; muted: string },
): string => {
  if (ratio >= 0.7) {
    return palette.healthy;
  }
  if (ratio >= 0.4) {
    return palette.mixed;
  }
  if (ratio > 0) {
    return palette.poor;
  }
  return palette.muted;
};

const ratioBandLabel = (ratio: number, denominatorEmpty: boolean): string => {
  if (denominatorEmpty) {
    return 'No cache activity in this window';
  }
  if (ratio >= 0.7) {
    return 'Healthy — most prompts are reusing cache';
  }
  if (ratio >= 0.4) {
    return 'Mixed — some prompts paying full freight';
  }
  return 'Poor — context placement is wasting per-turn spend';
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
  isLoading,
  error,
  onReload,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
}: TokensPageViewProps) => {
  const theme = useTheme();
  const denominator = summary.cacheCreationTokens + summary.cacheReadTokens;
  const denominatorEmpty = denominator === 0;
  const healthy = summary.cacheReadRatio >= 0.7;

  const ratioColor = ratioBandColor(summary.cacheReadRatio, {
    healthy: theme.palette.success.main,
    mixed: theme.palette.warning.main,
    poor: theme.palette.error.main,
    muted: theme.palette.text.disabled,
  });

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
        ratioLabel={ratioBandLabel(summary.cacheReadRatio, denominatorEmpty)}
        healthy={healthy}
        ratioEmpty={denominatorEmpty}
        savedTokens={formatCompact(summary.cacheReadTokens)}
      />

      {/* Per-model token sums — full width, below the composition card */}
      <TokenByModelCard rows={modelRows} />
    </PageLayout>
  );
};

export default TokensPageView;
