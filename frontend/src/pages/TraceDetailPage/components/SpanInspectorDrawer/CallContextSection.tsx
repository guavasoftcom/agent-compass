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
import { useMemo } from 'react';
import { Box, Typography } from '@mui/material';
import type { LogRow, SpanRow } from '../../../../api';
import {
  buildSpanRelations,
  sortedToolCallsOf,
  type RelatedCall,
  type SpanRelations,
} from '../../spanRelations';
import { buildSpanCallFacts, hasAnyFact, type SpanCallFacts } from '../../spanCallFacts';
import { radii } from '../../../../theme/theme';

interface CallContextSectionProps {
  span: SpanRow;
  // The whole trace, not the currently rendered rows: the relations are found by scanning for
  // related calls, and a zoomed or collapsed subset would silently shrink what it can find.
  spans: SpanRow[];
  // Keyed by span id, as the page already holds it. The tool logs this reads sit on the call's
  // CHILD spans -- see spanCallFacts.ts for why they are reachable and how they are re-checked.
  logsBySpanId: Map<string, LogRow[]>;
  // Selects and scrolls to a span, expanding whatever was collapsed over it and widening the zoom
  // if it sits outside the current window — TraceDetailPageView#revealSpan. Without those two
  // guards a related call inside a folded subagent dispatch would link to a row that is not
  // rendered, opening the drawer onto a waterfall showing nothing.
  onRevealSpan: (spanId: string) => void;
}

// What is already known about the call. The description is the agent's own statement of intent and
// the single most direct answer to this section's question; it is otherwise buried inside the
// tool_result log's tool_input JSON, where nothing surfaces it.
const CallFactList = ({ facts }: { facts: SpanCallFacts }) => {
  const rows: string[] = [];
  if (facts.succeeded === false || facts.errorText !== null) {
    rows.push(facts.errorText === null ? 'Failed' : `Failed: ${facts.errorText}`);
  } else if (facts.succeeded === true) {
    rows.push(
      facts.resultSizeBytes === null
        ? 'Succeeded'
        : `Succeeded — returned ${facts.resultSizeBytes.toLocaleString()} bytes`,
    );
  }
  if (facts.decision !== null) {
    // 'config' means pre-authorized, so nobody was interrupted -- worth telling apart from a
    // decision the reader was actually stopped for.
    rows.push(
      facts.decisionSource === 'config'
        ? `Permission: ${facts.decision} (pre-authorized)`
        : `Permission: ${facts.decision}${facts.decisionSource === null ? '' : ` (${facts.decisionSource})`}`,
    );
  }
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.375 }}>
      {facts.description !== null ? (
        <Typography sx={{ fontSize: 12, color: 'text.primary' }}>{facts.description}</Typography>
      ) : null}
      {rows.map((row) => (
        <Typography
          key={row}
          sx={{ typography: 'mono', fontSize: 11, color: 'text.secondary', wordBreak: 'break-word' }}
        >
          {row}
        </Typography>
      ))}
    </Box>
  );
};

/**
 * One relation, with its call number as a link to the row it names.
 *
 * A real <button> rather than a styled span: this moves focus and scroll position in the waterfall,
 * so it has to be reachable by Tab and activate on Enter/Space like any other control. Rendered
 * inline inside the sentence, so the line still reads as prose.
 */
const RelationRow = ({
  call,
  label,
  direction,
  onRevealSpan,
}: {
  call: RelatedCall;
  label: string;
  direction: 'earlier' | 'later';
  onRevealSpan: (spanId: string) => void;
}) => {
  const distance = `${call.callsAway} ${call.callsAway === 1 ? 'call' : 'calls'} ${direction}`;
  return (
    <Typography
      sx={{ typography: 'mono', fontSize: 11, color: 'text.secondary', wordBreak: 'break-word' }}
    >
      {`${label}: ${call.toolName}`}
      {call.callNumber === null ? null : (
        <>
          {' ('}
          <Box
            component="button"
            type="button"
            onClick={() => onRevealSpan(call.spanId)}
            title="Go to this call in the waterfall"
            sx={{
              font: 'inherit',
              color: 'primary.main',
              background: 'none',
              border: 0,
              p: 0,
              cursor: 'pointer',
              textDecoration: 'underline',
              textUnderlineOffset: 2,
              '&:hover': { color: 'primary.dark' },
            }}
          >
            {`call ${call.callNumber}`}
          </Box>
          {')'}
        </>
      )}
      {` — ${distance}`}
    </Typography>
  );
};

// Every relation the section states, flattened in the order they are rendered. Shared with the
// emptiness check below, so "is there anything to show" can't drift from what actually shows.
const relationRowsOf = (
  relations: SpanRelations,
): { call: RelatedCall; label: string; direction: 'earlier' | 'later' }[] => [
  ...relations.sameFileEarlier.map((call) => ({ call, label: 'Same file', direction: 'earlier' as const })),
  ...relations.sameFileLater.map((call) => ({ call, label: 'Same file', direction: 'later' as const })),
];

// The relations found for this span. "Read 15 calls earlier" is exactly the fact a reader cannot
// see on screen: the waterfall shows adjacency, not relatedness, and the related row may be far
// off, folded inside a dispatch, or outside the zoom.
const RelationList = ({
  relations,
  onRevealSpan,
}: {
  relations: ReturnType<typeof buildSpanRelations>;
  onRevealSpan: (spanId: string) => void;
}) => {
  if (relations === null) {
    return null;
  }
  const rows = relationRowsOf(relations);
  if (rows.length === 0) {
    return null;
  }
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.375 }}>
      {rows.map((row) => (
        <RelationRow
          key={`${row.label}-${row.direction}-${row.call.spanId}`}
          call={row.call}
          label={row.label}
          direction={row.direction}
          onRevealSpan={onRevealSpan}
        />
      ))}
    </Box>
  );
};

/**
 * What the selected call was for, entirely from facts already recorded — its own stated intent, its
 * outcome, its permission decision, and the calls related to it.
 *
 * Nothing here asks a model anything. An earlier revision put an on-demand Ollama analysis in this
 * slot; measured against real spans it returned the computed summary verbatim on a third of them,
 * restated the relation list below on another third, and fabricated once, so it was removed rather
 * than tuned further. What is left is what was answering the question all along, and it renders
 * instantly with nothing to validate.
 */
const CallContextSection = ({ span, spans, logsBySpanId, onRevealSpan }: CallContextSectionProps) => {
  // Identical across every span selection for a given trace, so this is memoized on `spans` alone
  // rather than recomputed (filtered + sorted) inside buildSpanRelations on every selection change.
  const sortedToolCalls = useMemo(() => sortedToolCallsOf(spans), [spans]);
  const relations = useMemo(
    () => buildSpanRelations(span, spans, sortedToolCalls),
    [span, spans, sortedToolCalls],
  );
  const facts = useMemo(
    () => buildSpanCallFacts(span, spans, logsBySpanId),
    [span, spans, logsBySpanId],
  );

  // A span that is not a tool call has no call context, so the section renders nothing at all
  // rather than a placeholder telling the reader to select a different row.
  if (relations === null) {
    return null;
  }

  // Since the section carries no title of its own any more, a call with neither facts nor
  // relations has nothing left to put in the box -- render no box rather than an empty frame.
  const hasFacts = facts !== null && hasAnyFact(facts);
  if (!hasFacts && relationRowsOf(relations).length === 0) {
    return null;
  }

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        gap: 1,
        px: 1.4,
        py: 1.2,
        border: 1,
        borderColor: 'divider',
        borderRadius: radii.sm,
      }}
    >
      {hasFacts ? <CallFactList facts={facts} /> : null}
      <RelationList relations={relations} onRevealSpan={onRevealSpan} />
    </Box>
  );
};

export default CallContextSection;
