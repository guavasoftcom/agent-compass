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
import { Box, Grid } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import DonutCard from '../../components/DonutCard';
import { colorForIndex } from '../../theme/theme';

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
