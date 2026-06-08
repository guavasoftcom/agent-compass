import { Box, LinearProgress, Paper, Stack, Typography } from '@mui/material';
import { colorForIndex } from '../../../../theme';
import type { ToolCallRowWithShare } from '../../ToolCallsPageView';

export interface ToolRankingCardProps {
  rowsWithShare: ToolCallRowWithShare[];
  hasData: boolean;
  isLoading: boolean;
}

const ToolRankingCard = ({
  rowsWithShare,
  hasData,
  isLoading,
}: ToolRankingCardProps) => {
  const totalCalls = rowsWithShare.reduce((sum, row) => sum + row.calls, 0);

  return (
    <Paper variant="outlined" sx={{ p: 2, height: '100%' }}>
      <Stack
        direction="row"
        sx={{ alignItems: 'baseline', justifyContent: 'space-between', mb: 1 }}
      >
        <Typography variant="subtitle1">Tools by call count</Typography>
        {hasData && (
          <Typography variant="caption" color="text.secondary">
            share of {totalCalls.toLocaleString()} calls
          </Typography>
        )}
      </Stack>
      {!hasData && !isLoading ? (
        <Typography color="text.secondary">
          No data in this window.
        </Typography>
      ) : (
        <Box
          sx={{
            mt: 1,
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1fr) auto auto auto',
            columnGap: 1,
            rowGap: 0.5,
            alignItems: 'baseline',
          }}
        >
          {rowsWithShare.map((row, index) => {
            const color = colorForIndex(index);
            return (
              <Box key={row.tool} sx={{ display: 'contents' }}>
                <Typography
                  variant="body2"
                  noWrap
                  sx={{ overflow: 'hidden', textOverflow: 'ellipsis', fontWeight: 600 }}
                >
                  {row.tool}
                </Typography>
                <Typography
                  variant="body2"
                  sx={{
                    textAlign: 'right',
                    fontVariantNumeric: 'tabular-nums',
                    fontWeight: 600,
                  }}
                >
                  {row.calls.toLocaleString()}
                </Typography>
                <Typography variant="body2" color="text.disabled">
                  ·
                </Typography>
                <Typography
                  variant="body2"
                  color="text.secondary"
                  sx={{
                    textAlign: 'right',
                    fontVariantNumeric: 'tabular-nums',
                  }}
                >
                  {row.share.toFixed(1)}%
                </Typography>
                <LinearProgress
                  variant="determinate"
                  value={row.share}
                  sx={(theme) => ({
                    gridColumn: '1 / -1',
                    height: 8,
                    mb: 1.5,
                    bgcolor:
                      theme.custom?.progressTrack ??
                      theme.palette.action.hover,
                    '& .MuiLinearProgress-bar': { bgcolor: color },
                  })}
                />
              </Box>
            );
          })}
        </Box>
      )}
    </Paper>
  );
};

export default ToolRankingCard;
