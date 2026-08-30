import { Box, Paper, Tooltip, Typography } from '@mui/material';
import { alpha, useTheme } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import type { CostModelEffortCell } from '../../../../api';
import { USD_FORMATTER, formatCompact, shortModelName } from '../../../../lib/format';
import { colorForIndex } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import { cacheHitRateLabel } from '../../../TracesPage/tokenBreakdown';
import { denseCostTableSx } from '../../costTableStyles';

export interface CostDriversCardProps {
  cells: CostModelEffortCell[];
  isLoading: boolean;
  /**
   * A model's palette color, keyed by rank in the "What drove it" tab's Model mix
   * donut (`buildModelMix`) — NOT a per-row index into `cells`, since one model can
   * own several (model, effort) rows here. Passed down from the view so a model's
   * dot always agrees with its donut slice regardless of how many effort levels it
   * has. Falls back to `colorForIndex(0)` for a model the mix somehow doesn't know
   * about (shouldn't happen — both read the same `modelEffort` array).
   */
  modelColorIndex: (model: string) => number;
}

const NOT_RECORDED_LABEL = 'not recorded';

// Matches the trace waterfall's token tooltip (SpanWaterfallRow's SpanFullRateBadge):
// bold mono header line + a label/value grid, no dividers.
const tipGridSx = {
  display: 'grid',
  gridTemplateColumns: 'auto auto',
  columnGap: 1.5,
  rowGap: 0.3,
  fontSize: 11,
} as const;

const TipRow = ({ label, value }: { label: string; value: string }) => (
  <>
    <Box component="span" sx={{ opacity: 0.75 }}>
      {label}
    </Box>
    <Box component="span" sx={{ textAlign: 'right' }}>
      {value}
    </Box>
  </>
);

/**
 * Cost drivers: one row per (model, effort) pair, with the token composition behind
 * that cell's spend collapsed to two columns — "Expensive tokens" (input + cache
 * write + output, the three kinds billed at their model's full rate) and "Cache
 * read" (billed at a steep discount, but usually the largest raw count). A hover
 * tooltip on "Expensive tokens" breaks the sum back out into its three components,
 * so the row stays scannable without a 4-column token group. Deliberately shows
 * tokens and dollars side by side rather than a per-token-kind dollar figure —
 * splitting `cost_usd` across token kinds would need a rate table, and an earlier
 * revision of this dashboard measured that approach running 2-3x off real spend
 * (see the `span_costs` migration's header).
 * `effort` renders as "not recorded" rather than a default level when the backend
 * sends `null` — ~7% of requests carry no effort attribute.
 */
const CostDriversCard = ({ cells, isLoading, modelColorIndex }: CostDriversCardProps) => {
  const theme = useTheme();
  const hoverColor = theme.custom?.progressTrack ?? theme.palette.action.hover;
  const stripeColor = theme.custom?.rowStripe ?? alpha(hoverColor, 0.5);
  const totalCost = cells.reduce((sum, cell) => sum + cell.costUsd, 0);

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 0.5 }}>
        <Typography variant="subtitle1">Cost drivers</Typography>
        <Tooltip
          title={
            'Cache-read tokens are usually the dominant figure behind spend — they are the cost '
            + 'of re-reading accumulated context on every turn. Tokens and cost are shown side by '
            + 'side, never multiplied together: cost_usd is one number per request, and splitting '
            + 'it across token kinds would need a rate table this dashboard deliberately does not '
            + 'use (see the Tokens page for why).'
          }
          arrow
        >
          <InfoOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }} />
        </Tooltip>
      </Box>

      {!isLoading && cells.length === 0 ? (
        <Typography color="text.secondary">No priced requests in this window.</Typography>
      ) : (
        <Box sx={{ overflowX: 'auto' }}>
          <Box component="table" sx={denseCostTableSx(stripeColor, hoverColor)}>
            <Box component="thead">
              <Box component="tr">
                <Box component="th">Model</Box>
                <Box component="th" className="num">Effort</Box>
                <Box component="th" className="num">Requests</Box>
                <Box component="th" className="num">Cost</Box>
                <Box component="th" className="num">Share</Box>
                <Box component="th" className="num">Expensive tokens</Box>
                <Box component="th" className="num">Cache read</Box>
              </Box>
            </Box>
            <Box component="tbody">
              {cells.map((cell) => {
                const share = totalCost === 0 ? 0 : (cell.costUsd / totalCost) * 100;
                const cacheHitRate = cacheHitRateLabel({
                  input: cell.inputTokens,
                  output: cell.outputTokens,
                  cacheCreate: cell.cacheCreationTokens,
                  cacheRead: cell.cacheReadTokens,
                  total: cell.inputTokens + cell.outputTokens + cell.cacheCreationTokens + cell.cacheReadTokens,
                });
                return (
                  <Box component="tr" key={`${cell.model}-${cell.effort ?? 'none'}`}>
                    <Box component="td" sx={{ fontWeight: 600 }}>
                      <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1.125 }}>
                        <Box
                          sx={{
                            width: 9,
                            height: 9,
                            borderRadius: '3px',
                            flexShrink: 0,
                            bgcolor: colorForIndex(modelColorIndex(cell.model)),
                          }}
                        />
                        {shortModelName(cell.model)}
                      </Box>
                    </Box>
                    <Box
                      component="td"
                      className="num"
                      sx={cell.effort == null ? { fontStyle: 'italic', color: 'text.disabled' } : undefined}
                    >
                      {cell.effort ?? NOT_RECORDED_LABEL}
                    </Box>
                    <Box component="td" className="num">
                      {cell.requests.toLocaleString()}
                    </Box>
                    <Box component="td" className="num" sx={{ fontWeight: 700 }}>
                      {USD_FORMATTER.format(cell.costUsd)}
                    </Box>
                    <Box component="td" className="num" sx={{ color: 'text.secondary' }}>
                      {share.toFixed(1)}%
                    </Box>
                    <Box component="td" className="num" sx={{ color: 'text.secondary' }}>
                      <Tooltip
                        arrow
                        placement="top"
                        title={
                          <Box sx={{ py: 0.5, typography: 'mono' }}>
                            <Box sx={{ fontSize: 11.5, fontWeight: 700, mb: 0.3 }}>
                              {`${formatCompact(cell.inputTokens + cell.cacheCreationTokens + cell.outputTokens)} at full rate`}
                            </Box>
                            <Box sx={tipGridSx}>
                              <TipRow label="Input" value={cell.inputTokens.toLocaleString()} />
                              <TipRow label="Cache write" value={cell.cacheCreationTokens.toLocaleString()} />
                              <TipRow label="Output" value={cell.outputTokens.toLocaleString()} />
                            </Box>
                          </Box>
                        }
                      >
                        <Box component="span" sx={{ cursor: 'help', borderBottom: '1px dotted currentColor' }}>
                          {formatCompact(cell.inputTokens + cell.cacheCreationTokens + cell.outputTokens)}
                        </Box>
                      </Tooltip>
                    </Box>
                    <Box component="td" className="num" sx={{ color: 'text.secondary' }}>
                      <Tooltip
                        arrow
                        placement="top"
                        title={
                          <Box sx={{ py: 0.5, typography: 'mono' }}>
                            <Box sx={{ fontSize: 11.5, fontWeight: 700, mb: 0.3 }}>
                              {`${formatCompact(cell.cacheReadTokens)} cache read`}
                            </Box>
                            {cacheHitRate == null ? null : (
                              <Box sx={tipGridSx}>
                                <TipRow label="Of cacheable tokens" value={cacheHitRate} />
                              </Box>
                            )}
                            <Box
                              sx={{
                                mt: 0.5,
                                fontFamily: fontFamilies.body,
                                fontSize: 10.5,
                                opacity: 0.75,
                              }}
                            >
                              billed at 0.1x the input rate
                            </Box>
                          </Box>
                        }
                      >
                        <Box component="span" sx={{ cursor: 'help', borderBottom: '1px dotted currentColor' }}>
                          {formatCompact(cell.cacheReadTokens)}
                        </Box>
                      </Tooltip>
                    </Box>
                  </Box>
                );
              })}
            </Box>
          </Box>
        </Box>
      )}
    </Paper>
  );
};

export default CostDriversCard;
