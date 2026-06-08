import { Box, Grid } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import DonutCard from '../../components/DonutCard';
import { colorForIndex } from '../../theme';

import type {
  ToolCallRow,
  ToolCallTimeseries,
  ToolLatencyRow,
} from '../../api';

import StatsRow from './components/StatsRow';
import CallsOverTimeCard from './components/CallsOverTimeCard';
import ToolLatencyCard from './components/ToolLatencyCard';

export type ToolCallRowWithShare = ToolCallRow & { share: number };

export interface ToolCallsPageViewProps {
  rowsWithShare: ToolCallRowWithShare[];
  total: number;
  hasData: boolean;
  isLoading: boolean;
  error: Error | null;
  timeseries: ToolCallTimeseries | null;
  isTimeseriesLoading: boolean;
  latencyRows: ToolLatencyRow[];
  isLatencyLoading: boolean;
}

const ToolCallsPageView = ({
  rowsWithShare,
  total,
  hasData,
  isLoading,
  error,
  timeseries,
  isTimeseriesLoading,
  latencyRows,
  isLatencyLoading,
}: ToolCallsPageViewProps) => {
  return (
    <PageLayout
      subtitle="Aggregate tool invocations, mix, throughput, and latency."
      error={error}
    >
      {/* Stat cards span the full width */}
      <StatsRow
        rowsWithShare={rowsWithShare}
        total={total}
        latencyRows={latencyRows}
        timeseries={timeseries}
      />

      {/* Latency per tool beside the Call mix donut — balanced heights.
          (Tool ranking by call count was redundant with the donut, so removed.) */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '1.6fr 1fr' },
          gap: 2,
          alignItems: 'stretch',
        }}
      >
        <ToolLatencyCard
          latencyRows={latencyRows}
          isLoading={isLatencyLoading}
        />

        <DonutCard
          title="Call mix"
          slices={rowsWithShare.map((row, index) => ({
            label: row.tool,
            value: row.calls,
            color: colorForIndex(index),
          }))}
          ranked
          centerValue={total.toLocaleString()}
          centerLabel="calls"
          hasData={hasData}
          isLoading={isLoading}
        />
      </Box>

      <Grid container spacing={2}>
        <Grid size={{ xs: 12 }}>
          <CallsOverTimeCard
            timeseries={timeseries}
            isLoading={isTimeseriesLoading}
          />
        </Grid>
      </Grid>
    </PageLayout>
  );
};

export default ToolCallsPageView;
