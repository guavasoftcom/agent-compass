import { Fragment, useState, type ReactElement } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, CircularProgress, Tooltip, Typography, alpha } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import type { SessionPromptRow, SessionTokenBreakdown } from '../../../../api';
import { AttributeList } from '../../../../components/AttributeList';
import {
  AttributeValue,
  type ValueDialogState,
} from '../../../../components/AttributeList/AttributeValue';
import { ExpandedValueDialog } from '../../../../components/AttributeList/ExpandedValueDialog';
import { auroraColors, neutralColors } from '../../../../theme/colors';
import { fontFamilies } from '../../../../theme/typography';
import { USD_FORMATTER, formatTimestamp, formatTokens } from '../sessionsFormat';

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

// Model label + accent-dot hue. Opus (priciest tier) → primary violet, Sonnet →
// cyan, Haiku (and anything unknown) → muted. Keyed on the leading token so
// "claude-sonnet-4-5" and "sonnet" both resolve.
const MODEL_META: Record<string, { label: string; dot: string }> = {
  opus: { label: 'Opus', dot: 'primary.main' },
  sonnet: { label: 'Sonnet', dot: auroraColors.cyanBright },
  haiku: { label: 'Haiku', dot: 'text.disabled' },
};

const modelMeta = (model: string | null | undefined) => {
  if (!model) {
    return null;
  }
  const key = model.toLowerCase();
  const hit = Object.keys(MODEL_META).find((modelKey) => key.includes(modelKey));
  return hit ? { ...MODEL_META[hit], raw: model } : { label: model, dot: 'text.disabled', raw: model };
};

const ModelChip = ({ model }: { model: string | null | undefined }) => {
  const meta = modelMeta(model);
  if (!meta) {
    return null;
  }
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
        color: 'text.secondary',
        bgcolor: (t) => alpha(t.palette.text.primary, t.palette.mode === 'dark' ? 0.08 : 0.06),
        whiteSpace: 'nowrap',
      }}
    >
      <Box component="span" sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: meta.dot }} />
      {meta.label}
    </Box>
  );
};

const ToolChips = ({ tools }: { tools: { name: string; count: number }[] | null | undefined }) => {
  if (!tools || tools.length === 0) {
    return (
      <Box sx={{ display: 'flex', mt: 0.25 }}>
        <Box component="span" sx={{ fontSize: 11.5, fontStyle: 'italic', color: 'text.disabled' }}>
          No tool calls
        </Box>
      </Box>
    );
  }
  const shown = tools.slice(0, 5);
  const overflow = tools.length - shown.length;
  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mt: 0.25 }}>
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
            color: 'text.primary',
            bgcolor: (t) => alpha(t.palette.text.primary, 0.06),
          }}
        >
          {tool.name}
          {tool.count > 1 ? (
            <Box component="b" sx={{ fontWeight: 700, fontSize: 10.5, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
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

interface PromptTimelinePanelProps {
  // Displayed in the panel header so it's clear which session this timeline
  // belongs to (matches the row it was expanded from).
  sessionId?: string;
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
export const TokenBreakdownTitle = ({ tokens }: { tokens: SessionTokenBreakdown }) => {
  const working = tokens.input + tokens.output + tokens.cacheCreation;
  const total = working + tokens.cacheRead;
  const rows: [string, number][] = [
    ['Input', tokens.input],
    ['Output', tokens.output],
    ['Cache creation', tokens.cacheCreation],
  ];
  return (
    <Box sx={{ minWidth: 168 }}>
      <Box sx={{ fontFamily: fontFamilies.display, fontSize: 9.5, fontWeight: 700, letterSpacing: '1px', textTransform: 'uppercase', color: 'text.disabled', mb: 0.875 }}>
        Token usage
      </Box>
      {rows.map(([label, value]) => (
        <Box key={label} sx={{ display: 'flex', justifyContent: 'space-between', gap: 1.75, fontSize: 11.5, lineHeight: 1.85 }}>
          <Box component="span" sx={{ color: 'text.secondary', fontWeight: 500 }}>{label}</Box>
          <Box component="span" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600, color: 'text.primary' }}>
            {NUM_FORMATTER.format(value)}
          </Box>
        </Box>
      ))}
      {/* Working subtotal — input + output + cache creation (the fresh/billed-high tokens) */}
      <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 1.75, mt: 0.625, pt: 0.75, borderTop: '1px dashed', borderColor: 'divider', fontSize: 11.5, lineHeight: 1.85 }}>
        <Box component="span" sx={{ color: 'text.primary', fontWeight: 600 }}>Working</Box>
        <Box component="span" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 700, color: 'text.primary' }}>
          {NUM_FORMATTER.format(working)}
        </Box>
      </Box>
      {/* Cache read — muted; typically dwarfs the working tokens */}
      <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 1.75, fontSize: 11.5, lineHeight: 1.85 }}>
        <Box component="span" sx={{ color: 'text.secondary', fontWeight: 500 }}>Cache read</Box>
        <Box component="span" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600, color: 'text.secondary' }}>
          {NUM_FORMATTER.format(tokens.cacheRead)}
        </Box>
      </Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 1.75, mt: 0.75, pt: 0.875, borderTop: 1, borderColor: 'divider', fontSize: 11.5 }}>
        <Box component="span" sx={{ fontWeight: 700, color: 'text.primary' }}>Total</Box>
        <Box component="span" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 700, color: 'text.primary' }}>
          {NUM_FORMATTER.format(total)}
        </Box>
      </Box>
    </Box>
  );
};

// Styled tooltip wrapper: solid surface panel with border + soft shadow, matching
// the mockup (MUI's default tooltip is a translucent dark chip — wrong here).
// Shared by the per-turn TokenUsage and the grid's Tokens cell.
export const TokenBreakdownTooltip = ({ tokens, children }: { tokens: SessionTokenBreakdown; children: ReactElement }) => (
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
// Tokens cell.
export const TokenUsage = ({ tokens }: { tokens: SessionTokenBreakdown | null | undefined }) => {
  if (!tokens) {
    return null;
  }
  const total = tokens.input + tokens.output + tokens.cacheCreation + tokens.cacheRead;
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
          '&::before': { content: '""', width: 3, height: 3, borderRadius: '50%', bgcolor: 'text.disabled' },
        }}
      >
        <Box component="b" sx={{ fontWeight: 700, color: 'text.primary' }}>{formatTokens(total)}</Box>
        &nbsp;tokens
      </Box>
    </TokenBreakdownTooltip>
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
const PromptTimelinePanel = ({ sessionId, prompts, loading, error, windowStartMs, windowEndMs }: PromptTimelinePanelProps) => {
  const [expandedValue, setExpandedValue] = useState<ValueDialogState | null>(null);

  const panelSx = {
    px: 2.5,
    py: 2.25,
    maxHeight: 340,
    overflowY: 'auto',
    background: (t: Theme) =>
      `radial-gradient(600px 200px at 3% 0%, ${alpha(
        t.palette.primary.main,
        t.palette.mode === 'dark' ? 0.12 : 0.07,
      )}, transparent 70%), ${alpha(
        t.palette.text.primary,
        t.palette.mode === 'dark' ? 0.03 : 0.025,
      )}`,
    boxShadow: (t: Theme) => `inset 0 1px 0 ${t.palette.divider}`,
  } as const;

  if (loading) {
    return (
      <Box sx={panelSx}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, color: 'text.secondary' }}>
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
        <Box component="svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} sx={{ width: 14, height: 14, color: 'primary.main' }}>
          <path d="M12 8v4l3 2" />
          <circle cx="12" cy="12" r="9" />
        </Box>
        Prompt timeline
        <Box component="span" sx={{ color: 'text.disabled', fontWeight: 600, letterSpacing: '0.5px' }}>
          {`${prompts.length} prompt${prompts.length === 1 ? '' : 's'}`}
        </Box>
        {sessionId ? (
          <Box
            component="span"
            sx={{
              ml: 'auto',
              fontFamily: fontFamilies.mono,
              fontSize: 11,
              fontWeight: 500,
              letterSpacing: 0,
              textTransform: 'none',
              color: 'text.disabled',
            }}
          >
            Session{' '}
            <Box component="b" sx={{ color: 'text.secondary', fontWeight: 600 }}>{sessionId}</Box>
          </Box>
        ) : null}
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
            background: (t) => `linear-gradient(180deg, ${alpha(t.palette.primary.main, 0.4)}, transparent)`,
          },
        }}
      >
        {prompts.map((turn, index) => {
          const turnMs = turn.timestamp ? new Date(turn.timestamp).getTime() : NaN;
          const inWindow =
            windowStartMs == null || windowEndMs == null || Number.isNaN(turnMs)
              ? true
              : turnMs >= windowStartMs && turnMs <= windowEndMs;
          const previousTurn = prompts[index - 1];
          const previousTurnMs = previousTurn?.timestamp ? new Date(previousTurn.timestamp).getTime() : NaN;
          const previousTurnInWindow =
            windowStartMs == null || windowEndMs == null || Number.isNaN(previousTurnMs)
              ? true
              : previousTurnMs >= windowStartMs && previousTurnMs <= windowEndMs;
          let boundary: string | null = null;
          if (index > 0 && !previousTurnInWindow && inWindow) {
            boundary = 'selected window starts';
          } else if (index > 0 && previousTurnInWindow && !inWindow) {
            boundary = 'selected window ends';
          }
          return (
          <Fragment key={`${turn.timestamp}-${index}`}>
            {boundary ? (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, my: 0.25, '&::before, &::after': { content: '""', flex: 1, height: '1px', bgcolor: 'divider' } }}>
                <Box component="span" sx={{ fontFamily: fontFamilies.display, fontSize: 9.5, fontWeight: 700, letterSpacing: '1.2px', textTransform: 'uppercase', color: 'text.disabled', whiteSpace: 'nowrap' }}>
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
              '&:hover': { borderColor: (t) => alpha(t.palette.primary.main, 0.32) },
              '&::before': {
                content: '""',
                position: 'absolute',
                left: '-21px',
                top: '16px',
                width: 10,
                height: 10,
                borderRadius: '50%',
                bgcolor: 'primary.main',
                boxShadow: (t) => `0 0 0 4px ${t.palette.background.default}, 0 0 0 5px ${alpha(t.palette.primary.main, 0.32)}`,
              },
            }}
          >
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1.5 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.125, minWidth: 0 }}>
                <Box
                  component="span"
                  sx={{ fontFamily: fontFamilies.mono, fontSize: 11, fontWeight: 500, color: 'text.disabled', whiteSpace: 'nowrap', letterSpacing: '-0.2px' }}
                >
                  {formatPromptTimestamp(turn.timestamp)}
                </Box>
                <ModelChip model={turn.model} />
                {turn.costUsd != null ? (
                  <Box
                    component="span"
                    sx={{ fontFamily: fontFamilies.display, fontWeight: 700, fontSize: 12.5, color: 'text.primary', fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.2px' }}
                  >
                    {USD_FORMATTER.format(turn.costUsd)}
                  </Box>
                ) : null}
                <TokenUsage tokens={turn.tokens} />
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
                    boxShadow: (t) => `inset 0 0 0 1px ${alpha(t.palette.primary.main, 0.32)}`,
                    '&:hover': { bgcolor: (t) => alpha(t.palette.primary.main, 0.22) },
                  }}
                >
                  View trace
                  <ArrowForwardIcon sx={{ fontSize: 12 }} />
                </Box>
              ) : null}
            </Box>

            <Box sx={{ fontSize: 13, lineHeight: 1.55, color: turn.prompt == null ? 'text.disabled' : 'text.primary', fontStyle: turn.prompt == null ? 'italic' : 'normal' }}>
              {turn.prompt == null ? (
                '(prompt text not captured)'
              ) : (
                <AttributeValue
                  attrKey={formatTimestamp(turn.timestamp)}
                  value={turn.prompt}
                  truncate
                  inlineExpand={false}
                  onExpand={setExpandedValue}
                />
              )}
            </Box>

            <ToolChips tools={turn.tools} />
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
