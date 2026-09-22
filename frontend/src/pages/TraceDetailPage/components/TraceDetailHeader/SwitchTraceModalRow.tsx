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
import { Box, alpha } from '@mui/material';
import { formatRelativeTime } from '../../../../lib/format';
import { formatTokens, formatUsd } from '../../../TracesPage/tracesApi';
import RunningIndicator from '../../../../components/RunningIndicator';
import PromptSummaryText from '../../../../components/PromptSummaryText';
import NestingConnector, {
  type NestingConnectorGeometry,
} from '../../../../components/NestingConnector';
import type { SwitchTraceRow } from './SwitchTraceModalView';

interface Props {
  row: SwitchTraceRow;
  isCurrent: boolean;
  onSelect: () => void;
  /** How many ancestor dispatchers sit above this row (0 = top-level). */
  depth: number;
  /**
   * For each ancestor depth, whether that ancestor still has a later sibling
   * to come after this row — the cue for whether the connector at that depth
   * draws a continuing vertical line below the elbow, or stops there. Length
   * equals `depth`; this row's own "am I the last child" is
   * `!railBelow[depth - 1]`.
   */
  railBelow: boolean[];
}

export const GRID_COLUMNS = '58px 1fr 64px 76px 72px';

// Pixel equivalent of the row's base horizontal padding (theme spacing unit
// is 8px, so `px: 2.5` === 20px) — the left edge every depth-0 row already
// renders at, and the base every deeper row's extra indent is added onto.
const BASE_INDENT_PX = 20;
// Per-depth indent step and the elbow/rail connector's own geometry. Kept as
// named constants rather than inlined so the elbow's `left` offset (anchored
// to the PARENT's indent band, one depth shallower than the row it belongs
// to) and the indent itself can't drift apart.
const INDENT_STEP_PX = 18;
const CONNECTOR_WIDTH_PX = 9;
const CONNECTOR_LEFT_OFFSET_PX = 4;

const indentPxForDepth = (depth: number): number => BASE_INDENT_PX + depth * INDENT_STEP_PX;

// Geometry config for the shared NestingConnector (components/NestingConnector)
// — the elbow/rail drawing itself is shared with the Sessions page's
// PromptTimelinePanel; `indentAppliedViaMargin: false` tells the shared
// component this row indents via `pl` (BASE_INDENT_PX + depth *
// INDENT_STEP_PX above) rather than shifting its own box with a margin, so
// its own left edge always sits at the shared depth-0 origin regardless of
// depth. `originOffsetPx` folds in both BASE_INDENT_PX and
// CONNECTOR_LEFT_OFFSET_PX since `indentPxForDepth(ancestorDepth) +
// CONNECTOR_LEFT_OFFSET_PX` was the previous inline formula for each
// segment's `left`.
const SWITCH_TRACE_CONNECTOR_GEOMETRY: NestingConnectorGeometry = {
  indentStepPx: INDENT_STEP_PX,
  indentAppliedViaMargin: false,
  originOffsetPx: BASE_INDENT_PX + CONNECTOR_LEFT_OFFSET_PX,
  elbow: { top: '0', height: '50%', width: CONNECTOR_WIDTH_PX, borderWidth: 1, borderRadius: 6 },
  immediateRail: { top: '50%', bottom: '0', borderWidth: 1 },
  ancestorRail: { top: '0', bottom: '0', borderWidth: 1 },
};

// Character budget before an ordinary prompt clamps in this row. The full
// text is still reachable via the row's `title` tooltip — this modal lists
// turns to jump to, not to read prompts in full, so there's no "view
// formatted" button/dialog here unlike the drawer's LongAttrValue.
const PROMPT_CLAMP = 180;

const clampPrompt = (prompt: string): string =>
  prompt.length > PROMPT_CLAMP ? `${prompt.slice(0, PROMPT_CLAMP).replace(/\s+$/, '')}…` : prompt;

// Sum of the turn's four-way token split, or null when the turn has none —
// distinct from 0, which would print "0 tok" instead of the row's "—".
const tokenTotalOf = (tokens: SwitchTraceRow['tokens']): number | null =>
  tokens ? tokens.input + tokens.output + tokens.cacheCreation + tokens.cacheRead : null;

// One row: time / prompt / cost / tokens / current flag. When nested under a
// background-dispatching turn (depth > 0), also renders an elbow/rail
// connector back to its ancestor(s) — see the connector-vs-selection-accent
// gotcha in this page's CLAUDE.md: the connector paints as separate,
// absolutely-positioned Box elements IN FRONT OF the current row's own inset
// box-shadow selection accent, never folded into it.
const SwitchTraceModalRow = ({ row, isCurrent, onSelect, depth, railBelow }: Props) => {
  const tokenTotal = tokenTotalOf(row.tokens ?? null);

  return (
    <Box
      onClick={isCurrent ? undefined : onSelect}
      sx={{
        position: 'relative',
        display: 'grid',
        gridTemplateColumns: GRID_COLUMNS,
        alignItems: 'center',
        gap: 1.5,
        pr: 2.5,
        pl: `${indentPxForDepth(depth)}px`,
        py: 1.1,
        borderBottom: 1,
        borderColor: 'divider',
        cursor: isCurrent ? 'default' : 'pointer',
        bgcolor: isCurrent
          ? (t) => alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.22 : 0.12)
          : 'transparent',
        boxShadow: isCurrent ? (t) => `inset 2px 0 0 ${t.palette.primary.main}` : 'none',
        '&:hover': isCurrent ? {} : { bgcolor: 'action.hover' },
        '&:last-of-type': { borderBottom: 'none' },
      }}
    >
      <NestingConnector depth={depth} railBelow={railBelow} geometry={SWITCH_TRACE_CONNECTOR_GEOMETRY} />
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <Box sx={{ typography: 'mono', fontSize: 10.5, color: 'text.disabled' }}>
          {formatRelativeTime(row.timestamp)}
        </Box>
        {row.inProgress ? (
          <RunningIndicator tooltip="This trace is still running. It updates automatically." ariaLabel="Trace still running" />
        ) : null}
      </Box>
      <Box
        sx={{
          fontSize: 13,
          color: 'text.primary',
          wordBreak: 'break-word',
        }}
      >
        <PromptSummaryText prompt={row.prompt} renderOrdinary={clampPrompt} />
      </Box>
      <Box
        sx={{
          typography: 'mono',
          fontSize: 11,
          fontWeight: 600,
          color: 'warning.main',
          textAlign: 'right',
        }}
      >
        {formatUsd(row.costUsd ?? 0)}
      </Box>
      <Box
        sx={{
          typography: 'mono',
          fontSize: 11,
          color: 'text.secondary',
          textAlign: 'right',
        }}
      >
        {tokenTotal === null ? '—' : `${formatTokens(tokenTotal)} tok`}
      </Box>
      <Box sx={{ textAlign: 'right' }}>
        {isCurrent ? (
          <Box
            component="span"
            sx={{
              typography: 'eyebrowSm',
              px: 0.9,
              py: 0.3,
              borderRadius: '5px',
              bgcolor: (t) => alpha(t.palette.primary.main, 0.14),
              color: 'primary.main',
            }}
          >
            current
          </Box>
        ) : null}
      </Box>
    </Box>
  );
};

export default SwitchTraceModalRow;
