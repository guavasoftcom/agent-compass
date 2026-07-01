import type { ReactNode } from 'react';
import { alpha, Box, Paper, Stack, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import { neutralColors } from '../../theme/colors';
import { fontFamilies } from '../../theme/typography';

export interface DonutSlice {
  label: string;
  value: number;
  color: string;
  /** Render the legend label dimmed + italic (e.g. an "unknown" bucket). */
  muted?: boolean;
}

export interface DonutCardProps {
  title: string;
  slices: DonutSlice[];
  /** Big number shown in the ring center (e.g. a total or a percentage). */
  centerValue: ReactNode;
  /** Small uppercase caption under the center value (e.g. "calls"). */
  centerLabel: string;
  /** Show a 1-based rank number before each legend row. */
  ranked?: boolean;
  hasData?: boolean;
  isLoading?: boolean;
  emptyLabel?: string;
}

const SIZE = 200;
const STROKE = 20;
const RADIUS = 80; // leaves room for the round caps inside the viewBox
const CENTER = SIZE / 2;
const CIRC = 2 * Math.PI * RADIUS;
const GAP_PX = 6; // gap between segments

/**
 * Aurora donut: a stroke-based ring (rounded caps + small gaps between segments),
 * a total in the center, and a legend below — matching the design mockup. Shares
 * the dashboard palette via each slice's `color`. Not built on @mui/x-charts so the
 * ring style matches the rest of the Aurora UI exactly.
 */
const DonutCard = ({
  title,
  slices,
  centerValue,
  centerLabel,
  ranked = false,
  hasData = true,
  isLoading = false,
  emptyLabel = 'No data in this window.',
}: DonutCardProps) => {
  const theme = useTheme();
  const sum = slices.reduce((acc, slice) => acc + slice.value, 0);
  const trackColor =
    theme.palette.mode === 'dark'
      ? alpha(neutralColors.white, 0.07)
      : alpha(neutralColors.inkLight, 0.07);

  // Build one arc per slice, positioned by rotating from -90° (12 o'clock).
  const positiveSlices = slices.filter((slice) => slice.value > 0);
  const arcs = positiveSlices.map((slice, index) => {
    const precedingValue = positiveSlices
      .slice(0, index)
      .reduce((acc, preceding) => acc + preceding.value, 0);
    const fraction = slice.value / sum;
    const startAngle = -90 + (precedingValue / sum) * 360;
    const rawLen = fraction * CIRC;
    // Subtract a gap so rounded caps don't overlap; keep a minimum sliver.
    const dash = Math.max(rawLen - (slices.length > 1 ? GAP_PX : 0), 2);
    return { color: slice.color, dash, startAngle };
  });

  return (
    <Paper variant="outlined" sx={{ p: 2, height: '100%' }}>
      <Typography variant="subtitle1" gutterBottom>
        {title}
      </Typography>

      {(!hasData || sum === 0) && !isLoading ? (
        <Typography color="text.secondary">{emptyLabel}</Typography>
      ) : (
        <>
          <Box
            sx={{
              position: 'relative',
              width: SIZE,
              height: SIZE,
              mx: 'auto',
              my: 1,
            }}
          >
            <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`}>
              <circle
                cx={CENTER}
                cy={CENTER}
                r={RADIUS}
                fill="none"
                stroke={trackColor}
                strokeWidth={STROKE}
              />
              {arcs.map((arc, index) => (
                <circle
                  key={index}
                  cx={CENTER}
                  cy={CENTER}
                  r={RADIUS}
                  fill="none"
                  stroke={arc.color}
                  strokeWidth={STROKE}
                  strokeLinecap="round"
                  strokeDasharray={`${arc.dash} ${CIRC - arc.dash}`}
                  transform={`rotate(${arc.startAngle} ${CENTER} ${CENTER})`}
                />
              ))}
            </svg>
            <Box
              sx={{
                position: 'absolute',
                inset: 0,
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                pointerEvents: 'none',
              }}
            >
              <Typography
                sx={{
                  fontFamily: fontFamilies.display,
                  fontWeight: 800,
                  fontSize: 36,
                  lineHeight: 1,
                  letterSpacing: -1,
                }}
              >
                {centerValue}
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ letterSpacing: 1, textTransform: 'uppercase', mt: 0.5 }}
              >
                {centerLabel}
              </Typography>
            </Box>
          </Box>

          <Stack sx={{ mt: 1.5 }}>
            {slices.map((slice, index) => {
              const pct = sum > 0 ? (slice.value / sum) * 100 : 0;
              return (
                <Stack
                  key={slice.label}
                  direction="row"
                  spacing={1.25}
                  sx={{
                    alignItems: 'center',
                    py: 0.9,
                    borderBottom: index < slices.length - 1 ? '1px solid' : 'none',
                    borderColor: 'divider',
                  }}
                >
                  {ranked && (
                    <Typography
                      variant="body2"
                      color="text.disabled"
                      sx={{ width: 16, flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}
                    >
                      {index + 1}
                    </Typography>
                  )}
                  <Box
                    sx={{
                      width: 10,
                      height: 10,
                      borderRadius: '3px',
                      flexShrink: 0,
                      bgcolor: slice.color,
                    }}
                  />
                  <Typography
                    variant="body2"
                    noWrap
                    sx={{
                      flex: 1,
                      minWidth: 0,
                      fontWeight: 600,
                      ...(slice.muted && { fontStyle: 'italic', color: 'text.disabled' }),
                    }}
                  >
                    {slice.label}
                  </Typography>
                  <Typography
                    variant="body2"
                    color="text.secondary"
                    sx={{ flexShrink: 0, whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}
                  >
                    <Box component="span" sx={{ color: 'text.primary', fontWeight: 700 }}>
                      {slice.value.toLocaleString()}
                    </Box>{' '}
                    · {pct.toFixed(1)}%
                  </Typography>
                </Stack>
              );
            })}
          </Stack>
        </>
      )}
    </Paper>
  );
};

export default DonutCard;
