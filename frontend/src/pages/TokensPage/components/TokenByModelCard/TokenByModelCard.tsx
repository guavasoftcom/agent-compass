import { Box, Paper } from '@mui/material';
import BreakdownList from '../../../../components/BreakdownList/BreakdownList';
import type { BreakdownRow } from '../../../../components/BreakdownList/BreakdownList';
import { fontFamilies } from '../../../../theme/typography';

/**
 * One per-model token row for the window. Pre-formatted display strings,
 * sourced from the live `summary.byModel` (see BACKEND.md) — never a fixture.
 */
export interface TokenByModelRow {
  model: string;
  /** Token sum over the window, pre-formatted (e.g. "7.8M"). */
  tokens: string;
  /** Share of total tokens, 0–100. */
  share: number;
  /** Index into the dashboard palette (theme.colorForIndex). */
  colorIndex: number;
}

export interface TokenByModelCardProps {
  /** Live per-model token sums for the selected window (`summary.byModel`). */
  rows: TokenByModelRow[];
  note?: string;
}

/**
 * "Token sum by model" — a full-width card with one column per model
 * (name + colour dot, big token total, share bar). Sits below the composition
 * card so the time-series chart keeps the full content width to itself.
 */
const TokenByModelCard = ({ rows, note }: TokenByModelCardProps) => {
  const breakdownRows: BreakdownRow[] = rows.map((row) => ({
    label: row.model,
    value: row.tokens,
    percentage: row.share,
    colorIndex: row.colorIndex,
  }));

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Box sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16 }}>Token sum by model</Box>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' },
          gap: { xs: 2.5, sm: 3.75 },
          mt: 1.75,
        }}
      >
        <BreakdownList
          rows={breakdownRows}
          layout="column-card"
          showColorDot={true}
          largeValue={true}
          percentageDecimalPlaces={0}
        />
      </Box>

      <Box sx={{ mt: 2.75, fontSize: 12, color: 'text.secondary', lineHeight: 1.5 }}>
        {note ?? 'Token totals by model over the selected window.'}
      </Box>
    </Paper>
  );
};

export default TokenByModelCard;
