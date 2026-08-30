import { Box, Stack, Typography } from '@mui/material';
import ChartCard from '../../../../components/ChartCard';

/** One skill or subagent row inside a single model's block, ranked by that model's own calls. */
export interface ModelFirstBlockRow {
  identifier: string;
  calls: number;
  /** Render the row name dimmed + italic (e.g. an "unknown" bucket). */
  muted?: boolean;
}

/** One model's block: its total across every row, and the rows it actually called, ranked. */
export interface ModelFirstBlock {
  /** Raw model id as the backend reports it (e.g. "claude-opus-4-8"). */
  model: string;
  /** Short display name for the block header (e.g. "Opus 5"). */
  label: string;
  /** Resolved palette colour, shared with the block header dot and every bar. */
  color: string;
  totalCalls: number;
  /** Ranked highest → lowest by calls; rows with 0 calls under this model are omitted. */
  rows: ModelFirstBlockRow[];
}

export interface ModelFirstBlocksProps {
  title: string;
  subtitle: string;
  blocks: ModelFirstBlock[];
  isLoading: boolean;
  emptyLabel: string;
  /**
   * Formats a single row's value. Defaults to a thousands-separated count,
   * right for the default calls metric — the Skills & Subagents page's Cost
   * mode passes `USD_FORMATTER.format` instead.
   */
  formatValue?: (value: number) => string;
  /**
   * Formats a block header's total (the right-aligned figure next to the
   * model name). Defaults to `"<count> calls"`; the Cost mode passes a
   * formatter that renders the dollar total with no "calls" suffix.
   */
  formatTotal?: (value: number) => string;
}

const BAR_TRACK_COLUMN = '1fr 2fr 34px';

/**
 * "Skills by model" / "Subagents by model" — one block per model (fixed
 * order: Sonnet, Opus, Haiku), each ranking the skills/subagents that model
 * actually called. Replaces the old single stacked bar per skill: grouping by
 * model instead of by skill answers "what does this model get used for" and
 * scales to a future 4th/5th model as another block rather than a squeezed
 * matrix column.
 *
 * Each block's bar scale is local to that block (`value / block's own max`),
 * not global across models — a model's own top skill always fills the bar
 * even when another model has a much higher absolute count. A model with no
 * calls anywhere in the window still renders its block, with a muted
 * "No calls in this window" line instead of an empty stack.
 *
 * Hand-built CSS, matching `ModelBreakdownCard`'s prior approach (there is no
 * chart library in this app).
 */
const ModelFirstBlocks = ({
  title,
  subtitle,
  blocks,
  isLoading,
  emptyLabel,
  formatValue = (value: number) => value.toLocaleString(),
  formatTotal = (value: number) => `${value.toLocaleString()} calls`,
}: ModelFirstBlocksProps) => {
  const hasAnyData = blocks.some((block) => block.totalCalls > 0);

  return (
    <ChartCard title={title} subtitle={subtitle} fillHeight>
      {!hasAnyData && !isLoading ? (
        <Box sx={{ height: 200, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          <Typography color="text.secondary">{emptyLabel}</Typography>
        </Box>
      ) : (
        <Stack sx={{ flex: 1, mt: 0.5 }}>
          {blocks.map((block, index) => {
            const maxCalls = block.rows.length > 0 ? block.rows[0].calls : 1;
            return (
              <Box
                key={block.model}
                sx={{
                  py: '13px',
                  borderBottom: index < blocks.length - 1 ? '1px solid' : 'none',
                  borderColor: 'divider',
                  '&:first-of-type': { pt: '2px' },
                  '&:last-of-type': { borderBottom: 'none', pb: 0 },
                }}
              >
                <Stack direction="row" sx={{ alignItems: 'center', gap: 1, mb: 1.25 }}>
                  <Box
                    sx={{ width: 10, height: 10, borderRadius: '3px', flexShrink: 0, bgcolor: block.color }}
                  />
                  <Typography sx={{ fontSize: 13.5, fontWeight: 600, color: 'text.primary' }}>
                    {block.label}
                  </Typography>
                  <Typography
                    sx={{
                      ml: 'auto',
                      fontSize: 12.5,
                      fontWeight: 500,
                      color: 'text.secondary',
                      fontVariantNumeric: 'tabular-nums',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {formatTotal(block.totalCalls)}
                  </Typography>
                </Stack>

                {block.rows.length === 0 ? (
                  <Typography sx={{ fontSize: 12.5, color: 'text.disabled', fontStyle: 'italic' }}>
                    No calls in this window
                  </Typography>
                ) : (
                  <Stack spacing={0.75}>
                    {block.rows.map((row) => (
                      <Box
                        key={row.identifier}
                        sx={{
                          display: 'grid',
                          gridTemplateColumns: BAR_TRACK_COLUMN,
                          alignItems: 'center',
                          gap: 1.25,
                          fontSize: 12.5,
                        }}
                      >
                        <Box
                          component="span"
                          sx={{
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            minWidth: 0,
                            ...(row.muted && { fontStyle: 'italic', color: 'text.disabled' }),
                          }}
                        >
                          {row.identifier}
                        </Box>
                        <Box
                          sx={(theme) => ({
                            height: 7,
                            borderRadius: '4px',
                            bgcolor: theme.custom?.progressTrack ?? theme.palette.action.hover,
                            overflow: 'hidden',
                          })}
                        >
                          <Box
                            sx={{
                              height: '100%',
                              borderRadius: '4px',
                              width: `${(row.calls / maxCalls) * 100}%`,
                              bgcolor: block.color,
                            }}
                          />
                        </Box>
                        <Box
                          component="span"
                          sx={{ textAlign: 'right', color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}
                        >
                          {formatValue(row.calls)}
                        </Box>
                      </Box>
                    ))}
                  </Stack>
                )}
              </Box>
            );
          })}
        </Stack>
      )}
    </ChartCard>
  );
};

export default ModelFirstBlocks;
