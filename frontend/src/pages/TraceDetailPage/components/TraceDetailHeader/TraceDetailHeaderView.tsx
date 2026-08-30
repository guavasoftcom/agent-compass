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
import { Link as RouterLink } from 'react-router-dom';
import { Box, Tooltip, Typography } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import { formatDuration, formatUsd } from '../../../TracesPage/tracesApi';
import { spanColor } from '../../../TracesPage/components/traceColors';
import SummaryStrip, { type OpGroup, type SummaryItem } from './SummaryStrip';
import IdentityPill from './IdentityPill';
import { fontFamilies } from '../../../../theme/typography';
import type { TokenBreakdown } from '../../../TracesPage/tokenBreakdown';

export interface TraceDetailHeaderViewProps {
  traceId: string;
  sessionId: string | null;
  rootName: string;
  earliestStartMs: number;
  totalMs: number;
  errorCount: number;
  spanCount: number;
  serviceLabels: string[];
  tokenBreakdown: TokenBreakdown;
  modelCallCount: number;
  toolCallCount: number;
  maximumDepth: number;
  totalCostUsd: number;
  // Portion of totalCostUsd billed after this trace's own root span closed —
  // e.g. a fire-and-forget subagent dispatch that kept issuing requests after
  // the turn that launched it ended. 0 hides the Cost tile's info tooltip.
  backgroundCostUsd: number;
  // Time-by-operation self-time breakdown — see SummaryStrip's OpBreakdownCard.
  shownOperations: OpGroup[];
  opCount: number;
  // Aurora sync: the trace's first user prompt, shown as a header row inside
  // the summary panel above the KPI tiles. Requires a `firstUserPrompt` field
  // on TraceRow (populated from the root/first prompt-bearing span, mirroring
  // SessionSummaryRow.firstUserPrompt) — null/undefined hides the row.
  firstUserPrompt?: string | null;
}

// The breadcrumb carries copy-to-clipboard IdChips for the trace id, and the
// session id when the trace has one, right after the "Trace detail" h1 — see
// IdChip. The Overview panel's meta footer (SummaryStrip) is left with just
// Root span / Services / Started. What's left up here below the chips is the
// four at-a-glance KPI tiles, Cost leading and gradient-emphasized — Duration
// was dropped once Time by operation (below the tiles) started stating the
// trace's total self-time itself, so repeating it as its own tile was pure
// duplication. The old single "Tokens" tile became the richer Token
// composition card inside the panel.
const TraceDetailHeaderView = ({
  traceId,
  sessionId,
  rootName,
  earliestStartMs,
  totalMs,
  errorCount,
  spanCount,
  serviceLabels,
  tokenBreakdown,
  modelCallCount,
  toolCallCount,
  maximumDepth,
  totalCostUsd,
  backgroundCostUsd,
  shownOperations,
  opCount,
  firstUserPrompt,
}: TraceDetailHeaderViewProps) => {
  const durationLabel = formatDuration(totalMs * 1e6);
  const costLabel = formatUsd(totalCostUsd);
  const backgroundCostLabel = formatUsd(backgroundCostUsd);
  const startedAtLabel = new Date(earliestStartMs).toLocaleTimeString('en-US', {
    hour12: false,
  });
  const rootColor = spanColor(rootName);

  const summary: SummaryItem[] = [
    {
      label: 'Cost',
      monospace: true,
      emphasis: true,
      value:
        backgroundCostUsd > 0 ? (
          <>
            {costLabel}{' '}
            <Tooltip
              title={
                `Includes ${backgroundCostLabel} billed after this trace's own root span closed — ` +
                'e.g. a fire-and-forget subagent dispatch that kept issuing requests after the turn ' +
                'that launched it ended.'
              }
              arrow
            >
              <InfoOutlinedIcon
                sx={{ fontSize: 14, color: 'warning.main', cursor: 'help', verticalAlign: 'text-bottom' }}
              />
            </Tooltip>
          </>
        ) : (
          costLabel
        ),
      title: costLabel,
    },
    { label: 'Spans', value: spanCount, title: String(spanCount) },
    { label: 'Tool calls', value: toolCallCount, title: String(toolCallCount) },
    {
      label: 'Depth',
      value: (
        <>
          {maximumDepth}{' '}
          <Box
            component="small"
            sx={{
              fontFamily: fontFamilies.body,
              fontSize: 12,
              fontWeight: 500,
              color: 'text.secondary',
            }}
          >
            levels
          </Box>
        </>
      ),
      title: `${maximumDepth} levels`,
    },
    {
      label: 'Errors',
      value: (
        <Box
          component="span"
          sx={{ color: errorCount ? 'error.main' : 'inherit' }}
        >
          {errorCount || '—'}
        </Box>
      ),
      title: errorCount
        ? `${errorCount} error${errorCount === 1 ? '' : 's'}`
        : 'No errors',
    },
  ];

  return (
    <Box sx={{ flexShrink: 0 }}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          gap: 2,
          flexWrap: 'wrap',
        }}
      >
        <Box>
          <Typography
            sx={{
              typography: 'eyebrow',
              color: 'primary.main',
              mb: 0.75,
            }}
          >
            Observability
          </Typography>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1.4,
              flexWrap: 'wrap',
            }}
          >
            <Box
              component={RouterLink}
              to="/traces"
              sx={{
                fontFamily: fontFamilies.display,
                fontWeight: 700,
                fontSize: 26,
                letterSpacing: '-0.4px',
                color: 'text.secondary',
                textDecoration: 'none',
                transition: '.12s',
                '&:hover': { color: 'primary.main' },
              }}
            >
              Traces
            </Box>
            <ChevronRightIcon sx={{ color: 'text.disabled', fontSize: 24 }} />
            <Typography
              component="h1"
              sx={{
                fontFamily: fontFamilies.display,
                fontSize: 26,
                fontWeight: 800,
                letterSpacing: '-0.5px',
                color: 'text.primary',
              }}
            >
              Trace detail
            </Typography>
            <IdentityPill traceId={traceId} sessionId={sessionId} />
          </Box>
        </Box>
      </Box>
      <SummaryStrip
        items={summary}
        prompt={firstUserPrompt}
        tokenBreakdown={tokenBreakdown}
        modelCallCount={modelCallCount}
        totalCostUsd={totalCostUsd}
        shownOperations={shownOperations}
        opCount={opCount}
        totalMs={totalMs}
        rootName={rootName}
        rootColor={rootColor}
        serviceLabels={serviceLabels}
        startedAtLabel={startedAtLabel}
        durationLabel={durationLabel}
        spanCount={spanCount}
        toolCallCount={toolCallCount}
        errorCount={errorCount}
      />
    </Box>
  );
};

export default TraceDetailHeaderView;
