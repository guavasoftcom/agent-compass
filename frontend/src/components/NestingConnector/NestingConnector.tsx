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
import { Fragment } from 'react';
import { Box } from '@mui/material';

// One segment's visual geometry — an elbow (the corner joining a nested
// row/card to its immediate parent) or a straight rail continuation (the
// unbroken vertical line for an ancestor that still has more rows coming).
export interface ConnectorSegmentGeometry {
  top: string;
  bottom?: string;
  height?: string;
  borderWidth: number;
}

export interface NestingConnectorGeometry {
  /** Pixels between one depth level's indent band and the next. */
  indentStepPx: number;
  /**
   * True when the CALLER indents a nested row/card by shifting its whole box
   * right via `margin` (so this box's own left edge sits `depth *
   * indentStepPx` right of the shared depth-0 origin, and the connector,
   * drawn in that box's own local coordinate space, must subtract that shift
   * back out). False when the caller indents via `padding` instead, leaving
   * the box's own left edge AT the shared origin regardless of depth. This
   * is the one thing the two current callers (`PromptTimelinePanel`'s
   * margin-shifted cards vs. `SwitchTraceModalRow`'s padded table rows) do
   * differently on purpose — see each page's own CLAUDE.md for why a card's
   * border box needs the margin shift where a full-width table row doesn't.
   */
  indentAppliedViaMargin: boolean;
  /** Constant added to `ancestorDepth * indentStepPx` (before the margin
   * compensation above, if any) — where a depth-0 ancestor's own rail/elbow
   * sits, plus a few px of fine visual offset. */
  originOffsetPx: number;
  /** The corner connecting this row/card to its immediate parent. */
  elbow: { top: string; height: string; width: number; borderWidth: number; borderRadius: number };
  /** Continuation below the elbow, drawn only when the immediate parent
   * still has a later sibling of its own still to come. */
  immediateRail: { top: string; bottom: string; borderWidth: number };
  /** Unbroken vertical line for a shallower ancestor (grandparent or above)
   * that still has more rows/cards to come, so a deep row's whole ancestor
   * chain reads as continuous lines, not just its immediate parent's. */
  ancestorRail: { top: string; bottom: string; borderWidth: number };
}

interface NestingConnectorProps {
  /** How many ancestor dispatchers sit above this row (0 = top-level, no
   * connector rendered). */
  depth: number;
  /**
   * For each ancestor depth, whether that ancestor still has a later
   * sibling to come after this row — the cue for whether the connector at
   * that depth draws a continuing line below the elbow, or stops there.
   * Length equals `depth`; index `i` is the ancestor whose own depth in the
   * tree is `i` (produced by `lib/nestDispatchedRows.ts`).
   */
  railBelow: boolean[];
  geometry: NestingConnectorGeometry;
}

/**
 * Shared elbow/rail connector for a nested row or card in a dispatcher-child
 * tree — the per-depth indent + connector visual that `PromptTimelinePanel`
 * (Sessions page) and `SwitchTraceModalRow` (Trace Detail page) used to
 * reimplement independently. Renders nothing at `depth === 0`.
 *
 * Only the connector DRAWING is shared here — each caller keeps its own
 * indent mechanism (margin-shifting the whole box vs. widening `pl`) and
 * supplies `geometry.indentAppliedViaMargin` so this component can still
 * compute correct positions in that box's own local coordinate space. A
 * caller's other visual differences (card vs. table-row border weight,
 * corner radius, how far a segment reaches into the gap between rows) are
 * config, not hardcoded, via `geometry.elbow` / `.immediateRail` /
 * `.ancestorRail` — see each page's CLAUDE.md for why those numbers differ
 * (card layouts have a `gap` between siblings a table row doesn't).
 */
const NestingConnector = ({ depth, railBelow, geometry }: NestingConnectorProps) => {
  if (depth === 0) {
    return null;
  }

  const { indentStepPx, indentAppliedViaMargin, originOffsetPx, elbow, immediateRail, ancestorRail } =
    geometry;
  const ownLeftShiftPx = indentAppliedViaMargin ? depth * indentStepPx : 0;
  const leftForAncestorDepth = (ancestorDepth: number): number =>
    ancestorDepth * indentStepPx + originOffsetPx - ownLeftShiftPx;

  return (
    <Fragment>
      {/* Elbow: reaches up to the dispatcher (or an earlier sibling)
      directly above, then curves right into this row/card's own left
      edge. */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          left: `${leftForAncestorDepth(depth - 1)}px`,
          top: elbow.top,
          height: elbow.height,
          width: elbow.width,
          borderLeft: elbow.borderWidth,
          borderBottom: elbow.borderWidth,
          borderColor: 'divider',
          borderBottomLeftRadius: `${elbow.borderRadius}px`,
          pointerEvents: 'none',
        }}
      />
      {railBelow[depth - 1] ? (
        // The immediate parent has a later sibling coming — continue the
        // line past this row/card's far edge into the next gap.
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            left: `${leftForAncestorDepth(depth - 1)}px`,
            top: immediateRail.top,
            bottom: immediateRail.bottom,
            borderLeft: immediateRail.borderWidth,
            borderColor: 'divider',
            pointerEvents: 'none',
          }}
        />
      ) : null}
      {railBelow.slice(0, depth - 1).map((hasMoreBelow, ancestorDepth) =>
        hasMoreBelow ? (
          <Box
            key={ancestorDepth}
            aria-hidden
            sx={{
              position: 'absolute',
              left: `${leftForAncestorDepth(ancestorDepth)}px`,
              top: ancestorRail.top,
              bottom: ancestorRail.bottom,
              borderLeft: ancestorRail.borderWidth,
              borderColor: 'divider',
              pointerEvents: 'none',
            }}
          />
        ) : null,
      )}
    </Fragment>
  );
};

export default NestingConnector;
