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
import type { ToolCallTimeseries, ToolLatencyRow } from '../../../../api';
import type { ToolCallRowWithShare } from '../../ToolCallsPageView';
import StatsRowView from './StatsRowView';

export interface StatsRowProps {
  rowsWithShare: ToolCallRowWithShare[];
  total: number;
  latencyRows: ToolLatencyRow[];
  timeseries: ToolCallTimeseries | null;
}

const StatsRow = ({ rowsWithShare, total, latencyRows, timeseries }: StatsRowProps) => {
  const topRow = rowsWithShare[0] ?? null;
  // The latency endpoint already returns rows sorted by p95 descending, so the
  // slowest tool is simply the first element. No client-side sort needed.
  const slowestRow = latencyRows[0] ?? null;

  // Sparkline = total calls per time bucket (sum across tools).
  const spark = (timeseries?.points ?? []).map((point) =>
    point.counts.reduce((sum, count) => sum + count, 0),
  );

  return (
    <StatsRowView
      total={total}
      distinctCount={rowsWithShare.length}
      topTool={topRow?.tool ?? null}
      topShare={topRow?.share ?? null}
      slowestTool={slowestRow?.tool ?? null}
      slowestP95Ms={slowestRow?.p95Ms ?? null}
      toolNames={rowsWithShare.map((row) => row.tool)}
      spark={spark}
    />
  );
};

export default StatsRow;
