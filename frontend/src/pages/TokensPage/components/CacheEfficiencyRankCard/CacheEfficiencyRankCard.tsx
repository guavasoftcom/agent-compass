import { Box, LinearProgress, Paper, Tooltip, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import type { SessionCacheEfficiencyRow } from '../../../../api';
import {
  cacheEfficiencyBand,
  formatCacheEfficiency,
} from '../../../../lib/cacheEfficiency';
import { formatCompact } from '../../../../lib/format';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';

export interface CacheEfficiencyRankCardProps {
  rows: SessionCacheEfficiencyRow[];
  /** Server-side floor, for the empty-state copy. */
  minimumInputTokensLabel: string;
}

const USD_FORMATTER = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

// Session ids are long uuids; the leading segment is enough to recognize a row
// and match it against the Sessions page, and the full id is one hover away.
const shortSessionId = (sessionId: string): string => sessionId.split('-')[0] ?? sessionId;

/**
 * Sessions with the lowest share of input-side tokens served from cache, worst
 * first. The bar length IS the efficiency ratio (not a share of some total), so
 * a short bar reads directly as "this session rebuilt most of its context".
 *
 * The ranking, the ratio, and the noise floor all come from the server; this
 * component only formats. Bands come from `lib/cacheEfficiency` so a row here
 * and the same session's Cache eff. cell on the Sessions page always agree.
 */
const CacheEfficiencyRankCard = ({
  rows,
  minimumInputTokensLabel,
}: CacheEfficiencyRankCardProps) => {
  const theme = useTheme();
  const trackColor = theme.custom?.progressTrack ?? theme.palette.action.hover;
  const bandColors = {
    strong: theme.palette.success.main,
    mixed: theme.palette.text.primary,
    weak: theme.palette.warning.main,
    unknown: theme.palette.text.disabled,
  };

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 0.5 }}>
        <Typography variant="subtitle1">Worst cache efficiency</Typography>
        <Tooltip
          title={
            'Share of each session\'s input-side tokens (input + cache creation + cache read) '
            + 'that were served from the prompt cache. Cache reads bill at a fraction of fresh '
            + 'input, so a session sitting well below the rest is usually the cheapest thing to '
            + 'fix: look for idle gaps past the cache TTL, hooks injecting changing content into '
            + 'context, or frequent restarts.'
          }
          arrow
        >
          <InfoOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }} />
        </Tooltip>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Sessions below {minimumInputTokensLabel} input-side tokens are excluded — too small to judge.
      </Typography>

      {rows.length === 0 ? (
        <Box sx={{ py: 4, textAlign: 'center' }}>
          <Typography color="text.secondary">
            No session in this window is large enough to rank.
          </Typography>
        </Box>
      ) : (
        rows.map((row) => {
          const band = cacheEfficiencyBand(row.cacheEfficiency);
          const percentage = row.cacheEfficiency * 100;
          return (
            <Box key={row.sessionId} sx={{ mt: 1.875 }}>
              <Box
                sx={{
                  display: 'flex',
                  alignItems: 'baseline',
                  gap: 1,
                  mb: 0.75,
                  fontSize: 13,
                }}
              >
                <Tooltip title={row.sessionId} placement="top" arrow>
                  <Box
                    sx={{
                      fontFamily: fontFamilies.mono,
                      fontWeight: 600,
                      flex: 1,
                      minWidth: 0,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                      cursor: 'help',
                    }}
                  >
                    {shortSessionId(row.sessionId)}
                  </Box>
                </Tooltip>
                <Box
                  sx={{
                    color: 'text.secondary',
                    fontVariantNumeric: 'tabular-nums',
                    whiteSpace: 'nowrap',
                  }}
                >
                  <Box component="b" sx={{ color: bandColors[band], fontWeight: 700 }}>
                    {formatCacheEfficiency(row.cacheEfficiency)}
                  </Box>{' '}
                  · {USD_FORMATTER.format(row.costUsd)}
                </Box>
              </Box>
              <LinearProgress
                variant="determinate"
                value={percentage}
                sx={{
                  height: 8,
                  borderRadius: radii.pill,
                  bgcolor: trackColor,
                  '& .MuiLinearProgress-bar': {
                    backgroundColor: bandColors[band],
                    borderRadius: radii.pill,
                  },
                }}
              />
              <Box sx={{ mt: 0.5, fontSize: 12, color: 'text.secondary' }}>
                {formatCompact(row.cacheReadTokens)} of {formatCompact(row.inputSideTokens)} input-side
                tokens cached
              </Box>
            </Box>
          );
        })
      )}
    </Paper>
  );
};

export default CacheEfficiencyRankCard;
