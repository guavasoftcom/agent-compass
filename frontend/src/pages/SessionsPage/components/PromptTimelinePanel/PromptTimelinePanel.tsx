import { Fragment, useState, type ReactElement } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import {
  Box,
  CircularProgress,
  Tooltip,
  Typography,
  alpha,
} from '@mui/material';
import type { Theme } from '@mui/material/styles';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import type { SessionPromptRow, SessionTokenBreakdown } from '../../../../api';
import { AttributeList } from '../../../../components/AttributeList';
import {
  AttributeValue,
  type ValueDialogState,
} from '../../../../components/AttributeList/AttributeValue';
import { ExpandedValueDialog } from '../../../../components/AttributeList/ExpandedValueDialog';
import PromptSummaryText from '../../../../components/PromptSummaryText';
import {
  auroraColors,
  gradients,
  neutralColors,
} from '../../../../theme/colors';
import { fontFamilies } from '../../../../theme/typography';
import {
  USD_FORMATTER,
  costTier,
  formatTimestamp,
  formatTokens,
} from '../sessionsFormat';

const NUM_FORMATTER = new Intl.NumberFormat('en-US');

const formatPromptTimestamp = (value: string): string =>
  value
    ? new Date(value).toLocaleString('en-US', {
        month: 'short',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit',
        second: '2-digit',
      })
    : '';

// Model label + accent hue. Opus (priciest tier) → primary violet, Sonnet →
// pink, both with a tinted pill background; Haiku (and anything unknown) stays
// neutral, so the "plain/cheap" tier doesn't compete visually with the two
// colored chips. Keyed on the leading token so "claude-sonnet-4-5" and
// "sonnet" both resolve. Sonnet's pink is mode-aware like the theme's other
// pink figures (deeper on the light surface, brighter on dark) rather than a
// single flat hex.
const MODEL_LABEL: Record<string, string> = {
  opus: 'Opus',
  sonnet: 'Sonnet',
  haiku: 'Haiku',
};

const modelKeyOf = (model: string | null | undefined): string | null => {
  if (!model) {
    return null;
  }
  const key = model.toLowerCase();
  return (
    (['opus', 'sonnet', 'haiku'] as const).find((modelKey) =>
      key.includes(modelKey),
    ) ?? null
  );
};

// Shared by the model chip (dot + tinted background) and the turn card's rail
// dot, so a turn's accent color can never disagree between the two.
const modelAccentColor = (modelKey: string | null, theme: Theme): string => {
  if (modelKey === 'opus') {
    return theme.palette.primary.main;
  }
  if (modelKey === 'sonnet') {
    return theme.palette.mode === 'dark'
      ? auroraColors.pinkBright
      : auroraColors.pink;
  }
  return theme.palette.text.disabled;
};

const ModelChip = ({ model }: { model: string | null | undefined }) => {
  if (!model) {
    return null;
  }
  const modelKey = modelKeyOf(model);
  const label = modelKey ? MODEL_LABEL[modelKey] : model;
  const tinted = modelKey === 'opus' || modelKey === 'sonnet';
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.75,
        height: 20,
        px: 1.125,
        borderRadius: 999,
        fontFamily: fontFamilies.display,
        fontSize: 10.5,
        fontWeight: 600,
        letterSpacing: 0.2,
        whiteSpace: 'nowrap',
        color: tinted
          ? (t: Theme) => modelAccentColor(modelKey, t)
          : 'text.secondary',
        bgcolor: tinted
          ? (t: Theme) => alpha(modelAccentColor(modelKey, t), 0.16)
          : (t: Theme) =>
              alpha(
                t.palette.text.primary,
                t.palette.mode === 'dark' ? 0.08 : 0.06,
              ),
      }}
    >
      <Box
        component="span"
        sx={{
          width: 6,
          height: 6,
          borderRadius: '50%',
          bgcolor: (t: Theme) => modelAccentColor(modelKey, t),
        }}
      />
      {label}
    </Box>
  );
};

const ToolChips = ({
  tools,
  muted = false,
}: {
  tools: { name: string; count: number }[] | null | undefined;
  // Background-activity variant: dimmer fill/text than the default chip, so a
  // turn's own tool calls (default) read as the primary signal and a trace's
  // background subagent calls (muted) read as secondary context. Never shows
  // the "No tool calls" fallback — callers only render this variant when
  // there's a non-empty background list to show.
  muted?: boolean;
}) => {
  if (!tools || tools.length === 0) {
    if (muted) {
      return null;
    }
    return (
      <Box sx={{ display: 'flex', mt: 0.25 }}>
        <Box
          component="span"
          sx={{ fontSize: 11.5, fontStyle: 'italic', color: 'text.disabled' }}
        >
          No tool calls
        </Box>
      </Box>
    );
  }
  const shown = tools.slice(0, 5);
  const overflow = tools.length - shown.length;
  const chipColor = muted ? 'text.disabled' : auroraColors.mutedSlate;
  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 0.75, mt: 0.25 }}>
      {muted ? (
        <Box
          component="span"
          sx={{ fontSize: 10.5, fontStyle: 'italic', color: 'text.disabled', mr: 0.25 }}
        >
          background:
        </Box>
      ) : null}
      {shown.map((tool, index) => (
        <Box
          key={`${tool.name}-${index}`}
          component="span"
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 0.6,
            height: 21,
            px: 1,
            borderRadius: 0.875,
            fontFamily: fontFamilies.body,
            fontSize: 11.5,
            fontWeight: 500,
            // Same blue as the "Tools" span hue on the Trace Detail page
            // (auroraColors.cyanBright) — every tool chip renders in one flat
            // color rather than a per-category palette, matching that page's
            // convention that a tool call is one visual category. The muted
            // variant dims to text.disabled so background activity doesn't
            // compete visually with the turn's own tool calls above it.
            color: chipColor,
            bgcolor: muted ? alpha(neutralColors.inkLight, 0.12) : alpha(auroraColors.mutedSlate, 0.16),
          }}
        >
          {tool.name}
          {tool.count > 1 ? (
            <Box
              component="b"
              sx={{
                fontWeight: 700,
                fontSize: 10.5,
                // Not a literal 'white': in dark mode text.primary resolves to the
                // same near-white, but in light mode white on the chip's pale
                // alpha(mutedSlate, 0.16) ground made the count invisible. The muted
                // variant keeps the chip's own dimmed hue so background tool rows
                // don't regain emphasis through their counts.
                color: muted ? chipColor : 'text.primary',
                fontVariantNumeric: 'tabular-nums',
              }}
            >
              {tool.count}
            </Box>
          ) : null}
        </Box>
      ))}
      {overflow > 0 ? (
        <Box
          component="span"
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            height: 21,
            px: 1,
            borderRadius: 0.875,
            fontSize: 11,
            fontWeight: 600,
            color: 'text.secondary',
          }}
        >
          {`+${overflow}`}
        </Box>
      ) : null}
    </Box>
  );
};

// Cost-outlier tiering shared by the grid's Cost column and the drawer
// header's cost figure, so the two can never render a session's cost
// differently: plain below $8, amber ("warm") from $8, and the same
// violet→pink gradient text the "Median cost" stat card uses once the cost
// reaches the live P95 threshold ("hot").
export const CostValue = ({
  costUsd,
  hotThresholdUsd,
}: {
  costUsd: number;
  hotThresholdUsd: number;
}) => {
  const tier = costTier(costUsd, hotThresholdUsd);
  return (
    <Box
      component="b"
      sx={{
        fontFamily: fontFamilies.display,
        fontWeight: 700,
        fontSize: 14,
        ...(tier === 'warm' && { color: auroraColors.gold }),
        ...(tier === 'hot'
          ? {
              backgroundImage: gradients.auroraActionSoft,
              WebkitBackgroundClip: 'text',
              backgroundClip: 'text',
              color: 'transparent',
            }
          : tier === 'plain'
            ? { color: 'text.primary' }
            : {}),
      }}
    >
      {USD_FORMATTER.format(costUsd)}
    </Box>
  );
};

interface PromptTimelinePanelProps {
  prompts: SessionPromptRow[] | null;
  loading: boolean;
  error: Error | null;
  // Active dashboard window (epoch ms). When provided, turns whose timestamp
  // falls outside it render dimmed with a boundary divider — the endpoint returns
  // the WHOLE session, not just the windowed slice (see SESSIONS-BACKEND.md).
  windowStartMs?: number;
  windowEndMs?: number;
}

// Rich title for the token-usage tooltip: the four-way split, a "Working" subtotal
// (input + output + cache creation — the fresh/billed-high tokens), then Total.
// Exported so the SessionsPage grid can reuse it on the Tokens cell.
export const TokenBreakdownTitle = ({
  tokens,
}: {
  tokens: SessionTokenBreakdown;
}) => {
  const working = tokens.input + tokens.output + tokens.cacheCreation;
  const total = working + tokens.cacheRead;
  const rows: [string, number][] = [
    ['Input', tokens.input],
    ['Output', tokens.output],
    ['Cache creation', tokens.cacheCreation],
  ];
  return (
    <Box sx={{ minWidth: 168 }}>
      <Box
        sx={{
          fontFamily: fontFamilies.display,
          fontSize: 9.5,
          fontWeight: 700,
          letterSpacing: '1px',
          textTransform: 'uppercase',
          color: 'text.disabled',
          mb: 0.875,
        }}
      >
        Token usage
      </Box>
      {rows.map(([label, value]) => (
        <Box
          key={label}
          sx={{
            display: 'flex',
            justifyContent: 'space-between',
            gap: 1.75,
            fontSize: 11.5,
            lineHeight: 1.85,
          }}
        >
          <Box
            component="span"
            sx={{ color: 'text.secondary', fontWeight: 500 }}
          >
            {label}
          </Box>
          <Box
            component="span"
            sx={{
              fontVariantNumeric: 'tabular-nums',
              fontWeight: 600,
              color: 'text.primary',
            }}
          >
            {NUM_FORMATTER.format(value)}
          </Box>
        </Box>
      ))}
      {/* Working subtotal — input + output + cache creation (the fresh/billed-high tokens) */}
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          gap: 1.75,
          mt: 0.625,
          pt: 0.75,
          borderTop: '1px dashed',
          borderColor: 'divider',
          fontSize: 11.5,
          lineHeight: 1.85,
        }}
      >
        <Box component="span" sx={{ color: 'text.primary', fontWeight: 600 }}>
          Working
        </Box>
        <Box
          component="span"
          sx={{
            fontVariantNumeric: 'tabular-nums',
            fontWeight: 700,
            color: 'text.primary',
          }}
        >
          {NUM_FORMATTER.format(working)}
        </Box>
      </Box>
      {/* Cache read — muted; typically dwarfs the working tokens */}
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          gap: 1.75,
          fontSize: 11.5,
          lineHeight: 1.85,
        }}
      >
        <Box component="span" sx={{ color: 'text.secondary', fontWeight: 500 }}>
          Cache read
        </Box>
        <Box
          component="span"
          sx={{
            fontVariantNumeric: 'tabular-nums',
            fontWeight: 600,
            color: 'text.secondary',
          }}
        >
          {NUM_FORMATTER.format(tokens.cacheRead)}
        </Box>
      </Box>
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          gap: 1.75,
          mt: 0.75,
          pt: 0.875,
          borderTop: 1,
          borderColor: 'divider',
          fontSize: 11.5,
        }}
      >
        <Box component="span" sx={{ fontWeight: 700, color: 'text.primary' }}>
          Total
        </Box>
        <Box
          component="span"
          sx={{
            fontVariantNumeric: 'tabular-nums',
            fontWeight: 700,
            color: 'text.primary',
          }}
        >
          {NUM_FORMATTER.format(total)}
        </Box>
      </Box>
    </Box>
  );
};

// Styled tooltip wrapper: solid surface panel with border + soft shadow, matching
// the mockup (MUI's default tooltip is a translucent dark chip — wrong here).
// Shared by the per-turn TokenUsage and the grid's Tokens cell.
export const TokenBreakdownTooltip = ({
  tokens,
  children,
}: {
  tokens: SessionTokenBreakdown;
  children: ReactElement;
}) => (
  <Tooltip
    title={<TokenBreakdownTitle tokens={tokens} />}
    placement="top"
    arrow
    slotProps={{
      tooltip: {
        sx: {
          bgcolor: 'background.paper',
          color: 'text.primary',
          border: 1,
          borderColor: 'divider',
          borderRadius: '11px',
          px: 1.625,
          py: 1.375,
          maxWidth: 'none',
          boxShadow: `0 18px 44px ${alpha(neutralColors.shadowDeep, 0.28)}`,
        },
      },
      arrow: {
        sx: {
          color: 'background.paper',
          '&::before': { border: 1, borderColor: 'divider' },
        },
      },
    }}
  >
    {children}
  </Tooltip>
);

// "12K tokens" with the full breakdown (input/output/cache creation/cache read) on
// hover. Aurora sync: the combined total replaces the old "· N cached" caption —
// the per-kind split still lives one hover away via TokenBreakdownTooltip.
// Exported so the SessionsPage grid can reuse the same hover affordance on the
// Tokens cell. The dotted underline matches the grid's own hover-affordance
// convention (SessionsTable's Cost / Cache-eff. cells) so the hover is visible
// before the pointer arrives, not only via `cursor: help`.
export const TokenUsage = ({
  tokens,
}: {
  tokens: SessionTokenBreakdown | null | undefined;
}) => {
  if (!tokens) {
    return null;
  }
  const total =
    tokens.input + tokens.output + tokens.cacheCreation + tokens.cacheRead;
  return (
    <TokenBreakdownTooltip tokens={tokens}>
      <Box
        component="span"
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.5,
          fontFamily: fontFamilies.body,
          fontSize: 11.5,
          fontWeight: 500,
          color: 'text.secondary',
          fontVariantNumeric: 'tabular-nums',
          letterSpacing: '-0.1px',
          cursor: 'help',
          borderBottom: '1px dotted',
          borderColor: 'text.disabled',
          pb: '1px',
          '&::before': {
            content: '""',
            width: 3,
            height: 3,
            borderRadius: '50%',
            bgcolor: 'text.disabled',
          },
        }}
      >
        <Box component="b" sx={{ fontWeight: 700, color: 'text.primary' }}>
          {formatTokens(total)}
        </Box>
        &nbsp;tokens
      </Box>
    </TokenBreakdownTooltip>
  );
};

/**
 * Flags a turn whose cost and token figures are approximate.
 *
 * INTERVAL turns get a muted "approx" marker: their numbers are bucketed from
 * cumulative counters by timestamp and are a different, coarser measurement than
 * a REQUEST turn's — not merely a rounder version of the same one. Labeling them
 * is what stops a reader from comparing an exact turn against an approximate one
 * and concluding something changed. REQUEST turns render nothing: exact is the
 * expectation, so only the exception is worth the ink.
 */
const TurnAttributionMarker = ({
  attribution,
  requestCount,
}: {
  attribution: SessionPromptRow['attribution'];
  requestCount: number;
}) => {
  if (attribution === 'REQUEST' && requestCount > 0) {
    return null;
  }
  // Older sessions carry no attribution field at all; treat a missing value the
  // same as INTERVAL rather than implying exactness we cannot vouch for.
  return (
    <Tooltip
      title={
        'Approximate. This turn has no per-request logs, so its model, cost and tokens were ' +
        'bucketed from cumulative counters by timestamp. Counter totals run lower than ' +
        "per-request sums on cache-heavy turns — don't compare the two directly."
      }
      placement="top"
      arrow
    >
      <Box
        component="span"
        sx={{
          fontFamily: fontFamilies.display,
          fontSize: 9.5,
          fontWeight: 700,
          letterSpacing: '0.8px',
          textTransform: 'uppercase',
          color: 'text.disabled',
          cursor: 'help',
          whiteSpace: 'nowrap',
        }}
      >
        approx
      </Box>
    </Tooltip>
  );
};

// Flags a turn whose trace cost (already included in costUsd above it) is
// partly background spend — e.g. a fire-and-forget subagent dispatch that kept
// issuing requests after this turn ended and the next prompt was typed. Null
// when there's no background cost, matching the "only the exception earns ink"
// convention TurnAttributionMarker follows.
const BackgroundCostBadge = ({
  backgroundCostUsd,
}: {
  backgroundCostUsd: number | null | undefined;
}) => {
  if (!backgroundCostUsd) {
    return null;
  }
  return (
    <Tooltip
      title={
        'Includes spend from a subagent this turn dispatched that kept running after the turn ' +
        "ended — it's part of the cost above, not additional."
      }
      placement="top"
      arrow
    >
      <Box
        component="span"
        sx={{
          fontFamily: fontFamilies.mono,
          fontSize: 11,
          fontWeight: 600,
          color: 'warning.main',
          cursor: 'help',
          whiteSpace: 'nowrap',
        }}
      >
        {`+${USD_FORMATTER.format(backgroundCostUsd)} background`}
      </Box>
    </Tooltip>
  );
};

// Aurora glass timeline: a gradient rail with a glowing dot per turn, each turn
// a translucent card carrying its timestamp, model chip, per-turn cost, prompt
// text (or a placeholder for pre-capture rows), tool-call chips, and an optional
// "View trace" link. Recessed panel background matches the trace-summary inline
// expand elsewhere in the app. Long prompt text reuses the shared AttributeValue
// "View more" → ExpandedValueDialog machinery from components/AttributeList
// (same pattern as LogTable and the grid's own row detail) rather than
// rendering full text pre-wrapped inline.
const PromptTimelinePanel = ({
  prompts,
  loading,
  error,
  windowStartMs,
  windowEndMs,
}: PromptTimelinePanelProps) => {
  const [expandedValue, setExpandedValue] = useState<ValueDialogState | null>(
    null,
  );

  // No height cap and no scroll of its own: the panel fills its container (the
  // detail drawer's body), which owns the scrolling. Session identity lives in
  // the drawer header, so the panel header carries only the prompt count.
  const panelSx = {
    px: 2.5,
    pt: 2.25,
    pb: 3.25,
    minHeight: '100%',
    background: (t: Theme) =>
      `radial-gradient(600px 200px at 3% 0%, ${alpha(
        t.palette.primary.main,
        t.palette.mode === 'dark' ? 0.12 : 0.07,
      )}, transparent 70%), ${alpha(
        t.palette.text.primary,
        t.palette.mode === 'dark' ? 0.03 : 0.025,
      )}`,
  } as const;

  if (loading) {
    return (
      <Box sx={panelSx}>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            color: 'text.secondary',
          }}
        >
          <CircularProgress size={14} thickness={5} />
          <Typography sx={{ fontSize: 12.5 }}>Loading prompts…</Typography>
        </Box>
      </Box>
    );
  }
  if (error) {
    return (
      <Box sx={panelSx}>
        <Typography sx={{ fontSize: 12.5, color: 'error.main' }}>
          {`Failed to load prompts: ${error.message}`}
        </Typography>
      </Box>
    );
  }
  if (!prompts || prompts.length === 0) {
    return (
      <Box sx={panelSx}>
        <Typography sx={{ fontSize: 12.5, color: 'text.disabled' }}>
          No prompts captured for this session.
        </Typography>
      </Box>
    );
  }

  return (
    <Box sx={panelSx}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          mb: 1.75,
          fontFamily: fontFamilies.display,
          fontSize: 10.5,
          fontWeight: 700,
          letterSpacing: '1.4px',
          textTransform: 'uppercase',
          color: 'text.secondary',
        }}
      >
        <Box
          component="svg"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={2}
          sx={{ width: 14, height: 14, color: 'primary.main' }}
        >
          <path d="M12 8v4l3 2" />
          <circle cx="12" cy="12" r="9" />
        </Box>
        Prompt timeline
        <Box
          component="span"
          sx={{
            color: 'text.disabled',
            fontWeight: 600,
            letterSpacing: '0.5px',
          }}
        >
          {`${prompts.length} prompt${prompts.length === 1 ? '' : 's'}`}
        </Box>
      </Box>

      <Box
        sx={{
          position: 'relative',
          pl: '22px',
          display: 'flex',
          flexDirection: 'column',
          gap: 1.25,
          '&::before': {
            content: '""',
            position: 'absolute',
            left: '5px',
            top: '6px',
            bottom: '6px',
            width: '2px',
            borderRadius: '2px',
            background: (t) =>
              `linear-gradient(180deg, ${alpha(t.palette.primary.main, 0.4)}, transparent)`,
          },
        }}
      >
        {prompts.map((turn, index) => {
          const turnMs = turn.timestamp
            ? new Date(turn.timestamp).getTime()
            : NaN;
          const inWindow =
            windowStartMs == null || windowEndMs == null || Number.isNaN(turnMs)
              ? true
              : turnMs >= windowStartMs && turnMs <= windowEndMs;
          const previousTurn = prompts[index - 1];
          const previousTurnMs = previousTurn?.timestamp
            ? new Date(previousTurn.timestamp).getTime()
            : NaN;
          const previousTurnInWindow =
            windowStartMs == null ||
            windowEndMs == null ||
            Number.isNaN(previousTurnMs)
              ? true
              : previousTurnMs >= windowStartMs &&
                previousTurnMs <= windowEndMs;
          let boundary: string | null = null;
          if (index > 0 && !previousTurnInWindow && inWindow) {
            boundary = 'selected window starts';
          } else if (index > 0 && previousTurnInWindow && !inWindow) {
            boundary = 'selected window ends';
          }
          return (
            <Fragment key={`${turn.timestamp}-${index}`}>
              {boundary ? (
                <Box
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1.25,
                    my: 0.25,
                    '&::before, &::after': {
                      content: '""',
                      flex: 1,
                      height: '1px',
                      bgcolor: 'divider',
                    },
                  }}
                >
                  <Box
                    component="span"
                    sx={{
                      fontFamily: fontFamilies.display,
                      fontSize: 9.5,
                      fontWeight: 700,
                      letterSpacing: '1.2px',
                      textTransform: 'uppercase',
                      color: 'text.disabled',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {boundary}
                  </Box>
                </Box>
              ) : null}
              <Box
                sx={{
                  position: 'relative',
                  border: 1,
                  borderColor: 'divider',
                  borderRadius: 1.6,
                  bgcolor: 'background.paper',
                  boxShadow: 1,
                  px: 1.75,
                  py: 1.25,
                  opacity: inWindow ? 1 : 0.45,
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 0.75,
                  transition: 'border-color .14s',
                  '&:hover': {
                    borderColor: (t) => alpha(t.palette.primary.main, 0.32),
                  },
                  '&::before': {
                    content: '""',
                    position: 'absolute',
                    left: '-21px',
                    top: '16px',
                    width: 10,
                    height: 10,
                    borderRadius: '50%',
                    // Rail dot picks up the turn's model accent (opus/sonnet) instead
                    // of always being primary.main — the ring stays primary-tinted
                    // regardless, matching the design handoff.
                    bgcolor: (t) => modelAccentColor(modelKeyOf(turn.model), t),
                    boxShadow: (t) =>
                      `0 0 0 4px ${t.palette.background.default}, 0 0 0 5px ${alpha(t.palette.primary.main, 0.32)}`,
                  },
                }}
              >
                <Box
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 1.5,
                  }}
                >
                  {/* Wraps rather than overflows: in the detail drawer the card is
                  560px wide, so a long model name plus cost, tokens and the
                  "approx" marker no longer fit on one line — unwrapped, the
                  marker ran under the View-trace pill. */}
                  <Box
                    sx={{
                      display: 'flex',
                      alignItems: 'center',
                      flexWrap: 'wrap',
                      columnGap: 1.125,
                      rowGap: 0.5,
                      minWidth: 0,
                    }}
                  >
                    <Box
                      component="span"
                      sx={{
                        fontFamily: fontFamilies.mono,
                        fontSize: 11,
                        fontWeight: 500,
                        color: 'text.disabled',
                        whiteSpace: 'nowrap',
                        letterSpacing: '-0.2px',
                      }}
                    >
                      {formatPromptTimestamp(turn.timestamp)}
                    </Box>
                    <ModelChip model={turn.model} />
                    {turn.costUsd != null ? (
                      <Box
                        component="span"
                        sx={{
                          fontFamily: fontFamilies.display,
                          fontWeight: 700,
                          fontSize: 12.5,
                          color: 'text.primary',
                          fontVariantNumeric: 'tabular-nums',
                          letterSpacing: '-0.2px',
                        }}
                      >
                        {USD_FORMATTER.format(turn.costUsd)}
                      </Box>
                    ) : null}
                    <BackgroundCostBadge backgroundCostUsd={turn.backgroundCostUsd} />
                    <TokenUsage tokens={turn.tokens} />
                    <TurnAttributionMarker
                      attribution={turn.attribution}
                      requestCount={turn.requestCount ?? 0}
                    />
                  </Box>
                  {turn.traceId ? (
                    <Box
                      component={RouterLink}
                      to={`/traces/${turn.traceId}`}
                      sx={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 0.5,
                        height: 20,
                        px: '10px',
                        flexShrink: 0,
                        borderRadius: 999,
                        fontFamily: fontFamilies.display,
                        fontSize: 10.5,
                        fontWeight: 600,
                        letterSpacing: 0.2,
                        color: 'primary.main',
                        textDecoration: 'none',
                        bgcolor: (t) => alpha(t.palette.primary.main, 0.12),
                        boxShadow: (t) =>
                          `inset 0 0 0 1px ${alpha(t.palette.primary.main, 0.32)}`,
                        '&:hover': {
                          bgcolor: (t) => alpha(t.palette.primary.main, 0.22),
                        },
                      }}
                    >
                      View trace
                      <ArrowForwardIcon sx={{ fontSize: 12 }} />
                    </Box>
                  ) : null}
                </Box>

                <Box
                  sx={{
                    fontSize: 13,
                    lineHeight: 1.55,
                    color:
                      turn.prompt == null ? 'text.disabled' : 'text.primary',
                    fontStyle: turn.prompt == null ? 'italic' : 'normal',
                  }}
                >
                  {turn.prompt == null ? (
                    '(prompt text not captured)'
                  ) : (
                    <PromptSummaryText
                      prompt={turn.prompt}
                      onViewFullPrompt={(prompt) =>
                        setExpandedValue({
                          key: formatTimestamp(turn.timestamp),
                          value: prompt,
                        })
                      }
                      renderOrdinary={(prompt) => (
                        <AttributeValue
                          attrKey={formatTimestamp(turn.timestamp)}
                          value={prompt}
                          truncate
                          inlineExpand={false}
                          onExpand={setExpandedValue}
                        />
                      )}
                    />
                  )}
                </Box>

                <ToolChips tools={turn.tools} />
                <ToolChips tools={turn.backgroundTools} muted />
              </Box>
            </Fragment>
          );
        })}
      </Box>
      <ExpandedValueDialog
        state={expandedValue}
        onClose={() => setExpandedValue(null)}
        renderAttributeList={(attrs) => (
          <AttributeList attributes={attrs} truncate inlineExpand />
        )}
      />
    </Box>
  );
};

export default PromptTimelinePanel;
