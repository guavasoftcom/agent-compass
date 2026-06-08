import { Box, Paper, Stack, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import PageLayout from '../../components/PageLayout';
import StatCard from '../../components/StatCard';
import DonutCard from '../../components/DonutCard';
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
  const sorted = [...rows].sort((a, b) =>
    b.failureRate !== a.failureRate ? b.failureRate - a.failureRate : b.failures - a.failures,
  );

  return (
    <Paper variant="outlined" sx={{ p: 2.5, height: '100%' }}>
      <Stack
        direction="row"
        sx={{ alignItems: 'baseline', justifyContent: 'space-between', gap: 1.5, mb: 1 }}
      >
        <Typography variant="subtitle1">Tools by failure rate</Typography>
        {hasData && (
          <Typography variant="caption" color="text.secondary">
            failures / calls · descending
          </Typography>
        )}
      </Stack>

      {!hasData && !isLoading ? (
        <Typography color="text.secondary">No data in this window.</Typography>
      ) : (
        <Box sx={{ mt: 1.5 }}>
          {sorted.map((row) => {
            const high = row.failureRate >= HIGH_FAILURE;
            const color = high ? theme.palette.error.main : theme.palette.warning.main;
            return (
              <Box key={row.tool} sx={{ mb: 2, '&:last-of-type': { mb: 0 } }}>
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
                    {row.failures.toLocaleString()} / {row.calls.toLocaleString()}
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
                    bgcolor: t.custom?.progressTrack ?? t.palette.action.hover,
                    overflow: 'hidden',
                  })}
                >
                  <Box
                    sx={{
                      height: '100%',
                      borderRadius: '6px',
                      width: `${Math.min(100, Math.max(row.failureRate * 100, 1.5))}%`,
                      background: `linear-gradient(90deg, color-mix(in srgb, ${color} 55%, transparent), ${color})`,
                    }}
                  />
                </Box>
              </Box>
            );
          })}
        </Box>
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
  const theme = useTheme();
  const succeeded = Math.max(0, totalCalls - totalFailures);

  return (
    <PageLayout
      subtitle={
        'Tool execution failure rate over the selected window. Counts only tool_result '
        + 'events that actually fired — denied-at-hook invocations are not included.'
      }
      error={error}
    >
      {/* Stat cards span the full width */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', md: 'repeat(4, 1fr)' },
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
                <Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}>
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
                <Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}>
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

        <DonutCard
          title="Reliability mix"
          slices={[
            { label: 'Succeeded', value: succeeded, color: theme.palette.success.main },
            { label: 'Failed', value: totalFailures, color: theme.palette.error.main },
          ]}
          ranked
          centerValue={totalCalls === 0 ? '—' : formatPercent(overallRate)}
          centerLabel="fail rate"
          hasData={totalCalls > 0}
          isLoading={isLoading}
        />
      </Box>

      <ToolRepeatsCard rows={repeatRows} isLoading={isRepeatsLoading} />
    </PageLayout>
  );
};

export default ToolReliabilityPageView;
