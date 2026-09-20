/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
import { Box, Tooltip, alpha, useTheme } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { neutralColors, tokenFigureColor } from '../../../../theme/colors';
import type { SpanRow, LogRow } from '../../../../api';
import {
  formatDuration,
  formatTokens,
  formatUsd,
} from '../../../TracesPage/tracesApi';
import {
  cacheHitRatePercent,
  fullRateTokens,
  tokenBreakdownForSpan,
  type TokenBreakdown,
} from '../../../TracesPage/tokenBreakdown';
import { fontFamilies } from '../../../../theme/typography';
import { shortModelName } from '../../../../lib/format';
import type { ChipFamily } from '../../chipVisibility';
import { NEW_SPAN_HIGHLIGHT_MS } from '../../spanArrivalHighlight';

interface Props {
  span: SpanRow;
  depth: number;
  hasChildren: boolean;
  isCollapsed: boolean;
  isSelected: boolean;
  indexLabel: number | undefined;
  descendantErrorCount: number;
  // Cost attributable to this span, from costOfSelectedSpan. The view resolves
  // it because it owns the log buckets the llm_request figure comes from.
  costUsd: number;
  // True when costUsd is the stamped span_costs rollup (requests made under
  // this span) rather than the span's own call — drives the badge tooltip.
  isRollupCost: boolean;
  // Color this span's dispatched subagent was assigned (agentDispatch.ts), when it belongs to
  // one — undefined for a main-loop span. Tints the timeline bar and a quiet left-edge accent so
  // a dispatch's whole subtree reads as one thing scanning down the waterfall.
  agentColor?: string;
  // True for the NEW_SPAN_HIGHLIGHT_MS after this span arrived on a poll of a running trace: the
  // row flashes and fades back to its own background. Only the initial `0%` frame is given, so the
  // fade lands on whatever tint the row otherwise has (selected, agent wash, none).
  isNewlyArrived?: boolean;
  // Badge families the toolbar legend has muted — gates every badge below
  // except error/descendant-error, which name the row's status rather than an
  // optional figure and are never hidden.
  chipsOff: Set<ChipFamily>;
  // The `call N` badge is not one of the chipsOff families (it is the row's identity in the trace
  // analysis, never a toggleable figure), so a compact host with no analysis to cite from opts out
  // here instead. Defaults to shown.
  showCallNumber?: boolean;
  // Log rows associated with this span, used to detect skills executed.
  logs?: LogRow[];
  gridColumns: string;
  // Horizontal bar geometry (percent of the visible zoom window).
  left: number;
  right: number;
  width: number;
  onToggleCollapse: (spanId: string) => void;
  onSelect: (spanId: string) => void;
}

// Shared chip metrics. Every badge in the name column is the same height and
// radius; only the palette and font weight differ.
const spanChipSx = {
  ml: 0.9,
  display: 'inline-flex',
  alignItems: 'center',
  height: 17,
  px: 0.75,
  borderRadius: '5px',
  typography: 'mono',
  fontSize: 10,
  fontWeight: 600,
  flexShrink: 0,
} as const;

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

// The number this row carries in the call timeline the "Analyze trace" review is
// written against, so a finding reading "call 20 was an outlier" can be walked
// back to the call it is about. Deliberately distinct from the leading index
// badge beside it: that one counts every span in DFS order, while this counts
// only tool calls and model requests in trace order (the backend fills it — see
// TraceCallNumbering), so on any real trace the two numbers differ and only this
// one matches a citation. Rendered only on the spans that have one, which is
// what keeps it from reading as a second index on every row.
const SpanCallNumberBadge = ({ callNumber }: { callNumber?: number | null }) => {
  if (!callNumber) {
    return null;
  }
  return (
    <Tooltip
      arrow
      placement="top"
      title="Call number in the trace analysis timeline — a review citing this number means this row"
    >
      <Box
        component="span"
        sx={{
          ...spanChipSx,
          fontWeight: 500,
          color: 'text.disabled',
          border: 1,
          borderColor: 'divider',
        }}
      >
        {`call ${callNumber}`}
      </Box>
    </Tooltip>
  );
};

// Two token badges, not one. Cache read routinely runs 10-100x the other three
// counts, so a single combined total made every model row read as the same huge
// number and the figures a reader is actually deciding on (input, output, cache
// creation) disappeared inside it. The split is by rate, not by billed/free —
// cache read is billed too, at a tenth of the input rate.
const SpanFullRateBadge = ({ tokens }: { tokens: TokenBreakdown }) => {
  const fullRate = fullRateTokens(tokens);
  if (fullRate <= 0) {
    return null;
  }
  return (
    <Tooltip
      arrow
      placement="top"
      title={
        <Box sx={{ py: 0.5, typography: 'mono' }}>
          <Box sx={{ fontSize: 11.5, fontWeight: 700, mb: 0.3 }}>
            {`${formatTokens(fullRate)} at full rate`}
          </Box>
          {/* Cache read is deliberately absent: it has its own chip and card
              immediately beside this one, so listing it here repeated the same
              number and its 0.1x note a few pixels apart. */}
          <Box sx={tipGridSx}>
            <TipRow label="Input" value={tokens.input.toLocaleString()} />
            <TipRow label="Output" value={tokens.output.toLocaleString()} />
            <TipRow
              label="Cache creation"
              value={tokens.cacheCreate.toLocaleString()}
            />
          </Box>
        </Box>
      }
    >
      <Box
        component="span"
        sx={{
          ...spanChipSx,
          gap: 0.4,
          // Brand pink, not amber: amber is the cost hue, and a row carrying both
          // in the same color read as one number split in two.
          color: (t) => tokenFigureColor(t.palette.mode),
          bgcolor: (t) => alpha(tokenFigureColor(t.palette.mode), 0.16),
        }}
      >
        <Box
          component="svg"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={2}
          sx={{ width: 10, height: 10, opacity: 0.8 }}
        >
          <circle cx="12" cy="12" r="8" />
          <circle cx="12" cy="12" r="3" fill="currentColor" />
        </Box>
        {formatTokens(fullRate)}
      </Box>
    </Tooltip>
  );
};

// The quiet half of the pair: same geometry, neutral palette, so a row's loud
// figure stays the one that costs full rate.
const SpanCacheReadBadge = ({ tokens }: { tokens: TokenBreakdown }) => {
  if (tokens.cacheRead <= 0) {
    return null;
  }
  const hitRate = cacheHitRatePercent(tokens);
  return (
    <Tooltip
      arrow
      placement="top"
      title={
        <Box sx={{ py: 0.5, typography: 'mono' }}>
          <Box sx={{ fontSize: 11.5, fontWeight: 700, mb: 0.3 }}>
            {`${formatTokens(tokens.cacheRead)} cache read`}
          </Box>
          {hitRate != null ? (
            <Box sx={tipGridSx}>
              <TipRow label="Of cacheable tokens" value={`${hitRate}%`} />
            </Box>
          ) : null}
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
      <Box
        component="span"
        sx={{
          ...spanChipSx,
          gap: 0.4,
          color: 'text.disabled',
          bgcolor: 'action.hover',
        }}
      >
        <Box
          component="svg"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={2}
          sx={{ width: 10, height: 10, opacity: 0.75 }}
        >
          <path d="M3 12a9 9 0 0 1 15-6.7L21 8" />
          <path d="M21 3v5h-5" />
          <path d="M21 12a9 9 0 0 1-15 6.7L3 16" />
          <path d="M3 21v-5h5" />
        </Box>
        {formatTokens(tokens.cacheRead)}
      </Box>
    </Tooltip>
  );
};

// Real, billed cost (costOfSelectedSpan — see spanCost.ts) — not an estimate.
// Omitted for spans that neither were stamped with a request nor issued one,
// and for claude_code.interaction rows, which resolve to 0 there because their
// rollup is the whole turn (the header's Cost KPI).
//
// Two kinds of figure share this badge, and the tooltip is what tells them
// apart: on a stamped span — now only a tool.execution running a subagent — it
// covers every request made *under* that span, so a parent and its children can
// each carry a badge and the column does not sum to the trace total. On an
// llm_request row it is that one call's own spend.
const SpanCostBadge = ({
  costUsd,
  isRollupCost,
}: {
  costUsd: number;
  isRollupCost: boolean;
}) => {
  if (costUsd <= 0) {
    return null;
  }
  return (
    <Box
      component="span"
      title={
        isRollupCost
          ? 'Cost of the requests made under this span'
          : 'Cost of this model call'
      }
      sx={{
        ...spanChipSx,
        fontWeight: 700,
        color: 'warning.main',
        bgcolor: (t) => alpha(t.palette.warning.main, 0.16),
      }}
    >
      {formatUsd(costUsd)}
    </Box>
  );
};

// Extract skill name from logs with a skill_activated event. The event.name
// attribute is the short form ("skill_activated"); the log's own `body` field
// carries the dotted form ("claude_code.skill_activated") instead.
const extractSkillName = (logs?: LogRow[]): string | null => {
  if (!logs) {
    return null;
  }
  for (const log of logs) {
    const eventName = log.attributes?.['event.name'];
    if (eventName === 'skill_activated') {
      const skillName = log.attributes?.['skill.name'];
      if (skillName && typeof skillName === 'string') {
        return skillName;
      }
    }
  }
  return null;
};

// Whichever attribute carries what the tool was actually asked to do, in
// preference order. `full_command` first: on a Bash span it is the whole
// heredoc, where `command` is only its first line.
const TOOL_ARG_KEYS = [
  'full_command',
  'command',
  'file_path',
  'pattern',
  'query',
  'url',
] as const;

// A full_command runs to several hundred characters often enough (heredoc commit
// messages especially) that the card has to clamp. It clamps and points at the
// drawer, which owns the truncate-and-expand path for the whole string.
const TIP_COMMAND_MAX_CHARS = 300;

const attrToText = (value: unknown): string =>
  typeof value === 'string' ? value : JSON.stringify(value);

// The tool chip stops being a bare label: hovering it answers "asked to do
// what?" without opening the drawer, which is the question a reader scanning a
// column of `Bash` / `Read` rows actually has.
const SpanToolBadge = ({ span }: { span: SpanRow }) => {
  const attributes = span.attributes;
  const toolNameAttribute = attributes?.['tool_name'];
  const toolName =
    typeof toolNameAttribute === 'string' ? toolNameAttribute : '';
  if (!toolName) {
    return null;
  }
  const statusAttribute = attributes?.['tool.status'];
  const toolStatus = typeof statusAttribute === 'string' ? statusAttribute : '';
  // An Agent span's "what was it asked to do" is subagent_type, not any of the
  // TOOL_ARG_KEYS — those describe a shell/file/search tool's own arguments,
  // which an Agent span doesn't carry. subagent_type wins whenever it's
  // populated; every other tool falls back to the normal key search.
  const subagentTypeAttribute = attributes?.['subagent_type'];
  const hasSubagentType =
    subagentTypeAttribute !== undefined &&
    subagentTypeAttribute !== null &&
    String(subagentTypeAttribute) !== '';
  const argKey =
    toolName === 'Agent' && hasSubagentType
      ? 'subagent_type'
      : attributes
        ? TOOL_ARG_KEYS.find((key) => {
            const value = attributes[key];
            return (
              value !== undefined && value !== null && String(value) !== ''
            );
          })
        : undefined;
  const argText = argKey && attributes ? attrToText(attributes[argKey]) : '';
  const isClipped = argText.length > TIP_COMMAND_MAX_CHARS;
  return (
    <Tooltip
      arrow
      placement="top"
      slotProps={{ tooltip: { sx: { maxWidth: 420 } } }}
      title={
        <Box sx={{ py: 0.5, typography: 'mono' }}>
          <Box sx={{ fontSize: 11.5, fontWeight: 700, mb: 0.3 }}>
            {toolName}
            {toolStatus ? (
              <Box
                component="span"
                sx={{ ml: 0.75, fontSize: 10, opacity: 0.7 }}
              >
                {toolStatus}
              </Box>
            ) : null}
          </Box>
          {argKey ? (
            <>
              <Box sx={{ fontSize: 10, opacity: 0.7, mb: 0.4 }}>{argKey}</Box>
              <Box
                sx={{
                  fontSize: 11,
                  lineHeight: 1.5,
                  whiteSpace: 'pre-wrap',
                  overflowWrap: 'anywhere',
                  maxHeight: 150,
                  overflow: 'hidden',
                }}
              >
                {isClipped
                  ? `${argText.slice(0, TIP_COMMAND_MAX_CHARS).replace(/\s+$/, '')}\u2026`
                  : argText}
              </Box>
              {isClipped ? (
                <Box
                  sx={{
                    mt: 0.5,
                    fontFamily: fontFamilies.body,
                    fontSize: 10.5,
                    opacity: 0.75,
                  }}
                >
                  {`${argText.length.toLocaleString()} chars · open the span for the full command`}
                </Box>
              ) : null}
            </>
          ) : (
            <Box
              sx={{
                fontFamily: fontFamilies.body,
                fontSize: 10.5,
                opacity: 0.75,
              }}
            >
              no command recorded on this span
            </Box>
          )}
        </Box>
      }
    >
      <Box
        component="span"
        sx={{
          ...spanChipSx,
          color: 'info.main',
          bgcolor: (t) => alpha(t.palette.info.main, 0.15),
        }}
      >
        {toolName}
      </Box>
    </Tooltip>
  );
};

const SpanSkillBadge = ({ skillName }: { skillName: string | null }) => {
  if (!skillName) {
    return null;
  }
  return (
    <Tooltip arrow placement="top" title={skillName}>
      <Box
        component="span"
        sx={{
          ...spanChipSx,
          color: 'success.main',
          bgcolor: (t) => alpha(t.palette.success.main, 0.15),
        }}
      >
        Skill
      </Box>
    </Tooltip>
  );
};

const SpanWaterfallRow = ({
  span,
  depth,
  hasChildren,
  isCollapsed,
  isSelected,
  indexLabel,
  descendantErrorCount,
  costUsd,
  isRollupCost,
  agentColor,
  isNewlyArrived = false,
  chipsOff,
  showCallNumber = true,
  logs,
  gridColumns,
  left,
  right,
  width,
  onToggleCollapse,
  onSelect,
}: Props) => {
  const theme = useTheme();
  const tokens = tokenBreakdownForSpan(span);
  const skillName = extractSkillName(logs);
  // Which model an llm_request row actually went to — the tool chip's counterpart
  // for model spans, so a trace that switched models mid-run reads off the tree
  // instead of one drawer open at a time. `model` and `gen_ai.request.model` are
  // both present on every real llm_request span and always agree; the fallback is
  // for the OTel-canonical key outliving the vendor one. Non-model spans (tool,
  // session, interaction) carry neither and render no badge.
  const modelAttribute =
    span.attributes?.['model'] ?? span.attributes?.['gen_ai.request.model'];
  const modelName = typeof modelAttribute === 'string' ? modelAttribute : '';
  // Effort rides in the same pill rather than its own, so an llm_request row
  // gains one badge here, not two — the row is already carrying tokens and cost.
  // It is null on ~2% of recent calls (higher in older traces), and that means
  // "not recorded": the pill then shows the model alone rather than implying a
  // default level. See the span_efforts view (V15) for why it is a join and not
  // a span attribute.
  const effort = span.effort ?? '';
  const isError = span.statusCode === 'error';
  // Error always wins the bar's color — one hue means one thing on this row, and error is never
  // shared with anything else (see this page's CLAUDE.md). A dispatched subagent's own color is
  // the next priority, so its whole subtree reads as one thing scanning down the waterfall; the
  // plain primary gradient is the default for a main-loop span.
  const barBackground = isError
    ? theme.palette.error.main
    : agentColor
      ? `linear-gradient(90deg, ${agentColor}, ${alpha(agentColor, 0.6)})`
      : `linear-gradient(90deg, ${theme.palette.primary.main}, ${theme.palette.primary.light})`;

  // Duration-label placement, kept inside the track so it can never force a
  // horizontal scrollbar: after the bar when it ends with room to spare, just
  // before the bar's start when the bar sits to the right, and — for a
  // full-width bar like the root span, where neither side has room — tucked
  // inside the bar's right end with light text so it reads as a deliberate
  // on-bar label rather than overlapping in the dim body color.
  const labelInsideBar = right >= 85;
  const durationLabelStyle = labelInsideBar
    ? { right: `calc(${100 - right}% + 8px)`, color: neutralColors.white }
    : { left: `calc(${right}% + 6px)` };
  return (
    <Box
      data-span={span.spanId}
      onClick={() => onSelect(span.spanId)}
      sx={{
        display: 'grid',
        gridTemplateColumns: gridColumns,
        alignItems: 'center',
        height: 30,
        borderBottom: 1,
        borderColor: 'divider',
        cursor: 'pointer',
        opacity: 1,
        // The selected tint always wins the row background — it's a different property from the
        // bar's own color, so a dispatch's quiet wash and the selected highlight never compete
        // for the same slot. Unselected, a dispatched span gets a faint wash of its own agent
        // color: independent of the timeline bar, so an errored span (bar forced to red) still
        // visibly belongs to its subagent's rows above and below it. A dispatched span's
        // selected/hover tints reuse that same agent color rather than the generic
        // primary/action.hover ones — just brighter, so hovering or selecting a row still reads
        // as "this subagent" instead of switching to an unrelated highlight color. The left-edge
        // accent follows suit: agentColor when the selected row belongs to a dispatch, primary.main
        // otherwise.
        bgcolor: isSelected
          ? agentColor
            ? (t) => alpha(agentColor, t.palette.mode === 'dark' ? 0.4 : 0.28)
            : (t) =>
                alpha(
                  t.palette.primary.main,
                  t.palette.mode === 'dark' ? 0.22 : 0.12,
                )
          : agentColor
            ? (t) => alpha(agentColor, t.palette.mode === 'dark' ? 0.16 : 0.08)
            : 'transparent',
        boxShadow: isSelected
          ? agentColor
            ? `inset 2px 0 0 ${agentColor}`
            : (t) => `inset 2px 0 0 ${t.palette.primary.main}`
          : 'none',
        transition: 'opacity .14s, background .1s',
        ...(isNewlyArrived
          ? {
              animation: `spanArrivalFlash ${NEW_SPAN_HIGHLIGHT_MS}ms ease-out`,
              '@keyframes spanArrivalFlash': {
                '0%': {
                  backgroundColor: alpha(
                    theme.palette.primary.main,
                    theme.palette.mode === 'dark' ? 0.45 : 0.3,
                  ),
                },
              },
            }
          : {}),
        '&:hover': {
          bgcolor: agentColor
            ? (t) => alpha(agentColor, t.palette.mode === 'dark' ? 0.28 : 0.18)
            : 'action.hover',
        },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          minWidth: 0,
          pl: `${10 + depth * 15}px`,
        }}
      >
        {hasChildren ? (
          <Box
            component="span"
            onClick={(e) => {
              e.stopPropagation();
              onToggleCollapse(span.spanId);
            }}
            sx={{
              display: 'grid',
              placeItems: 'center',
              width: 18,
              height: 18,
              color: 'text.disabled',
              transform: isCollapsed ? 'none' : 'rotate(90deg)',
              transition: 'transform .12s',
              '& svg': { fontSize: 15 },
            }}
          >
            <ChevronRightIcon />
          </Box>
        ) : (
          <Box sx={{ width: 18, display: 'grid', placeItems: 'center' }}>
            <Box
              sx={{
                width: 5,
                height: 5,
                borderRadius: '50%',
                bgcolor: 'text.disabled',
                opacity: 0.5,
              }}
            />
          </Box>
        )}
        <Box
          component="span"
          sx={{
            display: 'inline-grid',
            placeItems: 'center',
            minWidth: 19,
            height: 17,
            px: 0.6,
            mr: 0.9,
            borderRadius: '5px',
            border: 1,
            borderColor: 'divider',
            typography: 'mono',
            fontSize: 10,
            fontWeight: 600,
            color: 'text.secondary',
            flexShrink: 0,
          }}
        >
          {indexLabel}
        </Box>
        <Box
          component="span"
          sx={{
            typography: 'mono',
            fontSize: 12.5,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
          title={span.name}
        >
          {span.name}
        </Box>
        {/* Chip order is deliberate: the call number first (it is the row's
            identity in the analysis, not a figure), then the two token figures
            (they are on every model row, so a stable position lets the eye scan
            the column), then cost, then the identity pills. Each is gated on the
            toolbar legend's per-family visibility toggle except the call number
            and error/descendant-error, which name the row rather than report an
            optional figure and are never hidden. */}
        {showCallNumber ? <SpanCallNumberBadge callNumber={span.callNumber} /> : null}
        {!chipsOff.has('tok') ? <SpanFullRateBadge tokens={tokens} /> : null}
        {!chipsOff.has('cr') ? <SpanCacheReadBadge tokens={tokens} /> : null}
        {!chipsOff.has('cost') ? (
          <SpanCostBadge costUsd={costUsd} isRollupCost={isRollupCost} />
        ) : null}
        {modelName && !chipsOff.has('mdl') ? (
          <Box
            component="span"
            title={effort ? `${modelName} · ${effort} effort` : modelName}
            sx={{
              ...spanChipSx,
              gap: 0.4,
              color: 'primary.main',
              bgcolor: (t) => alpha(t.palette.primary.main, 0.15),
              whiteSpace: 'nowrap',
            }}
          >
            {shortModelName(modelName)}
            {effort ? (
              <Box component="span" sx={{ fontWeight: 500, opacity: 0.75 }}>
                {`· ${effort}`}
              </Box>
            ) : null}
          </Box>
        ) : null}
        {!chipsOff.has('tool') ? <SpanToolBadge span={span} /> : null}
        <SpanSkillBadge skillName={skillName} />
        {isError ? (
          <Box
            component="span"
            sx={{
              ml: 0.9,
              px: 0.75,
              py: 0.1,
              borderRadius: '5px',
              color: 'error.main',
              bgcolor: (t) => alpha(t.palette.error.main, 0.14),
              fontFamily: fontFamilies.display,
              fontSize: 9.5,
              fontWeight: 700,
              flexShrink: 0,
            }}
          >
            error
          </Box>
        ) : null}
        {descendantErrorCount > 0 ? (
          <Box
            component="span"
            sx={{
              ml: 0.9,
              px: 0.75,
              py: 0.1,
              borderRadius: '5px',
              color: 'warning.main',
              bgcolor: (t) => alpha(t.palette.warning.main, 0.14),
              fontFamily: fontFamilies.display,
              fontSize: 9.5,
              fontWeight: 700,
              flexShrink: 0,
            }}
          >
            +{descendantErrorCount} below
          </Box>
        ) : null}
      </Box>
      <Box sx={{ position: 'relative', height: '100%', mx: 1.5 }}>
        {width > 0 && right > 0 && left < 100 ? (
          <>
            <Box
              sx={{
                position: 'absolute',
                top: '50%',
                transform: 'translateY(-50%)',
                height: 13,
                borderRadius: '4px',
                background: barBackground,
                opacity: isSelected ? 1 : 0.82,
                left: `${left}%`,
                width: `${width}%`,
                minWidth: 3,
              }}
            />
            <Box
              component="span"
              sx={{
                position: 'absolute',
                top: '50%',
                transform: 'translateY(-50%)',
                typography: 'mono',
                fontSize: 10,
                color: 'text.secondary',
                whiteSpace: 'nowrap',
                pointerEvents: 'none',
                ...durationLabelStyle,
              }}
            >
              {formatDuration(span.durationNanos)}
            </Box>
          </>
        ) : null}
      </Box>
    </Box>
  );
};

export default SpanWaterfallRow;
