import { alpha, Box, LinearProgress, Paper, Stack, Typography, useTheme } from '@mui/material';
import CheckCircleOutlinedIcon from '@mui/icons-material/CheckCircleOutlined';
import { neutralColors } from '../../../../theme/colors';
import { colorForIndex } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';

export interface CompositionSlice {
  label: string;
  /** Sub-label under the name, e.g. "reused from cache". */
  description: string;
  value: number;
  color: string;
}

export interface TokenCompositionCardProps {
  slices: CompositionSlice[];
  centerValue: string;
  centerLabel: string;
  /** Cache-read ratio, 0–1. */
  cacheReadRatio: number;
  ratioColor: string;
  ratioLabel: string;
  healthy: boolean;
  ratioEmpty: boolean;
  /** Cache-read tokens, pre-formatted (e.g. "8.9M") for the savings note. */
  savedTokens: string;
}

const SIZE = 200;
const STROKE = 20;
const RADIUS = 80;
const CENTER = SIZE / 2;
const CIRC = 2 * Math.PI * RADIUS;
const GAP_PX = 6;

/**
 * Merged "Token composition" card: the token-mix donut (with descriptive legend)
 * beside the cache-read-ratio health gauge — the two share one card so the
 * composition story reads in a single place.
 */
const TokenCompositionCard = ({
  slices,
  centerValue,
  centerLabel,
  cacheReadRatio,
  ratioColor,
  ratioLabel,
  healthy,
  ratioEmpty,
  savedTokens,
}: TokenCompositionCardProps) => {
  const theme = useTheme();
  const sum = slices.reduce((acc, s) => acc + s.value, 0);
  const trackColor =
    theme.palette.mode === 'dark'
      ? alpha(neutralColors.white, 0.07)
      : alpha(neutralColors.inkLight, 0.07);

  const positiveSlices = slices.filter((s) => s.value > 0);
  const arcs = positiveSlices.map((s, index) => {
    const precedingValue = positiveSlices
      .slice(0, index)
      .reduce((acc, preceding) => acc + preceding.value, 0);
    const fraction = s.value / sum;
    const startAngle = -90 + (precedingValue / sum) * 360;
    const rawLen = fraction * CIRC;
    const dash = Math.max(rawLen - (slices.length > 1 ? GAP_PX : 0), 2);
    return { color: s.color, dash, startAngle };
  });

  const formatPercent = (ratio: number): string => `${(ratio * 100).toFixed(1)}%`;

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Box sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16 }}>Token composition</Box>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '1fr 1px 1fr' },
          columnGap: 3.75,
          rowGap: 3,
          mt: 1.75,
          alignItems: 'stretch',
        }}
      >
        {/* Left — donut + descriptive legend */}
        <Box sx={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', minWidth: 0 }}>
          <Box sx={{ position: 'relative', width: SIZE, height: SIZE, mx: 'auto', my: 1 }}>
            <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`}>
              <circle cx={CENTER} cy={CENTER} r={RADIUS} fill="none" stroke={trackColor} strokeWidth={STROKE} />
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
              <Typography sx={{ fontFamily: fontFamilies.display, fontWeight: 800, fontSize: 36, lineHeight: 1, letterSpacing: -1 }}>
                {centerValue}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ letterSpacing: 1, textTransform: 'uppercase', mt: 0.5 }}>
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
                    py: 1.1,
                    borderBottom: index < slices.length - 1 ? '1px solid' : 'none',
                    borderColor: 'divider',
                  }}
                >
                  <Typography variant="body2" color="text.disabled" sx={{ width: 16, flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}>
                    {index + 1}
                  </Typography>
                  <Box sx={{ width: 10, height: 10, borderRadius: '3px', flexShrink: 0, bgcolor: slice.color }} />
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>
                      {slice.label}
                    </Typography>
                    <Typography sx={{ fontSize: 11.5, color: 'text.disabled', lineHeight: 1.2 }}>
                      {slice.description}
                    </Typography>
                  </Box>
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
        </Box>

        {/* Divider */}
        <Box sx={{ backgroundColor: 'divider', display: { xs: 'none', md: 'block' } }} />

        {/* Right — cache-read-ratio health */}
        <Box sx={{ display: 'flex', flexDirection: 'column', justifyContent: 'flex-start', minWidth: 0 }}>
          <Box
            sx={{
              fontSize: 11,
              fontWeight: 600,
              letterSpacing: 0.5,
              textTransform: 'uppercase',
              color: 'text.disabled',
            }}
          >
            Cache read ratio
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5, mt: 1, flexWrap: 'wrap' }}>
            <Typography
              sx={(t) => ({
                fontFamily: fontFamilies.display,
                fontWeight: 800,
                fontSize: 54,
                lineHeight: 1,
                letterSpacing: -1.5,
                backgroundImage: `linear-gradient(120deg, ${t.palette.text.primary} 20%, ${t.palette.primary.main})`,
                WebkitBackgroundClip: 'text',
                backgroundClip: 'text',
                color: 'transparent',
              })}
            >
              {ratioEmpty ? '—' : formatPercent(cacheReadRatio)}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              cacheRead / (cacheRead + cacheCreation)
            </Typography>
          </Box>

          <LinearProgress
            variant="determinate"
            value={Math.min(100, cacheReadRatio * 100)}
            sx={(t) => ({
              height: 12,
              borderRadius: 7,
              mt: 4,
              bgcolor: t.custom?.progressTrack ?? t.palette.action.hover,
              '& .MuiLinearProgress-bar': {
                borderRadius: 7,
                background: `linear-gradient(90deg, ${colorForIndex(0)}, ${colorForIndex(1)})`,
              },
            })}
          />
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 0.75 }}>
            {['0%', '40% (mixed)', '70% (healthy)', '100%'].map((tick) => (
              <Typography key={tick} variant="caption" color="text.disabled">
                {tick}
              </Typography>
            ))}
          </Box>

          <Box
            sx={{
              mt: 3.25,
              px: 1.75,
              py: 1.25,
              borderRadius: 2.5,
              display: 'flex',
              alignItems: 'center',
              gap: 1.25,
              fontSize: 14,
              fontWeight: 500,
              color: ratioColor,
              bgcolor: `color-mix(in srgb, ${ratioColor} 14%, transparent)`,
            }}
          >
            {healthy && <CheckCircleOutlinedIcon sx={{ fontSize: 18 }} />}
            {ratioLabel}
          </Box>

          <Box sx={{ mt: 'auto', pt: 2.75, fontSize: 12.5, color: 'text.secondary', lineHeight: 1.5 }}>
            Reusing cached context saves rebuilding{' '}
            <Box component="b" sx={{ color: 'text.primary', fontWeight: 700 }}>{savedTokens}</Box>{' '}
            tokens of prompt every window.
          </Box>
        </Box>
      </Box>
    </Paper>
  );
};

export default TokenCompositionCard;
