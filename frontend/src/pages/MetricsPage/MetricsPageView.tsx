import { useMemo, useState } from 'react';
import { Box, Stack } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import type { WindowOption } from '../../lib/constants';
import type { WindowSelection } from '../../api';
import MetricKpiStrip from './components/MetricKpiStrip';
import MetricHeader from './components/MetricHeader';
import MetricBreakdown from './components/MetricBreakdown';
import MetricTrendCard from './components/MetricTrendCard';
import { METRICS, type MetricSeries } from './components/metricsSampleData';

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

  const splitKeys = selected
    ? [SPLIT_NONE, ...Object.keys(selected.splits)]
    : [SPLIT_NONE];

  return (
    <PageLayout
      eyebrow="Observability"
      title="Metrics"
      subtitle={
        'The six counters Claude Code emits in the claude_code.* namespace. Pick a metric to see ' +
        'its trend over the selected window; split token & cost by model when you need the breakdown.'
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
        <MetricKpiStrip
          metrics={metrics}
          selectedId={selected?.id ?? ''}
          onSelect={selectMetric}
        />
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
              <MetricTrendCard
                metric={selected}
                split={split}
                splitKeys={splitKeys}
                onSplitChange={setSplit}
                isLoading={isLoading}
              />
              <MetricBreakdown metric={selected} split={split} />
            </Box>
          </Stack>
        )}
      </Box>
    </PageLayout>
  );
};

export default MetricsPageView;
