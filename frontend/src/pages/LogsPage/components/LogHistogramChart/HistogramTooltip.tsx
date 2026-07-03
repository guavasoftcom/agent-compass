import { Box, Typography, useTheme } from '@mui/material';
import ZoomInRoundedIcon from '@mui/icons-material/ZoomInRounded';
import { SEVERITIES, type HistogramBucket } from '../../logsApi';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';
import { severityColor } from '../severity';
import { MS_PER_DAY } from '../../../../lib/constants';
import { bucketTotal } from './bucketTotal';

interface HistogramTooltipProps {
  bucket: HistogramBucket;
  /** Viewport coordinates of the hovered bar's top-center. */
  position: { x: number; y: number };
  bucketWidthMs: number;
  /** Show the "click to zoom" affordance (the bar is clickable). */
  showZoomHint: boolean;
}

// Fixed-position hover card for a single histogram bucket: time range header,
// per-severity counts, total, and an optional zoom hint.
// Rendered by LogHistogramChart when a bar is hovered.
const HistogramTooltip = ({
  bucket,
  position,
  bucketWidthMs,
  showZoomHint,
}: HistogramTooltipProps) => {
  const theme = useTheme();
  const total = bucketTotal(bucket);

  return (
    <Box
      sx={{
        position: 'fixed',
        zIndex: (t) => t.zIndex.tooltip,
        left: position.x,
        top: position.y - 12,
        transform: 'translate(-50%, -100%)',
        pointerEvents: 'none',
        bgcolor: 'background.paper',
        border: 1,
        borderColor: 'divider',
        borderRadius: radii.lg,
        boxShadow: 6,
        px: 1.5,
        py: 1.25,
        minWidth: 150,
      }}
    >
      <Typography
        sx={{
          fontFamily: fontFamilies.display,
          fontWeight: 700,
          fontSize: 12,
          mb: 0.75,
        }}
      >
        {bucketWidthMs >= MS_PER_DAY
          ? new Date(bucket.t0).toLocaleDateString('en-US', {
              month: 'short',
              day: 'numeric',
            })
          : `${new Date(bucket.t0).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false })} – ${new Date(bucket.t1).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false })}`}
      </Typography>
      {SEVERITIES.filter((severity) => bucket[severity] > 0).map((severity) => (
        <Box
          key={severity}
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 2.25,
            py: 0.25,
          }}
        >
          <Box
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 0.9,
              color: 'text.secondary',
              fontSize: 12.5,
            }}
          >
            <Box
              sx={{
                width: 9,
                height: 9,
                borderRadius: radii.xs,
                bgcolor: severityColor(theme, severity),
              }}
            />
            {severity}
          </Box>
          <Box
            component="span"
            sx={{
              fontWeight: 600,
              fontVariantNumeric: 'tabular-nums',
              fontSize: 12.5,
            }}
          >
            {bucket[severity]}
          </Box>
        </Box>
      ))}
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          gap: 2.25,
          mt: 0.6,
          pt: 0.75,
          borderTop: 1,
          borderColor: 'divider',
          fontSize: 12.5,
        }}
      >
        <Box component="span" sx={{ color: 'text.secondary' }}>
          Total
        </Box>
        <Box
          component="span"
          sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}
        >
          {total}
        </Box>
      </Box>
      {showZoomHint && total > 0 ? (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 0.6,
            mt: 0.85,
            pt: 0.85,
            borderTop: 1,
            borderColor: 'divider',
            fontSize: 11,
            fontWeight: 600,
            color: 'primary.main',
          }}
        >
          <ZoomInRoundedIcon sx={{ fontSize: 13 }} />
          Click to zoom in
        </Box>
      ) : null}
    </Box>
  );
};

export default HistogramTooltip;
