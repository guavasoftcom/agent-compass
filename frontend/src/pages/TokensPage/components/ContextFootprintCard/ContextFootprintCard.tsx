import { Box, Chip, Paper, Tooltip, Typography } from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import BreakdownList, { type BreakdownRow } from '../../../../components/BreakdownList';
import type { ToolContextFootprintRow } from '../../../../api';
import { formatBytes, formatCompact } from '../../../../lib/format';
import { colorForIndex } from '../../../../theme/theme';

/** Tools shown individually before the rest collapse into one "Other" row. */
export const CONTEXT_FOOTPRINT_TOP_N = 8;

const OTHER_BUCKET_LABEL = 'Other';

export interface ContextFootprintCardProps {
  rows: ToolContextFootprintRow[];
}

/**
 * "What's filling the context window" — tools ranked by the total bytes their
 * results pushed into context.
 *
 * Deliberately kept visually distinct from the exact-token cards above it (its
 * own "estimated" chip, byte-first values) because its token figure is
 * `bytes / 4`, not a billed count. The two must never look like they add up.
 *
 * Rows past the top N collapse into one "Other" bucket rather than being
 * dropped, so the percentages still sum to 100 — MCP tool names
 * (`mcp__server__tool`) can proliferate and would otherwise make the visible
 * shares silently incomplete.
 */
const ContextFootprintCard = ({ rows }: ContextFootprintCardProps) => {
  const totalBytes = rows.reduce((sum, row) => sum + row.totalBytes, 0);

  const visibleRows = rows.slice(0, CONTEXT_FOOTPRINT_TOP_N);
  const remainingRows = rows.slice(CONTEXT_FOOTPRINT_TOP_N);

  const breakdownRows: BreakdownRow[] = visibleRows.map((row, index) => ({
    label: row.tool,
    value: formatBytes(row.totalBytes),
    percentage: totalBytes === 0 ? 0 : (row.totalBytes / totalBytes) * 100,
    colorIndex: index,
    secondaryText:
      `~${formatCompact(row.estimatedTokens)} est. tokens · ${formatCompact(row.calls)} calls`
      + ` · p95 ${formatBytes(row.p95Bytes)}`,
  }));

  if (remainingRows.length > 0) {
    const otherBytes = remainingRows.reduce((sum, row) => sum + row.totalBytes, 0);
    const otherTokens = remainingRows.reduce((sum, row) => sum + row.estimatedTokens, 0);
    const otherCalls = remainingRows.reduce((sum, row) => sum + row.calls, 0);
    breakdownRows.push({
      label: OTHER_BUCKET_LABEL,
      value: formatBytes(otherBytes),
      percentage: totalBytes === 0 ? 0 : (otherBytes / totalBytes) * 100,
      explicitColor: colorForIndex(CONTEXT_FOOTPRINT_TOP_N),
      secondaryText:
        `${remainingRows.length} more tools · ~${formatCompact(otherTokens)} est. tokens`
        + ` · ${formatCompact(otherCalls)} calls`,
    });
  }

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 0.5, flexWrap: 'wrap' }}>
        <Typography variant="subtitle1">What&apos;s filling the context window</Typography>
        <Chip label="estimated" size="small" variant="outlined" sx={{ height: 20, fontSize: 11 }} />
        <Tooltip
          title={
            'Ranked by the total size of the results each tool returned. Tokens are estimated at '
            + '~4 bytes each and are NOT billed spend — do not add them to the exact figures above. '
            + 'The estimate is also a floor: a tool result is re-sent with every later request in '
            + 'the same session, so a large result early in a long session is paid for many times '
            + 'over. Calls that reported no result size are excluded, so these counts run lower '
            + 'than the Tool Calls page.'
          }
          arrow
        >
          <InfoOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }} />
        </Tooltip>
      </Box>
      <Typography variant="body2" color="text.secondary">
        Usually Bash output and whole-file Reads dominate — both are tunable from AGENTS.md with
        output limits and Grep-before-Read guidance.
      </Typography>

      {breakdownRows.length === 0 ? (
        <Box sx={{ py: 4, textAlign: 'center' }}>
          <Typography color="text.secondary">
            No tool results reported a size in this window.
          </Typography>
        </Box>
      ) : (
        // Ranked with a leading number rather than a color dot: the order is what
        // this card is saying, and its colors carry no meaning shared with any
        // other chart on the page.
        <BreakdownList rows={breakdownRows} layout="stacked" showRank />
      )}
    </Paper>
  );
};

export default ContextFootprintCard;
