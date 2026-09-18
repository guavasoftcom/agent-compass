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

// Per-span coloring for the waterfall's subagent-dispatch highlight: a dispatch span (the "Agent"
// tool call) and every span in its subtree share one color, so a reader can see at a glance which
// rows belong to which dispatched subagent. Pure, no React, same idiom as spanTree.ts /
// spanRelations.ts in this folder.
//
// Color assignment is BY AGENT TYPE, not by dispatch instance: two dispatches sharing the same
// `subagent_type` (e.g. two "Explore" runs) are the same kind of agent and share one color. Indices
// are assigned via colorForAgentDispatchIndex(theme.ts) in first-seen order over DISTINCT labels,
// walking the tree depth-first -- the same DFS order buildSpanIndices uses for the row index badge,
// so a color that appears higher in the waterfall was always assigned first.
//
// This is a DEDICATED palette, not colorForIndex/CHART_PALETTE (the general chart palette every
// other categorical coloring on this page uses) -- CHART_PALETTE leads with violet and pink, which
// are exactly the waterfall toolbar's "model" and "tokens" legend colors, so the first two agent
// types dispatched in a trace would otherwise silently take on the same colors as unrelated badge
// families rendered right next to them in the same legend row. See theme.ts's own comment on
// colorForAgentDispatchIndex for the full list of hues this palette avoids.
//
// Nested dispatches (a dispatch inside another dispatch's subtree) resolve to the INNERMOST
// enclosing dispatch, mirroring the backend's SubagentCostAttributor#enclosingDispatchChain, which
// aggregates cost/call-counts on the innermost dispatch for the identical reason: the subagent that
// actually made a call is the one whose work it is, and the chain above it is identity, not
// ownership. Dispatches never nest in real data today, but the rule is cheap to get right up front.
import type { SpanRow } from '../../api';
import { colorForAgentDispatchIndex } from '../../theme/theme';
import type { SpanTree } from './spanTree';

// Mirrors SpanToolBadge's exact check (components/SpanWaterfallRow/SpanWaterfallRow.tsx) for what
// counts as a subagent dispatch: a span whose tool_name attribute is literally "Agent".
const AGENT_TOOL_NAME = 'Agent';

// A dispatch with no readable subagent_type (empty, absent, or not populated) still gets its own
// color rather than being left uncolored -- it is still a real dispatch, just an unnamed one.
const GENERIC_SUBAGENT_LABEL = 'Subagent';

export interface AgentDispatchLegendEntry {
  label: string;
  color: string;
  // Every dispatch span's own spanId sharing this label/color (the dispatch span itself, not its
  // descendants), in first-seen DFS order. Lets a click on this legend entry jump to -- and cycle
  // through -- each of possibly several same-type dispatches; see the click-to-jump section in this
  // page's CLAUDE.md.
  dispatchSpanIds: string[];
}

export interface AgentDispatchColoring {
  // Every span belonging to a dispatch -- the dispatch span itself and its whole subtree --
  // resolved directly to the color its (innermost enclosing) dispatch was assigned. No entry for
  // a main-loop span that sits outside every dispatch, so `.get(spanId)` reads as "not part of a
  // subagent dispatch" the same way an absent Map key reads anywhere else on this page.
  colorBySpanId: Map<string, string>;
  // The same per-span resolution as colorBySpanId, but the dispatch's label (subagent_type, or the
  // generic fallback) instead of its color -- what SpanInspectorDrawer reads to render the "which
  // subagent" name chip beside the color it already gets from colorBySpanId. Always has exactly the
  // same key set as colorBySpanId, since both are written together on every walk() visit below.
  labelBySpanId: Map<string, string>;
  // Distinct dispatch labels (subagent_type, or the generic fallback), first-seen in DFS order,
  // paired with the color assigned to that label. What the toolbar's legend renders; empty when
  // the trace dispatched no subagent.
  legend: AgentDispatchLegendEntry[];
}

const isDispatchSpan = (span: SpanRow): boolean =>
  span.attributes?.['tool_name'] === AGENT_TOOL_NAME;

// Same hasSubagentType-style guard SpanToolBadge uses before trusting subagent_type off the
// attribute bag: present, non-null, and not the empty string.
const subagentTypeLabelOf = (span: SpanRow): string => {
  const subagentTypeAttribute = span.attributes?.['subagent_type'];
  const hasSubagentType =
    subagentTypeAttribute !== undefined &&
    subagentTypeAttribute !== null &&
    String(subagentTypeAttribute) !== '';
  return hasSubagentType ? String(subagentTypeAttribute) : GENERIC_SUBAGENT_LABEL;
};

export const buildAgentDispatchColoring = (
  tree: SpanTree,
  spans: SpanRow[],
): AgentDispatchColoring => {
  const colorBySpanId = new Map<string, string>();
  const labelBySpanId = new Map<string, string>();
  // A Map preserves insertion order, so reading its values back out is the same first-seen-label
  // DFS order the legend has always been in -- the legend entries themselves are mutable objects,
  // looked up (or created) by label on every dispatch-span visit, with the current dispatch span's
  // id pushed onto dispatchSpanIds each time.
  const legendEntryByLabel = new Map<string, AgentDispatchLegendEntry>();

  if (spans.length === 0) {
    return { colorBySpanId, labelBySpanId, legend: [] };
  }

  const legendEntryForDispatch = (span: SpanRow): AgentDispatchLegendEntry => {
    const label = subagentTypeLabelOf(span);
    const existingEntry = legendEntryByLabel.get(label);
    if (existingEntry !== undefined) {
      existingEntry.dispatchSpanIds.push(span.spanId);
      return existingEntry;
    }
    const entry: AgentDispatchLegendEntry = {
      label,
      color: colorForAgentDispatchIndex(legendEntryByLabel.size),
      dispatchSpanIds: [span.spanId],
    };
    legendEntryByLabel.set(label, entry);
    return entry;
  };

  // One DFS walk, carrying the innermost enclosing dispatch's color+label down from parent to
  // child. Entering a (possibly nested) dispatch span overrides whatever was carried in from
  // above, which is what makes the innermost dispatch win for every span under it.
  const walk = (
    span: SpanRow,
    enclosing: { color: string; label: string } | undefined,
  ): void => {
    const current = isDispatchSpan(span) ? legendEntryForDispatch(span) : enclosing;
    if (current !== undefined) {
      colorBySpanId.set(span.spanId, current.color);
      labelBySpanId.set(span.spanId, current.label);
    }
    for (const child of tree.childrenByParentId.get(span.spanId) ?? []) {
      walk(child, current);
    }
  };

  for (const root of tree.roots) {
    walk(root, undefined);
  }

  return { colorBySpanId, labelBySpanId, legend: Array.from(legendEntryByLabel.values()) };
};
