import { Box, Stack } from '@mui/material';
import { radii } from '../../theme/theme';
import { fontFamilies } from '../../theme/typography';

export interface SegmentedBarSegment {
  label: string;
  value: number;
  color: string;
}

export interface SegmentedBarProps {
  segments: SegmentedBarSegment[];
  formatValue: (value: number) => string;
  /** Background of the bar's track, behind the colored segments. */
  trackColor: string;
  /**
   * When true, a zero-value segment is dropped from the legend as well as the bar
   * (`SessionCostDialog`'s behavior). When false (the default), a zero-value
   * segment still gets a legend row reading its formatted zero — only the bar
   * itself always drops zero segments, since the `MINIMUM_SEGMENT_WIDTH` floor
   * would otherwise paint a visible sliver for a value that isn't really there
   * (`SessionCacheEfficiencyDialog`'s behavior — see TokensPage/CLAUDE.md).
   */
  hideZeroSegmentsInLegend?: boolean;
}

/** Smallest visible slice of the bar, so a tiny segment still reads. */
const MINIMUM_SEGMENT_WIDTH = '2px';

/**
 * A horizontal proportional bar (e.g. cost by category, tokens by kind) with a
 * dot-and-value legend below it. The bar's own segment sum is always the
 * denominator — never a total passed separately — so a contract drift (segments
 * that don't sum to the caller's own total) shows up as a bar that doesn't fill
 * the track instead of as silent overflow. Shared by `SessionCostDialog` (Cost
 * page) and `SessionCacheEfficiencyDialog` (Tokens page).
 */
const SegmentedBar = ({
  segments,
  formatValue,
  trackColor,
  hideZeroSegmentsInLegend = false,
}: SegmentedBarProps) => {
  const segmentTotal = segments.reduce((running, segment) => running + segment.value, 0);
  const nonZeroSegments = segments.filter((segment) => segment.value > 0);
  const legendSegments = hideZeroSegmentsInLegend ? nonZeroSegments : segments;

  return (
    <Box>
      <Box
        sx={{
          display: 'flex',
          height: 8,
          borderRadius: radii.xs,
          overflow: 'hidden',
          bgcolor: trackColor,
        }}
      >
        {segmentTotal > 0
          && nonZeroSegments.map((segment) => (
            <Box
              key={segment.label}
              sx={{
                height: '100%',
                minWidth: MINIMUM_SEGMENT_WIDTH,
                width: `${(segment.value / segmentTotal) * 100}%`,
                bgcolor: segment.color,
              }}
            />
          ))}
      </Box>
      <Stack spacing={0.9} sx={{ mt: 1.25 }}>
        {legendSegments.map((segment) => (
          <Box key={segment.label} sx={{ display: 'flex', alignItems: 'center', gap: 1, fontSize: 12.5 }}>
            <Box
              sx={{
                width: 9,
                height: 9,
                borderRadius: '3px',
                flexShrink: 0,
                bgcolor: segment.color,
              }}
            />
            <Box sx={{ flex: 1, color: 'text.secondary' }}>{segment.label}</Box>
            <Box sx={{ fontFamily: fontFamilies.mono, fontWeight: 600 }}>{formatValue(segment.value)}</Box>
          </Box>
        ))}
      </Stack>
    </Box>
  );
};

export default SegmentedBar;
