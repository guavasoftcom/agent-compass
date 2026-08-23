import { Box, Paper, Stack, Typography } from '@mui/material';
import { alpha, useTheme } from '@mui/material/styles';
import PageLayout from '../../components/PageLayout';
import StatCard from '../../components/StatCard';
import { fontFamilies } from '../../theme/typography';
import type { ToolFailureRateRow, ToolRepeatStatRow } from '../../api';
import ToolRepeatsCard from './components/ToolRepeatsCard';

export interface ToolReliabilityPageViewProps {
  rows: ToolFailureRateRow[];
  totalCalls: number;
  totalFailures: number;
  overallRate: number;
  worstTool: ToolFailureRateRow | null;
  minCallsForRanking: number;
  isLoading: boolean;
  error: Error | null;
  repeatRows: ToolRepeatStatRow[];
  isRepeatsLoading: boolean;
}

const HIGH_FAILURE = 0.2; // ≥20% renders in the error color, otherwise warning

const formatPercent = (ratio: number): string => `${(ratio * 100).toFixed(1)}%`;

const FailureRanking = ({
  rows,
  isLoading,
}: {
  rows: ToolFailureRateRow[];
  isLoading: boolean;
}) => {
  const theme = useTheme();
  const hasData = rows.length > 0;

  // Real tool inventories run 10-15+ tools where most have zero failures — showing every
  // tool as an equal-weight bar buries the 2-3 that actually matter. Only failing tools get
  // a ranked bar; the rest collapse into a closed-by-default disclosure below.
  const failing = rows
    .filter((row) => row.failures > 0)
    .sort((a, b) =>
      b.failureRate !== a.failureRate
        ? b.failureRate - a.failureRate
        : b.failures - a.failures,
    );
  const zeroFailure = rows
    .filter((row) => row.failures === 0)
    .sort((a, b) => b.calls - a.calls);
  const zeroFailureCalls = zeroFailure.reduce((sum, row) => sum + row.calls, 0);
  const maxRate = failing.reduce(
    (max, row) => Math.max(max, row.failureRate),
    0,
  );

  return (
    <Paper variant="outlined" sx={{ p: 2.5, height: '100%' }}>
      <Stack
        direction="row"
        sx={{
          alignItems: 'baseline',
          justifyContent: 'space-between',
          gap: 1.5,
          mb: 1,
        }}
      >
        <Typography variant="subtitle1">Tools by failure rate</Typography>
        {hasData && (
          <Typography variant="caption" color="text.secondary">
            failures / calls · bar scaled to highest rate
          </Typography>
        )}
      </Stack>

      {!hasData && !isLoading ? (
        <Typography color="text.secondary">No data in this window.</Typography>
      ) : (
        <>
          {failing.length === 0 ? (
            <Typography color="text.secondary" sx={{ mt: 1.5 }}>
              No failing tools in this window.
            </Typography>
          ) : (
            <Box sx={{ mt: 1.5 }}>
              {failing.map((row) => {
                const high = row.failureRate >= HIGH_FAILURE;
                const color = high
                  ? theme.palette.error.main
                  : theme.palette.warning.main;
                const width =
                  maxRate > 0 ? (row.failureRate / maxRate) * 100 : 0;
                return (
                  <Box
                    key={row.tool}
                    sx={{ mb: 2, '&:last-of-type': { mb: 0 } }}
                  >
                    <Box
                      sx={{
                        display: 'flex',
                        alignItems: 'baseline',
                        gap: 1,
                        mb: 0.75,
                      }}
                    >
                      <Typography
                        sx={{
                          fontSize: 14,
                          fontWeight: 600,
                          flex: 1,
                          minWidth: 0,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {row.tool}
                      </Typography>
                      <Typography
                        sx={{
                          fontSize: 13,
                          color: 'text.secondary',
                          fontVariantNumeric: 'tabular-nums',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {row.failures.toLocaleString()} /{' '}
                        {row.calls.toLocaleString()}
                      </Typography>
                      <Typography
                        sx={{
                          fontSize: 13.5,
                          fontWeight: 700,
                          color,
                          fontVariantNumeric: 'tabular-nums',
                          whiteSpace: 'nowrap',
                          minWidth: 52,
                          textAlign: 'right',
                        }}
                      >
                        {formatPercent(row.failureRate)}
                      </Typography>
                    </Box>
                    <Box
                      sx={(t) => ({
                        height: 9,
                        borderRadius: '6px',
                        bgcolor:
                          t.custom?.progressTrack ?? t.palette.action.hover,
                        overflow: 'hidden',
                      })}
                    >
                      <Box
                        sx={{
                          height: '100%',
                          borderRadius: '6px',
                          width: `${width}%`,
                          background: `linear-gradient(90deg, ${alpha(color, 0.55)}, ${color})`,
                        }}
                      />
                    </Box>
                  </Box>
                );
              })}
            </Box>
          )}

          {zeroFailure.length > 0 && (
            <Box
              component="details"
              sx={{
                mt: failing.length > 0 ? 2.5 : 1.5,
                pt: 1.75,
                borderTop: '1px solid',
                borderColor: 'divider',
                '& summary::-webkit-details-marker': { display: 'none' },
                '&[open] .disclosure-arrow': { transform: 'rotate(90deg)' },
              }}
            >
              <Box
                component="summary"
                sx={{
                  cursor: 'pointer',
                  listStyle: 'none',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 0.75,
                  fontSize: 13,
                  fontWeight: 600,
                  color: 'text.secondary',
                  '&:hover': { color: 'text.primary' },
                }}
              >
                <Box
                  component="span"
                  className="disclosure-arrow"
                  sx={{
                    display: 'inline-block',
                    fontSize: 20,
                    color: 'text.disabled',
                    transition: 'transform 120ms',
                  }}
                >
                  ▸
                </Box>
                {zeroFailure.length} tools with no failures ·{' '}
                {zeroFailureCalls.toLocaleString()} calls
              </Box>
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: '1fr 1fr',
                  columnGap: 3,
                  rowGap: 0,
                  mt: 1.5,
                }}
              >
                {zeroFailure.map((row) => (
                  <Box
                    key={row.tool}
                    sx={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      gap: 1.25,
                      fontSize: 13,
                      py: 0.875,
                      borderBottom: '1px solid',
                      borderColor: 'divider',
                    }}
                  >
                    <Box component="span" sx={{ fontWeight: 500 }}>
                      {row.tool}
                    </Box>
                    <Box
                      component="span"
                      sx={{
                        color: 'text.disabled',
                        fontVariantNumeric: 'tabular-nums',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {row.calls.toLocaleString()} calls
                    </Box>
                  </Box>
                ))}
              </Box>
            </Box>
          )}
        </>
      )}
    </Paper>
  );
};

const ReliabilityMixCard = ({
  totalCalls,
  totalFailures,
  overallRate,
  isLoading,
}: {
  totalCalls: number;
  totalFailures: number;
  overallRate: number;
  isLoading: boolean;
}) => {
  const theme = useTheme();
  const hasData = totalCalls > 0;
  const succeeded = Math.max(0, totalCalls - totalFailures);
  const okColor = theme.palette.success.main;
  const badColor = theme.palette.error.main;
  const legend = [
    { label: 'Succeeded', value: succeeded, color: okColor },
    { label: 'Failed', value: totalFailures, color: badColor },
  ];

  return (
    <Paper variant="outlined" sx={{ p: 2.5, height: '100%' }}>
      <Typography variant="subtitle1" gutterBottom>
        Reliability mix
      </Typography>

      {!hasData && !isLoading ? (
        <Typography color="text.secondary">No data in this window.</Typography>
      ) : (
        <>
          <Stack
            direction="row"
            sx={{ alignItems: 'baseline', gap: 1, mt: 1.5 }}
          >
            <Typography
              sx={{
                fontFamily: fontFamilies.display,
                fontWeight: 800,
                fontSize: 34,
                lineHeight: 1,
                letterSpacing: -1,
              }}
            >
              {formatPercent(overallRate)}
            </Typography>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ letterSpacing: 0.8, textTransform: 'uppercase' }}
            >
              fail rate
            </Typography>
          </Stack>

          <Box
            sx={(t) => ({
              display: 'flex',
              height: 14,
              borderRadius: '8px',
              overflow: 'hidden',
              bgcolor: t.custom?.progressTrack ?? t.palette.action.hover,
              mt: 1.75,
            })}
          >
            <Box sx={{ flexGrow: succeeded, bgcolor: okColor }} />
            <Box
              sx={{
                flexGrow: totalFailures,
                minWidth: totalFailures > 0 ? 4 : 0,
                bgcolor: badColor,
              }}
            />
          </Box>

          <Stack sx={{ mt: 2 }}>
            {legend.map((row, index) => (
              <Stack
                key={row.label}
                direction="row"
                spacing={1.25}
                sx={{
                  alignItems: 'center',
                  py: 1.1,
                  borderBottom:
                    index < legend.length - 1 ? '1px solid' : 'none',
                  borderColor: 'divider',
                }}
              >
                <Typography
                  variant="body2"
                  color="text.disabled"
                  sx={{
                    width: 16,
                    flexShrink: 0,
                    fontVariantNumeric: 'tabular-nums',
                  }}
                >
                  {index + 1}
                </Typography>
                <Box
                  sx={{
                    width: 10,
                    height: 10,
                    borderRadius: '3px',
                    flexShrink: 0,
                    bgcolor: row.color,
                  }}
                />
                <Typography variant="body2" sx={{ flex: 1, fontWeight: 600 }}>
                  {row.label}
                </Typography>
                <Typography
                  variant="body2"
                  color="text.secondary"
                  sx={{
                    whiteSpace: 'nowrap',
                    fontVariantNumeric: 'tabular-nums',
                  }}
                >
                  <Box
                    component="span"
                    sx={{ color: 'text.primary', fontWeight: 700 }}
                  >
                    {row.value.toLocaleString()}
                  </Box>{' '}
                  ·{' '}
                  {totalCalls > 0
                    ? ((row.value / totalCalls) * 100).toFixed(1)
                    : '0.0'}
                  %
                </Typography>
              </Stack>
            ))}
          </Stack>
        </>
      )}
    </Paper>
  );
};

const ToolReliabilityPageView = ({
  rows,
  totalCalls,
  totalFailures,
  overallRate,
  worstTool,
  minCallsForRanking,
  isLoading,
  error,
  repeatRows,
  isRepeatsLoading,
}: ToolReliabilityPageViewProps) => {
  return (
    <PageLayout
      subtitle={
        'Tool execution failure rate over the selected window. Counts only tool_result ' +
        'events that actually fired — denied-at-hook invocations are not included.'
      }
      error={error}
    >
      {/* Stat cards span the full width */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: {
            xs: '1fr',
            sm: '1fr 1fr',
            md: 'repeat(4, 1fr)',
          },
          gap: 2,
        }}
      >
        <StatCard
          label="Overall failure rate"
          value={totalCalls === 0 ? '—' : formatPercent(overallRate)}
          accent
          sub={
            totalFailures > 0 ? (
              <>
                <Box
                  component="span"
                  sx={{ color: 'primary.main', fontWeight: 600 }}
                >
                  {totalFailures.toLocaleString()}
                </Box>{' '}
                failures across all tools
              </>
            ) : undefined
          }
        />
        <StatCard
          label="Total calls"
          value={totalCalls.toLocaleString()}
          sub="tool_result events"
        />
        <StatCard
          label="Total failures"
          value={totalFailures.toLocaleString()}
          sub="execution errors"
        />
        <StatCard
          label={`Most-failing tool (≥${minCallsForRanking} calls)`}
          value={worstTool ? worstTool.tool : '—'}
          sub={
            worstTool ? (
              <>
                <Box
                  component="span"
                  sx={{ color: 'primary.main', fontWeight: 600 }}
                >
                  {formatPercent(worstTool.failureRate)}
                </Box>{' '}
                failure rate
              </>
            ) : undefined
          }
        />
      </Box>

      {/* Failure-rate bars beside the Reliability mix donut — balanced heights */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '1.6fr 1fr' },
          gap: 2,
          alignItems: 'stretch',
        }}
      >
        <FailureRanking rows={rows} isLoading={isLoading} />

        <ReliabilityMixCard
          totalCalls={totalCalls}
          totalFailures={totalFailures}
          overallRate={overallRate}
          isLoading={isLoading}
        />
      </Box>

      <ToolRepeatsCard rows={repeatRows} isLoading={isRepeatsLoading} />
    </PageLayout>
  );
};

export default ToolReliabilityPageView;
