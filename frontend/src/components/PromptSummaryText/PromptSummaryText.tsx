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
import type { ReactNode } from 'react';
import type { SxProps, Theme } from '@mui/material';
import { Box, Button } from '@mui/material';
import { promptSummaryRenderer } from '../../lib/promptSummary';

export interface PromptSummaryTextProps {
  prompt: string;
  /**
   * How to render an ordinary (non-summarized) prompt. Defaults to the raw
   * text; pass this to swap in a caller-specific treatment — e.g. a
   * clamp-and-"view formatted" widget — while the subagent-notification
   * handling below stays shared.
   */
  renderOrdinary?: (prompt: string) => ReactNode;
  /**
   * When provided, a subagent-notification summary also renders a "View
   * more" trigger, called with the full raw prompt (the envelope the summary
   * was extracted from) so a caller can open its own "view full text"
   * affordance — e.g. AttributeList's `ExpandedValueDialog`. Omit to show
   * the summary with no way to see the raw envelope from this row.
   */
  onViewFullPrompt?: (prompt: string) => void;
  /** Merged after the default styles on the "SUBAGENT" eyebrow label span. */
  labelSx?: SxProps<Theme>;
  /** Merged after the default styles on the summary text's wrapping span. */
  summarySx?: SxProps<Theme>;
}

// Renders a prompt, substituting promptSummaryRenderer's summary — a muted,
// italic line prefixed with a "SUBAGENT" eyebrow label — whenever the prompt
// isn't really human-authored text (currently: a <task-notification>
// envelope; see lib/promptSummary.ts). An ordinary prompt falls through to
// `renderOrdinary`, so each call site keeps its own treatment for that case
// (a hover-only native tooltip vs. a clamp-and-expand "view formatted"
// dialog) while the subagent-notification detection and styling live in
// exactly one place rather than being re-implemented per call site.
// `labelSx`/`summarySx` merge after the two spans' own defaults (same
// array-merge pattern as GhostButton's `sx`), so a caller can retint or
// resize them to fit its own surrounding typography rather than being stuck
// with one fixed look everywhere this renders.
const PromptSummaryText = ({
  prompt,
  renderOrdinary,
  onViewFullPrompt,
  labelSx,
  summarySx,
}: PromptSummaryTextProps) => {
  const summary = promptSummaryRenderer(prompt);
  if (!summary) {
    return <>{renderOrdinary ? renderOrdinary(prompt) : prompt}</>;
  }
  return (
    <Box
      component="span"
      sx={[
        { color: 'text.secondary', fontStyle: 'italic' },
        ...(Array.isArray(summarySx) ? summarySx : [summarySx]),
      ]}
    >
      <Box
        component="span"
        sx={[
          { typography: 'eyebrowSm', color: 'text.disabled', mr: 0.6 },
          ...(Array.isArray(labelSx) ? labelSx : [labelSx]),
        ]}
      >
        SUBAGENT
      </Box>
      {summary}
      {onViewFullPrompt ? (
        <Button
          size="small"
          variant="text"
          onClick={(event) => {
            event.stopPropagation();
            onViewFullPrompt(prompt);
          }}
          sx={{
            minWidth: 0,
            p: 0,
            ml: 0.6,
            fontSize: 'inherit',
            fontStyle: 'normal',
            textTransform: 'none',
            verticalAlign: 'baseline',
          }}
        >
          View more
        </Button>
      ) : null}
    </Box>
  );
};

export default PromptSummaryText;
