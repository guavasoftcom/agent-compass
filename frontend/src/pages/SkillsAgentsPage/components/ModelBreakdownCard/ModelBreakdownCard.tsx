import { Box, Stack, Tooltip, Typography } from '@mui/material';
import ChartCard from '../../../../components/ChartCard';
import type { ChartCardLegendItem } from '../../../../components/ChartCard';

/** One model's slice of a single skill's / subagent's invocations. */
export interface ModelBreakdownSegment {
  /** Raw model id as the backend reports it (e.g. "claude-opus-4-8"). */
  model: string;
  /** Short display name for the legend and tooltip (e.g. "Opus 4 8"). */
  label: string;
  calls: number;
  /** Resolved palette color, shared with the card legend. */
  color: string;
}

/** One row of the card: a skill or subagent, its total, and its model split. */
export interface ModelBreakdownRow {
  identifier: string;
  totalCalls: number;
  /** Ordered to match the legend; models with no calls are absent. */
  segments: ModelBreakdownSegment[];
}

export interface ModelBreakdownCardProps {
  title: string;
  subtitle: string;
  rows: ModelBreakdownRow[];
  legendItems: ChartCardLegendItem[];
  isLoading: boolean;
  emptyLabel: string;
}

/**
 * "Skills by model" / "Subagents by model" — one row per skill or subagent, each
 * a horizontal segmented bar split by the model that made the call. Bars are
 * scaled against the busiest row rather than each row's own total, so bar length
 * reads as call volume and the segments read as the model mix within it.
 *
 * Hand-built CSS bars, matching `ToolLatencyCard` (there is no chart library in
 * this app).
 */
const ModelBreakdownCard = ({
  title,
  subtitle,
  rows,
  legendItems,
  isLoading,
  emptyLabel,
}: ModelBreakdownCardProps) => {
  const largestRowCalls = Math.max(...rows.map((row) => row.totalCalls), 1);

  return (
    <ChartCard title={title} subtitle={subtitle} legend={legendItems} fillHeight>
      {rows.length === 0 && !isLoading ? (
        <Box sx={{ height: 200, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          <Typography color="text.secondary">{emptyLabel}</Typography>
        </Box>
      ) : (
        <Stack spacing={1.75} sx={{ flex: 1, mt: 0.5 }}>
          {rows.map((row) => (
            <Box key={row.identifier}>
              <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1, mb: 0.875 }}>
                <Box
                  component="span"
                  sx={{
                    flex: 1,
                    minWidth: 0,
                    fontSize: 14,
                    fontWeight: 600,
                    color: 'text.primary',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {row.identifier}
                </Box>
                <Typography
                  sx={{
                    fontSize: 13,
                    fontWeight: 700,
                    color: 'text.primary',
                    fontVariantNumeric: 'tabular-nums',
                    flexShrink: 0,
                  }}
                >
                  {row.totalCalls.toLocaleString()}
                </Typography>
              </Stack>
              <Box
                sx={(theme) => ({
                  height: 10,
                  borderRadius: '6px',
                  bgcolor: theme.custom?.progressTrack ?? theme.palette.action.hover,
                  overflow: 'hidden',
                  display: 'flex',
                })}
              >
                {row.segments.map((segment) => (
                  <Tooltip
                    key={segment.model}
                    title={`${segment.label} · ${segment.calls.toLocaleString()} calls`}
                    placement="top"
                    arrow
                    disableInteractive
                  >
                    <Box
                      sx={{
                        width: `${(segment.calls / largestRowCalls) * 100}%`,
                        bgcolor: segment.color,
                      }}
                    />
                  </Tooltip>
                ))}
              </Box>
            </Box>
          ))}
        </Stack>
      )}
    </ChartCard>
  );
};

export default ModelBreakdownCard;
