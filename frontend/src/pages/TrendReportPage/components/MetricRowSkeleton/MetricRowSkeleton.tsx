import { Box, Skeleton, useTheme } from '@mui/material';

const SPARKLINE_WIDTH = 110;

/**
 * Loading placeholder for one `MetricRow` — same 3-column grid and cell dimensions (value,
 * sub-label, sparkline on each side; label + badge + ratio bar in the center) so the diff card
 * doesn't reflow once the real rows swap in. Rendered by `TrendReportPageView` for every metric
 * key in `TREND_SECTIONS` while `isLoading && !report`, alongside the real (data-independent)
 * `SectionHeader`s.
 */
const MetricRowSkeleton = () => {
  const theme = useTheme();

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: '1fr 160px 1fr',
        borderBottom: `1px solid ${theme.palette.divider}`,
      }}
    >
      <Box sx={{ px: 4, py: 2.5, display: 'flex', flexDirection: 'column', alignItems: 'flex-end', justifyContent: 'center' }}>
        <Skeleton variant="text" width={70} height={34} />
        <Skeleton variant="text" width={90} height={16} sx={{ mt: 0.5 }} />
        <Skeleton variant="rounded" width={SPARKLINE_WIDTH} height={22} sx={{ mt: 0.75 }} />
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
        }}
      >
        <Skeleton variant="text" width={80} height={16} />
        <Skeleton variant="rounded" width={56} height={22} sx={{ borderRadius: 999 }} />
        <Skeleton variant="rounded" width={SPARKLINE_WIDTH} height={5} sx={{ mt: 0.5, borderRadius: 3 }} />
      </Box>

      <Box sx={{ px: 4, py: 2.5, display: 'flex', flexDirection: 'column', alignItems: 'flex-start', justifyContent: 'center' }}>
        <Skeleton variant="text" width={70} height={34} />
        <Skeleton variant="text" width={90} height={16} sx={{ mt: 0.5 }} />
        <Skeleton variant="rounded" width={SPARKLINE_WIDTH} height={22} sx={{ mt: 0.75 }} />
      </Box>
    </Box>
  );
};

export default MetricRowSkeleton;
