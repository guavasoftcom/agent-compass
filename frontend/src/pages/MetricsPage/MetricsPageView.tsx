import { useMemo, useState } from 'react';
import { Box, Paper, Stack, Typography } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import AreaTrendChart from '../../components/AreaTrendChart';
import SegmentedToggle from '../../components/SegmentedToggle';
import { colorForIndex } from '../../theme';
import type { WindowOption } from '../../constants';
import type { WindowSelection } from '../../api';
import {
  METRICS,
  MetricKpiStrip,
  MetricHeader,
  MetricBreakdown,
  type MetricSeries,
} from './components';

export interface MetricsPageViewProps {
  /** Window-selection chrome (same contract as every other page). */
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows: readonly WindowOption[];
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  isPolling: boolean;
  /** The claude_code.* metrics. Optional so the page renders on sample data. */
  metrics?: MetricSeries[];
  isLoading?: boolean;
  error?: Error | null;
}

const SPLIT_NONE = 'None';

const COMPACT = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });
const formatCompact = (value: number): string => COMPACT.format(value);

/**
 * Metrics page — a simplified master-detail over the six claude_code.* counters.
 *
 * Left: the metric list (sparkline + headline value). Right: the selected
 * metric's header stats, a trend chart, and a breakdown card. A single
 * lightweight "Split by" control (None / Model / Type / …) appears only for
 * metrics that have an attribute breakdown — splitting stacks the chart and
 * fills the breakdown card. No facet filters, group-by/agg, heatmap, or
 * exemplar drawer (that distribution + drill-to-trace flow lives in Traces).
 *
 * Presentational: the container supplies window chrome + live data; defaults to
 * sample data in components/metricsSampleData.ts. See BACKEND.md.
 */
const MetricsPageView = ({
  selection,
  onSelectionChange,
  windows,
  onReload,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
  metrics = METRICS,
  isLoading = false,
  error = null,
}: MetricsPageViewProps) => {
  const [selectedId, setSelectedId] = useState(metrics[0]?.id ?? '');
  const [split, setSplit] = useState<string>(SPLIT_NONE);

  const selected = useMemo(
    () => metrics.find((m) => m.id === selectedId) ?? metrics[0],
    [metrics, selectedId],
  );

  const selectMetric = (id: string) => {
    setSelectedId(id);
    setSplit(SPLIT_NONE);
  };

  // x-axis: one bucket per trend point across the window, ending now.
  const axisDates = useMemo(() => {
    const n = selected?.trend.length ?? 0;
    // x-axis is anchored to current wall-clock time.
    // eslint-disable-next-line react-hooks/purity
    const end = Date.now();
    const stepMs = (24 * 60 * 60 * 1000) / Math.max(1, n - 1);
    return Array.from({ length: n }, (_, i) => new Date(end - (n - 1 - i) * stepMs));
  }, [selected]);

  // Chart series: a single total line, or the split proportions stacked.
  const series = useMemo(() => {
    if (!selected) {
      return [];
    }
    const rows = selected.splits[split];
    if (split === SPLIT_NONE || !rows) {
      return [{ label: 'total', data: selected.trend, color: colorForIndex(0) }];
    }
    return rows.map((row) => ({
      label: row.label,
      data: selected.trend.map((v) => (v * row.pct) / 100),
      color: colorForIndex(row.colorIndex),
    }));
  }, [selected, split]);

  const splitKeys = selected ? [SPLIT_NONE, ...Object.keys(selected.splits)] : [SPLIT_NONE];
  const hasSplits = splitKeys.length > 1;

  return (
    <PageLayout
      eyebrow="Observability"
      title="Metrics"
      subtitle={
        'The six counters Claude Code emits in the claude_code.* namespace. Pick a metric to see '
        + 'its trend over the selected window; split token & cost by model when you need the breakdown.'
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
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.25 }}>
        <MetricKpiStrip metrics={metrics} selectedId={selected?.id ?? ''} onSelect={selectMetric} />

        {selected && (
          <Stack sx={{ gap: 2.25, minWidth: 0 }}>
            <MetricHeader metric={selected} />

            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr', md: '1fr 300px' },
                gap: 2.25,
                alignItems: 'stretch',
              }}
            >
              <Paper variant="outlined" sx={{ p: '20px 24px', minWidth: 0 }}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 1.5, mb: 1.25 }}>
                  <Typography sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 600, fontSize: 16 }}>
                    {selected.name.replace('claude_code.', '')} over time
                  </Typography>
                  {hasSplits && (
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.125 }}>
                      <Box sx={{ fontSize: 11, fontWeight: 700, letterSpacing: 0.6, textTransform: 'uppercase', color: 'text.disabled', fontFamily: "'Sora', sans-serif" }}>
                        Split by
                      </Box>
                      <SegmentedToggle
                        options={splitKeys.map((key) => ({ value: key, label: key }))}
                        value={split}
                        onChange={setSplit}
                      />
                    </Box>
                  )}
                </Box>

                <Box sx={{ display: 'flex', gap: 1.75, flexWrap: 'wrap', mb: 0.5 }}>
                  {series.map((s) => (
                    <Box key={s.label} sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
                      <Box sx={{ width: 9, height: 9, borderRadius: '3px', bgcolor: s.color }} />
                      <Typography variant="caption" color="text.secondary">
                        {split === SPLIT_NONE ? `total · ${selected.unit.replace(/[{}]/g, '')}` : s.label}
                      </Typography>
                    </Box>
                  ))}
                </Box>

                {isLoading ? (
                  <Box sx={{ height: 290, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                    <Typography color="text.secondary">Loading…</Typography>
                  </Box>
                ) : (
                  <AreaTrendChart
                    axisDates={axisDates}
                    series={series}
                    yLabel={selected.unit.replace(/[{}]/g, '')}
                    formatY={formatCompact}
                    height={290}
                  />
                )}
              </Paper>

              <MetricBreakdown metric={selected} split={split} />
            </Box>
          </Stack>
        )}
      </Box>
    </PageLayout>
  );
};

export default MetricsPageView;
