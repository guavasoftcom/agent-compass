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
