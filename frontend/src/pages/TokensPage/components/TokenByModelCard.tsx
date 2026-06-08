import { Box, LinearProgress, Paper, useTheme } from '@mui/material';
import { colorForIndex } from '../../../theme';

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
  const theme = useTheme();
  const trackColor = theme.custom?.progressTrack ?? theme.palette.action.hover;
  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Box sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 600, fontSize: 16 }}>Token sum by model</Box>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' },
          gap: { xs: 2.5, sm: 3.75 },
          mt: 1.75,
        }}
      >
        {rows.map((row) => {
          const color = colorForIndex(row.colorIndex);
          return (
            <Box key={row.model} sx={{ minWidth: 0 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, fontWeight: 600, fontSize: 14, mb: 1.5, minWidth: 0 }}>
                <Box sx={{ width: 10, height: 10, borderRadius: '3px', bgcolor: color, flexShrink: 0 }} />
                <Box sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{row.model}</Box>
              </Box>
              <Box sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 800, fontSize: 28, letterSpacing: '-0.6px', color: 'text.primary' }}>
                {row.tokens}
                <Box component="span" sx={{ fontSize: 14, color: 'text.secondary', fontWeight: 600, ml: 0.625 }}>
                  · {row.share}%
                </Box>
              </Box>
              <LinearProgress
                variant="determinate"
                value={row.share}
                sx={{
                  mt: 1.625,
                  height: 8,
                  borderRadius: 5,
                  bgcolor: trackColor,
                  '& .MuiLinearProgress-bar': { backgroundColor: color, borderRadius: 5 },
                }}
              />
            </Box>
          );
        })}
      </Box>

      <Box sx={{ mt: 2.75, fontSize: 12, color: 'text.secondary', lineHeight: 1.5 }}>
        {note ?? 'Token totals by model over the selected window.'}
      </Box>
    </Paper>
  );
};

export default TokenByModelCard;
