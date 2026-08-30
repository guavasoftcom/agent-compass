import { Box, Typography, useTheme } from '@mui/material';
import DeltaBadge, { type DeltaBadgeDirection, type DeltaBadgeState } from '../../../../components/DeltaBadge';
import LineSparkline from '../../../../components/LineSparkline';
import { fontFamilies } from '../../../../theme/typography';

export interface MetricRowSide {
  value: string;
  sub: string;
  series: number[];
}

export interface MetricRowProps {
  label: string;
  before: MetricRowSide;
  after: MetricRowSide;
  state: DeltaBadgeState;
  direction: DeltaBadgeDirection;
  deltaLabel: string;
  /** Share (0-100) of the center ratio bar the "before" segment should occupy. */
  beforeSharePct: number;
}

const SPARKLINE_WIDTH = 110;

/**
 * One before/spine/after row of the Trend Report diff card — a 3-column grid mirroring the
 * period bar above it. Left cell: big before value + sub-label + muted sparkline. Center cell:
 * metric name, a `DeltaBadge`, and a two-tone ratio bar visualizing before-vs-after magnitude.
 * Right cell: the after value (colored success/error when the move is good/bad, ink otherwise)
 * + sub-label + sparkline tinted to match.
 *
 * Deliberately paints no background of its own — the before/after column washes are a single
 * absolutely-positioned overlay behind every row on `TrendReportPageView`, not per-row `bgcolor`
 * (see that file's comment), so this component only supplies text alignment and padding.
 */
const MetricRow = ({ label, before, after, state, direction, deltaLabel, beforeSharePct }: MetricRowProps) => {
  const theme = useTheme();

  const afterColor =
    state === 'good' ? theme.palette.success.main : state === 'bad' ? theme.palette.error.main : theme.palette.text.primary;
  const sparklineColor = state === 'good' ? theme.palette.success.main : state === 'bad' ? theme.palette.error.main : theme.palette.text.secondary;

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: '1fr 160px 1fr',
        borderBottom: `1px solid ${theme.palette.divider}`,
      }}
    >
      <Box sx={{ px: 4, py: 2.5, textAlign: 'right', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', justifyContent: 'center' }}>
        <Typography sx={{ fontFamily: fontFamilies.display, fontWeight: 800, fontSize: 28, letterSpacing: '-0.8px', color: 'text.primary' }}>
          {before.value}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5 }}>
          {before.sub}
        </Typography>
        <Box sx={{ mt: 0.75, width: SPARKLINE_WIDTH }}>
          <LineSparkline values={before.series} height={22} color={theme.palette.text.secondary} />
        </Box>
      </Box>

      <Box
        sx={{
          borderLeft: `1px solid ${theme.palette.divider}`,
          borderRight: `1px solid ${theme.palette.divider}`,
          px: 1.75,
          py: 2,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 0.75,
          textAlign: 'center',
        }}
      >
        <Typography sx={{ fontSize: 11, fontWeight: 600, letterSpacing: 0.4, textTransform: 'uppercase', color: 'text.secondary', lineHeight: 1.3 }}>
          {label}
        </Typography>
        <DeltaBadge state={state} direction={direction} value={deltaLabel} />
        <Box sx={{ mt: 0.5, width: SPARKLINE_WIDTH, height: 5, borderRadius: 3, backgroundColor: theme.custom.progressTrack, overflow: 'hidden', display: 'flex' }}>
          <Box sx={{ width: `${beforeSharePct}%`, backgroundColor: theme.palette.text.secondary, opacity: 0.4 }} />
          <Box sx={{ width: `${100 - beforeSharePct}%`, backgroundColor: afterColor }} />
        </Box>
      </Box>

      <Box sx={{ px: 4, py: 2.5, textAlign: 'left', display: 'flex', flexDirection: 'column', alignItems: 'flex-start', justifyContent: 'center' }}>
        <Typography sx={{ fontFamily: fontFamilies.display, fontWeight: 800, fontSize: 28, letterSpacing: '-0.8px', color: afterColor }}>
          {after.value}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5 }}>
          {after.sub}
        </Typography>
        <Box sx={{ mt: 0.75, width: SPARKLINE_WIDTH }}>
          <LineSparkline values={after.series} height={22} color={sparklineColor} />
        </Box>
      </Box>
    </Box>
  );
};

export default MetricRow;
