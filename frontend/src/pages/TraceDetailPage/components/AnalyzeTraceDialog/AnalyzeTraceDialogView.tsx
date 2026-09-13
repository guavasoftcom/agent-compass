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
import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import {
  Alert,
  alpha,
  Box,
  CircularProgress,
  Dialog,
  DialogContent,
  IconButton,
  Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import AutorenewIcon from '@mui/icons-material/Autorenew';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlined';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import ReactMarkdown from 'react-markdown';
import rehypeSanitize from 'rehype-sanitize';
import GhostButton from '../../../../components/GhostButton';
import { colorForIndex, radii } from '../../../../theme/theme';
import { severity } from '../../../../theme/colors';
import { fontFamilies } from '../../../../theme/typography';
import { formatDuration, formatUsd } from '../../../TracesPage/tracesApi';
import { formatTimestamp } from '../../../../lib/format';
import type { LogRow, SpanRow, TraceCostBreakdown } from '../../../../api';
import type {
  TraceAnalysisPhase,
  TraceAnalysisResult,
} from '../../traceAnalysisApi';
import { costOfSelectedSpan } from '../../spanCost';
import { tokenBreakdownForSpan } from '../../../TracesPage/tokenBreakdown';
import {
  callNumberFromHref,
  remarkCallCitations,
  unknownCallNumberFromHref,
} from './callCitations';
import { summarizeTraceWork } from './summarizeTraceWork';
import { fileTypeBadge } from './fileTypeBadge';
import { PHASE_KIND_COLOR_INDEX } from '../../traceInsightsDerivations';
import type { PhaseKind } from '../../traceInsightsDerivations';

// The shape of a hast node as far as this file needs it. Typed structurally
// rather than imported from `hast` so the rendering rules below don't depend on
// a transitive type package react-markdown happens to pull in.
interface MarkdownNode {
  children?: { type?: string; tagName?: string; value?: string }[];
}

/**
 * A paragraph that is nothing but bold text — the shape the model writes its two
 * section titles in ("**What went wrong**", "**Apply this**") instead of using a
 * real markdown heading, so no amount of h1/h2 styling ever fires on them.
 * Promoting exactly this shape to a section heading is what separates the two
 * halves of the answer visually.
 *
 * Deliberately strict: one child, a strong, nothing beside it. A bullet or
 * paragraph that merely *starts* bold ("**File Over-reading** — the agent…") is
 * a finding, not a title, and stays a paragraph.
 */
const isSectionHeadingParagraph = (node?: MarkdownNode) => {
  const children = (node?.children ?? []).filter(
    (child) => child.type !== 'text' || (child.value ?? '').trim() !== '',
  );
  return children.length === 1 && children[0].tagName === 'strong';
};

// The line that starts the "Apply this" section, exactly as the prompt asks the
// model to write it (a bold-only paragraph, same shape isSectionHeadingParagraph
// promotes). Splitting on this fixed string, rather than parsing markdown AST for
// it, keeps this independent of how ReactMarkdown happens to structure the tree.
const APPLY_THIS_HEADING = /\n\*\*Apply this\*\*\n?/;

interface ApplyItem {
  label: string;
  // Which file the instruction has to live in, when the model named one — the
  // `<target> — <rule>` shape the prompt asks for. Undefined for the other two
  // item kinds, and for a stored analysis generated before targets existed.
  target?: string;
  text: string;
}

// The three fixed prefixes the backend prompt now asks the model for (see
// backend/src/main/resources/templates/trace-analysis-prompt.mustache). Order
// matters here only for which item renders first, not for parsing — each line is
// found independently so the model reordering them wouldn't break this.
//
// Two of the three accept a legacy prefix as well, for one reason: analyses are
// stored and re-read on every dialog open, so a row written under an older
// contract would otherwise silently lose its card and fall back to raw markdown.
// `CLAUDE.md rule:` is what the rule line was called before it grew a target, and
// `Rewritten request:` is what the wording line was called while it was framed as
// a paste-and-re-run request. Don't drop either alternative without also
// migrating or expiring those trace_analyses rows.
const RULE_ITEM_LABEL = 'Instruction rule';
// The wording advice, which renders differently from the other two: the request
// it is about has already run, so there is nothing to paste it into and it is
// shown against the original wording as a before/after instead.
const BETTER_WORDING_ITEM_LABEL = 'Better wording';

const APPLY_ITEM_PATTERNS: { label: string; prefix: RegExp }[] = [
  {
    label: RULE_ITEM_LABEL,
    prefix: /^(?:Instruction rule|CLAUDE\.md rule):\s*/i,
  },
  { label: 'Tool swap', prefix: /^Tool swap:\s*/i },
  {
    label: BETTER_WORDING_ITEM_LABEL,
    prefix: /^(?:Better wording|Rewritten request):\s*/i,
  },
];

// Splits `CLAUDE.md — Always run the typecheck` into its target and instruction.
// Deliberately an em-dash-with-spaces match and only on the first occurrence, so an
// instruction that itself contains a dash keeps it. A line with no separator is all
// instruction and no target, which is what a pre-target stored analysis looks like.
const APPLY_TARGET_SEPARATOR = /^(\S[^—]*?)\s+—\s+(.*)$/s;

// What a target can actually be: the closed list the prompt hands the model is
// `CLAUDE.md` plus one `skill:<name>` per editable skill the trace ran. Matching that
// shape — rather than trusting position — is what keeps the two halves apart, because
// the model writes them in either order: the prompt asks for `<target> — <rule>` and
// real stored analyses also carry `<rule> — CLAUDE.md`, which read positionally puts
// the whole rule in the chip and the bare filename in the copyable body.
const APPLY_TARGET_SHAPE = /^(?:skill:\S+|[\w.\-/]+\.md|\.claude\/settings\.json)$/i;

const splitTarget = (text: string): { target?: string; text: string } => {
  const match = text.match(APPLY_TARGET_SEPARATOR);
  if (!match) {
    return { text };
  }
  const leading = stripWrappingBackticks(match[1]);
  const trailing = stripWrappingBackticks(match[2]);
  if (APPLY_TARGET_SHAPE.test(leading)) {
    return { target: leading, text: trailing };
  }
  if (APPLY_TARGET_SHAPE.test(trailing)) {
    return { target: trailing, text: leading };
  }
  // Neither side names a file, so the em dash belongs to the instruction — a rule
  // written without a target (every analysis stored before targets existed) must keep
  // its own punctuation rather than losing its first clause to a chip.
  return { text };
};

const stripWrappingBackticks = (text: string) => {
  const trimmed = text.trim();
  if (trimmed.length >= 2 && trimmed.startsWith('`') && trimmed.endsWith('`')) {
    return trimmed.slice(1, -1).trim();
  }
  return trimmed;
};

/**
 * Splits the analysis into the "What went wrong" markdown body and the three
 * paste-ready "Apply this" items, each rendered as its own copyable card instead
 * of folded into the one bulk analysis string (see the mockup this was worked out
 * against, handoff-analyze-trace-polish/source/trace-detail.js).
 *
 * Falls back to `{ body: analysisText, items: [] }` — the whole text rendered as
 * one markdown block, "Apply this" heading included, same as before this parsing
 * existed — whenever the split or every prefix match fails. That covers a stored
 * analysis generated before this prompt change, and a model response that didn't
 * follow the fixed-line-prefix instruction: both stay readable, they just don't
 * get per-item copy buttons.
 */
const parseApplyThis = (
  analysisText: string,
): { body: string; items: ApplyItem[] } => {
  const headingMatch = analysisText.match(APPLY_THIS_HEADING);
  if (!headingMatch || headingMatch.index === undefined) {
    return { body: analysisText, items: [] };
  }
  const applySection = analysisText.slice(
    headingMatch.index + headingMatch[0].length,
  );
  const applyLines = applySection.split('\n');
  const items: ApplyItem[] = [];
  for (const { label, prefix } of APPLY_ITEM_PATTERNS) {
    const line = applyLines.find((candidate) => prefix.test(candidate.trim()));
    if (!line) {
      continue;
    }
    const text = stripWrappingBackticks(line.trim().replace(prefix, ''));
    if (text && text.toLowerCase() !== 'none') {
      // Only the rule line carries a target; a tool swap or rewritten request that
      // happens to contain an em dash must not have its first clause eaten.
      items.push(
        label === RULE_ITEM_LABEL
          ? { label, ...splitTarget(text) }
          : { label, text },
      );
    }
  }
  if (items.length === 0) {
    return { body: analysisText, items: [] };
  }
  return { body: analysisText.slice(0, headingMatch.index).trimEnd(), items };
};

interface Props {
  open: boolean;
  onClose: () => void;
  traceId: string;
  // Loading the stored ['trace-analysis', traceId] query on dialog open.
  isLoadingStoredAnalysis: boolean;
  // The stored analysis, or a freshly regenerated one once the mutation's
  // onSuccess has written it into the same query cache entry. Null when
  // nothing has ever been generated for this trace.
  analysis: TraceAnalysisResult | null;
  onRunAnalysis: () => void;
  onRegenerate: () => void;
  isRegenerating: boolean;
  regenerateError: Error | null;
  // What the in-flight run has reported so far. Optional so a caller that
  // doesn't stream (and the tests for every other state) can omit it; absent or
  // phase-less, the regenerating state falls back to a plain spinner.
  progress?: AnalysisRunProgress;
  // The call numbers this trace actually has, from the spans the page already
  // fetched (SpanRow.callNumber). A citation to a number outside this set — one
  // the model invented, or a call elided from a truncated timeline — renders as
  // plain text rather than a link that leads nowhere. Both this and the handler
  // are optional so a caller with no waterfall to point at (and the view's own
  // fixtures) simply gets an unlinked review.
  knownCallNumbers?: Set<number>;
  onNavigateToCall?: (callNumber: number) => void;
  // The trace's own spans and per-span log buckets, already loaded by the page
  // (see TraceDetailPage.tsx). Feed TraceSummaryCard's client-computed
  // Tools/Models/Files/Cost sections and the Work stat line — optional so a
  // caller with no waterfall to draw from (and this file's own test fixtures)
  // still gets the backend-authoritative Prompt/Outcome rows, just with no
  // supplementary sections.
  spans?: SpanRow[];
  logsBySpanId?: Map<string, LogRow[]>;
  // Backend-authoritative trace cost, threaded from the same
  // ['trace-summary', traceId] query TraceDetailHeader's Cost KPI reads —
  // never re-derived by summing per-span costs. See spanCost.ts and
  // TraceDetailPage/CLAUDE.md's "Cost" section for why.
  traceCostUsd?: number | null;
  // Per-subagent cost breakdown from the dialog's own
  // ['trace-cost-breakdown', traceId] query (`enabled: open && supplementaryOpen`
  // — see onSupplementaryExpand below — loaded alongside the stored analysis).
  // Null while loading, before the reader has expanded the section, or when the
  // trace has never been dispatched to any subagent — the summary card's Cost
  // section then falls back to its pre-existing, backend-total-only rendering.
  costBreakdown?: TraceCostBreakdown | null;
  // Called the first time the reader expands TraceSummaryCard's collapsed-by-
  // default "Show Tools, Models, Files, Cost" section, so the container knows
  // to start the cost-breakdown query rather than firing it on every dialog
  // open regardless of whether that section is ever opened. The view keeps
  // owning `detailsOpen` itself (collapse/expand is its own interaction state,
  // same as every other collapsible section on this page) — this is a
  // notification, not a controlled prop. Optional so a caller with no
  // supplementary content (or this file's own test fixtures) can omit it.
  onSupplementaryExpand?: () => void;
}

/**
 * The live state of one run, accumulated by the container from the SSE stream.
 *
 * `draftText` is the review as far as the model has written it. It stays empty
 * on the backend's structured-output path, where the answer is a JSON document
 * that is only a readable review once fully parsed — so `draftCharacters` is
 * what moves there instead. The view reads that difference directly rather than
 * taking a flag: text is shown when there is text.
 *
 * `activeKey` and `draftKey` hold the phase event's `key`, never its `phase` —
 * an oversized timeline splits into multiple review passes, and a phase like
 * DRAFTING recurs once per pass with a distinct key (e.g. `"DRAFTING#2"`). The
 * container resets `draftText` to empty whenever a delta's key differs from
 * `draftKey`, so a second pass's draft (or the later "apply this" step) never
 * appends onto an earlier one's text.
 */
export interface AnalysisRunProgress {
  phases: TraceAnalysisPhase[];
  activeKey: string | null;
  draftKey: string | null;
  draftText: string;
  draftCharacters: number;
}

// View: five states, in priority order —
//   1. loading the stored query → spinner
//   2. no stored analysis, not regenerating, no error → explanation + "Run analysis"
//   3. regenerating → spinner, "can take a couple of minutes" note
//   4. regenerate failed → error text + "Try again"
//   5. have a result → disclaimer + analysis text + caption/actions row, plus a
//      warning banner on top when analysis.outdated is true (the trace has
//      picked up spans since this analysis was generated — the backend
//      computes this by comparing the trace's current latest span timestamp
//      against the one the analysis was generated against, so no client-side
//      staleness math lives here)
// The analysis text is rendered as markdown using react-markdown with
// rehype-sanitize to strip dangerous HTML. The model intentionally produces
// markdown formatting (**bold**, backticks, lists), which reads better than
// plain text. Sanitization prevents any stray HTML in trace content from
// becoming live DOM.
//
// Almost all of that formatting is styled through the container's sx rather
// than through react-markdown's components map, which is left with the one
// rule that changes structure rather than looks: the bold-paragraph-to-heading
// promotion above. Inline code must not be handled by a code component that
// branches on an inline prop — that prop was removed in react-markdown 9, so
// the branch read undefined on every span and rendered every backticked file
// name, tool name and quoted observation as a full-width block. Since block
// code is only ever pre > code, code is styled as an inline chip and the
// pre code rule resets it.
// The state a run is in before its first event arrives, and what the view falls
// back to for any caller that doesn't stream.
const NO_PROGRESS: AnalysisRunProgress = {
  phases: [],
  activeKey: null,
  draftKey: null,
  draftText: '',
  draftCharacters: 0,
};

// The one height the analysis panel is ever laid out at: the finished result's
// ceiling, and the fixed height the skeleton and the live draft both hold during
// a run. Same number for all three so nothing in the dialog moves between
// "waiting", "being written" and "done".
const ANALYSIS_SURFACE_HEIGHT = '48vh';

/**
 * The bordered panel an analysis is read in — shared, deliberately, by the
 * placeholder skeleton, the live draft and the finished result. The transition
 * between them is the point of the streaming dialog: the review is written into
 * the same box it will still be sitting in when it is done, so nothing jumps,
 * re-flows or re-styles at the moment it finishes. Panels styled separately
 * would drift and give that away.
 */
const ANALYSIS_SURFACE_SX = {
  maxHeight: ANALYSIS_SURFACE_HEIGHT,
  overflowY: 'auto',
  p: 2.25,
  borderRadius: radii.sm,
  border: 1,
  borderColor: 'divider',
  bgcolor: 'background.default',
  fontSize: 13,
  lineHeight: 1.65,
  color: 'text.primary',
  '& > *:first-of-type': { mt: 0 },
  '& > *:last-child': { mb: 0 },
  '& p': { m: 0, mb: 1.25 },
  '& h1, & h2, & h3, & h4': {
    fontFamily: fontFamilies.display,
    fontSize: '1.05em',
    fontWeight: 700,
    lineHeight: 1.4,
    mt: 2.5,
    mb: 1.25,
  },
  '& ul, & ol': { m: 0, mb: 1.75, pl: 2.5 },
  '& li': { mb: 1 },
  '& li:last-child': { mb: 0 },
  '& li::marker': { color: 'text.disabled' },
  '& li > ul, & li > ol': { mt: 1, mb: 0 },
  '& strong': { fontWeight: 700 },
  '& em': { fontStyle: 'italic' },
  '& code': {
    fontFamily: fontFamilies.mono,
    fontSize: '0.88em',
    bgcolor: 'action.hover',
    px: 0.5,
    py: 0.125,
    borderRadius: '5px',
    overflowWrap: 'anywhere',
  },
  '& pre': {
    m: 0,
    mb: 1.5,
    p: 1.25,
    bgcolor: 'action.hover',
    border: 1,
    borderColor: 'divider',
    borderRadius: radii.sm,
    overflowX: 'auto',
  },
  '& pre code': {
    bgcolor: 'transparent',
    px: 0,
    py: 0,
    borderRadius: 0,
    fontSize: '0.85em',
    overflowWrap: 'normal',
  },
  '& blockquote': {
    m: 0,
    mb: 1.5,
    pl: 1.5,
    borderLeft: 2,
    borderColor: 'divider',
    color: 'text.secondary',
  },
  '& hr': { border: 0, borderTop: 1, borderColor: 'divider', my: 2 },
  '& a': { color: 'primary.main' },
} as const;

/**
 * How a cited call number becomes clickable, or null when nothing can resolve one
 * (a caller that passed no handler, or a trace whose spans haven't loaded).
 *
 * A context rather than a prop because the markdown renderer is used twice — on
 * the finished review and on the draft arriving over the stream — and the second
 * sits three components down inside the progress view. Threading two props
 * through that chain would make every component between them know about
 * citations, which none of them otherwise do.
 */
interface CallCitationTarget {
  isKnownCall: (callNumber: number) => boolean;
  onNavigateToCall: (callNumber: number) => void;
}

const CallCitationContext = createContext<CallCitationTarget | null>(null);

/**
 * The analysis text as markdown, sanitized. Also used on the half-written draft
 * arriving over the stream, which is safe in a way worth stating: react-markdown
 * re-parses from scratch on every render, so an unterminated `**` or a list cut
 * mid-item simply renders as the literal characters so far and resolves itself
 * the moment the rest arrives. There is no partial-markdown state to manage.
 *
 * The `components` map stays a single entry — see the file's other note on why
 * inline code must NOT be handled here.
 */
const AnalysisMarkdown = ({ children }: { children: string }) => {
  const citationTarget = useContext(CallCitationContext);
  // Rebuilt only when the trace's own call numbers change, not per render:
  // react-markdown re-runs the whole pipeline whenever the plugin array's
  // identity changes, which on a streaming draft is every delta.
  const remarkPlugins = useMemo(
    () =>
      citationTarget ? [remarkCallCitations(citationTarget.isKnownCall)] : [],
    [citationTarget],
  );
  return (
    <ReactMarkdown
      remarkPlugins={remarkPlugins}
      rehypePlugins={[rehypeSanitize]}
      components={{
        // A call citation the remark plugin rewrote — everything else stays an
        // ordinary link, since the review can legitimately contain one.
        a: ({ href, children: linkChildren, ...props }) => {
          const callNumber = callNumberFromHref(href);
          const unknownCallNumber = unknownCallNumberFromHref(href);
          if (unknownCallNumber !== null) {
            // A citation to a call number this trace does not have — the model
            // invented it. Rendered as a subdued, non-clickable span rather
            // than a link that would go nowhere, but still visually distinct
            // from both a linked known call and plain body text, so a reader
            // can tell "this looks like a citation but doesn't resolve" apart
            // from either.
            return (
              <Box
                component="span"
                title={`This trace has no call ${unknownCallNumber}`}
                sx={{
                  color: 'text.disabled',
                  textDecoration: 'underline dotted',
                  textUnderlineOffset: '2px',
                  cursor: 'default',
                }}
              >
                {linkChildren}
              </Box>
            );
          }
          if (callNumber === null || !citationTarget) {
            // react-markdown hands every component the mdast node alongside the
            // element's own props; it must not reach the DOM. The `p` rule above
            // drops it by destructuring, which reads as an unused binding here.
            const anchorProps = { ...props };
            delete (anchorProps as { node?: unknown }).node;
            return (
              <a href={href} {...anchorProps}>
                {linkChildren}
              </a>
            );
          }
          return (
            <Box
              component="button"
              type="button"
              // Labelled rather than left to its own text: the button's content
              // is a bare number ("20"), which says nothing on its own to a
              // reader arriving on it out of the sentence.
              aria-label={`Show call ${callNumber} in the waterfall`}
              title={`Show call ${callNumber} in the waterfall`}
              onClick={() => citationTarget.onNavigateToCall(callNumber)}
              sx={{
                font: 'inherit',
                p: 0,
                border: 0,
                bgcolor: 'transparent',
                color: 'primary.main',
                fontWeight: 700,
                cursor: 'pointer',
                textDecoration: 'underline',
                textDecorationStyle: 'dotted',
                textUnderlineOffset: '2px',
                '&:hover': { textDecorationStyle: 'solid' },
              }}
            >
              {linkChildren}
            </Box>
          );
        },
        p: ({ node, ...props }) =>
          isSectionHeadingParagraph(node) ? (
            <Typography
              component="h3"
              sx={{
                fontFamily: fontFamilies.display,
                fontSize: '1.05em',
                fontWeight: 700,
                mt: 2.5,
                mb: 1.25,
                pb: 0.75,
                borderBottom: 1,
                borderColor: 'divider',
                '& strong': { fontWeight: 'inherit' },
              }}
              {...props}
            />
          ) : (
            <p {...props} />
          ),
      }}
    >
      {children}
    </ReactMarkdown>
  );
};

// The body of an "Apply this" card: text meant to be read and copied as it
// stands. Shared by the two items that are exactly that, and by both halves of
// the wording comparison, so the suggestion is set in the same face as the rule
// and the tool swap.
const APPLY_ITEM_TEXT_SX = {
  typography: 'mono',
  fontSize: 12,
  lineHeight: 1.55,
  color: 'text.primary',
  whiteSpace: 'pre-wrap',
  overflowWrap: 'anywhere',
} as const;

// How much of the original request to show before it needs expanding. Six lines
// is enough for the short follow-up requests that make up most of this database
// (62% of human-written prompts are under 120 characters) while stopping a pasted
// stack trace from burying the suggestion it is supposed to be compared against.
const ORIGINAL_REQUEST_CLAMP_LINES = 6;

// Whether the clamp will actually hide anything, and so whether the expand
// control is worth rendering. Deliberately a text heuristic rather than a
// scrollHeight measurement: measuring would tie the control to layout that jsdom
// does not compute, making the one interactive part of this card untestable, and
// the cost of being wrong is a toggle that expands a request already fully
// visible. The character bound is the clamp's line count at roughly 72
// monospace characters per line in this column.
const APPROXIMATE_CHARACTERS_PER_LINE = 72;
const isLongerThanTheClamp = (text: string) =>
  text.split('\n').length > ORIGINAL_REQUEST_CLAMP_LINES ||
  text.length > ORIGINAL_REQUEST_CLAMP_LINES * APPROXIMATE_CHARACTERS_PER_LINE;

const WORDING_HALF_LABEL_SX = {
  fontFamily: fontFamilies.display,
  fontSize: 9.5,
  fontWeight: 700,
  letterSpacing: '.5px',
  textTransform: 'uppercase',
  color: 'text.disabled',
  mb: 0.5,
} as const;

// One row per line of `analysis.summary` — "Request: …" / "Work: …" / "Outcome: …".
// A line with no ": " separator (shouldn't happen against the backend's own
// format, but stored text is stored text) renders as a bare value with no label
// rather than being dropped.
const SUMMARY_LINE_SEPARATOR = ': ';
// Display-only relabeling — the backend's own line prefix stays "Request" (see
// TraceAnalysisPromptBuilder#buildTraceSummary; parsing here keys off the ": "
// position, never the label text, so this is safe to rename without a backend
// change), but next to "Work"/"Outcome" it reads as the wrong noun for what's
// actually shown: the user's own prompt text.
const SUMMARY_LABEL_OVERRIDES: Record<string, string> = { Request: 'Prompt' };
// A single clause reads fine as one line; several joined clauses (the common
// shape for "Tools"/"Files"/"Cost", each naming a separate fact) read as one
// dense run-on sentence at that width, so they split into their own short list
// instead — same information, scannable instead of parsed word by word.
//
// The separator is the middle dot the backend joins them with
// (TraceAnalysisPromptBuilder's SUMMARY_CLAUSE_SEPARATOR), never a comma. An
// earlier revision split on `/,\s+/`, which shredded the one thing on this card
// that is free prose — the quoted request, and the quoted final message on the
// Outcome line — into bullets mid-sentence, and would now also cut a file away
// from its own "(Read ×2, Edit)" tool list. A summary stored before that change
// simply has no dot in it and renders as one line per fact, which is the right
// degradation: stored analyses are re-read, never migrated.
const SUMMARY_CLAUSE_SEPARATOR = ' · ';
const summaryRows = (
  summary: string,
): { label: string; value: string; clauses: string[] }[] =>
  summary.split('\n').map((line) => {
    const separatorIndex = line.indexOf(SUMMARY_LINE_SEPARATOR);
    const rawLabel = separatorIndex === -1 ? '' : line.slice(0, separatorIndex);
    const value =
      separatorIndex === -1
        ? line
        : line.slice(separatorIndex + SUMMARY_LINE_SEPARATOR.length);
    const clauses = value.split(SUMMARY_CLAUSE_SEPARATOR).filter(Boolean);
    return {
      label: SUMMARY_LABEL_OVERRIDES[rawLabel] ?? rawLabel,
      value,
      clauses,
    };
  });

// The Outcome row's error count, read off the backend's own deterministic prefix
// (TraceAnalysisPromptBuilder#outcomeSummaryLine always opens with either "no errors" or
// "N error(s)", optionally followed by "(M distinct)", before the "; ended: ..." clause) rather
// than recounted from spans — this stays the backend's own count of what it considers an error
// on this trace, not a second, possibly-disagreeing tally computed here.
const OUTCOME_ERROR_COUNT_PATTERN = /^(\d+) errors?\b/;
const outcomeErrorCount = (outcomeValue: string): number => {
  if (outcomeValue.startsWith('no errors')) {
    return 0;
  }
  const match = OUTCOME_ERROR_COUNT_PATTERN.exec(outcomeValue);
  return match ? Number(match[1]) : 0;
};

/**
 * A code-composed "what happened" recap — request, work, outcome — shown ahead
 * of the model's own findings so a reader re-opening a trace can re-orient
 * before reading verdicts, instead of reconstructing "what was this even about"
 * by hand. See the backend's `TraceAnalysisPromptBuilder#buildTraceSummary` for
 * why this text is never sent to Ollama: it carries no judgment and cites no
 * call number a reader would need to verify, so it renders as plain text here,
 * the same way `analysis.analysis` is plain text and never `dangerouslySetInnerHTML`.
 *
 * Prompt and Outcome are always the backend's own text — this component never
 * touches that parsing. Work is different: its content is replaced with a
 * small client-computed stat line ("N tool calls" / "N model calls" /
 * duration), from `summarizeTraceWork(spans)` — the same isToolCallSpan /
 * tokenBreakdownForSpan rules the header KPIs already use, so this card can
 * never disagree with them about what counts as a call. Behind a "Show
 * Tools, Models, Files, Cost" toggle (collapsed by default, matching the
 * design mockup) sit four more sections, all computed the same way and never
 * sent to the backend: Tools/Models as name chips (a ×N badge only when a
 * name repeats), Files as a path next to the tool chips that touched it, and
 * Cost as the backend-authoritative trace total (never a client-side sum —
 * see TraceDetailPage/CLAUDE.md's Cost section) plus the per-call figure
 * measured across the spans counted as model calls.
 *
 * `spans`/`logsBySpanId`/`traceCostUsd` are all optional: without them (a
 * caller with no waterfall to draw from, or an older test fixture) Work stays
 * the backend's own text and no supplementary sections render — the card's
 * pre-existing, fully backend-driven behavior, unchanged.
 */
const SUMMARY_ALWAYS_VISIBLE_LABELS = new Set(['Prompt', 'Work', 'Outcome']);

const SUMMARY_SECTION_LABEL_SX = {
  fontFamily: fontFamilies.display,
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '.6px',
  textTransform: 'uppercase',
  color: 'primary.main',
} as const;

// The bullet look shared by a multi-clause backend line and the two
// client-computed stat lists (Work, Cost) below.
const SummaryStatList = ({ items }: { items: string[] }) => (
  <Box
    component="ul"
    sx={{
      m: 0,
      p: 0,
      listStyle: 'none',
      display: 'flex',
      flexDirection: 'column',
      gap: 0.6,
    }}
  >
    {items.map((item) => (
      <Typography
        key={item}
        component="li"
        sx={{
          fontSize: 13.5,
          color: 'text.primary',
          lineHeight: 1.55,
          position: 'relative',
          pl: 1.5,
          '&::before': {
            content: '"\u2022"',
            position: 'absolute',
            left: 0,
            color: 'text.disabled',
          },
        }}
      >
        {item}
      </Typography>
    ))}
  </Box>
);

// One labelled block in the supplementary (Tools/Models/Files/Cost) area —
// same label styling and hairline top rule as the always-visible rows above
// it, so the collapsed section reads as a continuation of the same card
// rather than a bolted-on panel.
const SummarySection = ({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) => (
  <Box
    sx={{
      display: 'flex',
      flexDirection: 'column',
      gap: 0.6,
      pt: 1.5,
      pb: 1.5,
      borderTop: 1,
      borderColor: 'divider',
    }}
  >
    <Typography sx={SUMMARY_SECTION_LABEL_SX}>{label}</Typography>
    {children}
  </Box>
);

// A small colored language badge for the Files section — see fileTypeBadge.ts for why this reads
// the extension/filename rather than pulling in a per-language icon package. Fixed-width rather
// than shrink-to-content (like SummaryNameChip below) so a short label ("C") and a long one
// ("GRADLE") still line up into one ragged-right column down the file list.
const FileTypeIcon = ({ path }: { path: string }) => {
  const { label, color } = fileTypeBadge(path);
  return (
    <Box
      title={label}
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexShrink: 0,
        minWidth: 30,
        height: 16,
        px: 0.4,
        borderRadius: '4px',
        border: 1,
        borderColor: alpha(color, 0.4),
        bgcolor: alpha(color, 0.15),
        typography: 'mono',
        fontSize: 8.5,
        fontWeight: 700,
        letterSpacing: '.2px',
        color,
      }}
    >
      {label}
    </Box>
  );
};

// One chip per distinct tool/model name — the ×N badge only appears when the
// name repeats, since a bare "×1" states nothing a reader doesn't already see
// from the chip's own presence. `kind`, when given (Tools chips only — Models
// has no such taxonomy), tints the chip with traceInsightsDerivations' own
// phase-kind hue (PHASE_KIND_COLOR_INDEX + colorForIndex) so a Read/Edit/Search/
// Bash chip reads as the same color family everywhere that taxonomy classifies
// a tool call, instead of every tool chip looking alike.
const SummaryNameChip = ({
  name,
  count,
  kind,
}: {
  name: string;
  count: number;
  kind?: PhaseKind;
}) => {
  const kindColor = kind ? colorForIndex(PHASE_KIND_COLOR_INDEX[kind]) : null;
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.4,
        px: 0.75,
        py: 0.25,
        borderRadius: '999px',
        border: 1,
        borderColor: kindColor ? alpha(kindColor, 0.35) : 'divider',
        bgcolor: kindColor
          ? alpha(kindColor, 0.12)
          : (t) => alpha(t.palette.text.primary, 0.04),
        fontSize: 12,
        color: kindColor ?? 'text.primary',
      }}
    >
      {name}
      {count > 1 && (
        <Box
          component="span"
          sx={{
            typography: 'mono',
            fontSize: 10,
            fontWeight: 700,
            color: kindColor ?? 'text.secondary',
          }}
        >
          ×{count}
        </Box>
      )}
    </Box>
  );
};

const pluralize = (count: number, noun: string): string =>
  `${count} ${noun}${count === 1 ? '' : 's'}`;

// Every file in a real trace tends to share one long project-root prefix (`/Users/.../coding-
// agent-tuning/`) that says nothing about any individual row — it's the same on all of them, so
// it just pushes the part that actually differs (which file, in which of the project's own
// directories) off the edge of the row. Computed once per file list and stripped before any row
// renders, rather than per row, so every row strips the same prefix even if one path happens to
// be short enough not to need it. Requires at least two paths (a single file has nothing to
// share a prefix WITH) and at least two shared segments (`i > 1`, not `i > 0`) — a shared leading
// slash alone isn't a directory prefix worth stripping, it's just how every absolute path starts.
const commonDirectoryPrefix = (paths: string[]): string => {
  if (paths.length < 2) {
    return '';
  }
  const segmentsByPath = paths.map((path) => path.split('/'));
  let sharedSegmentCount = 0;
  while (
    sharedSegmentCount < segmentsByPath[0].length - 1 &&
    segmentsByPath.every(
      (segments) =>
        segments[sharedSegmentCount] === segmentsByPath[0][sharedSegmentCount],
    )
  ) {
    sharedSegmentCount += 1;
  }
  return sharedSegmentCount > 1
    ? segmentsByPath[0].slice(0, sharedSegmentCount).join('/')
    : '';
};

// The budget for a file row's directory segment once the shared project-root prefix above has
// already been stripped from it — generous rather than tight, because the directory Typography
// grows to fill whatever the row's filename and tool chips leave (flex: '1 1 auto') and the
// dialog itself runs up to 920px wide. An upper bound on the JS-computed string, not the actual
// rendered width: a genuinely narrow viewport still clips further via the CSS
// `textOverflow: 'ellipsis'` safety net below (at the trailing end, same as any ordinary
// overflowing text) rather than overflowing the row outright.
const FILE_DIRECTORY_TRUNCATE_LENGTH = 64;

/**
 * Truncates a directory path from the FRONT, keeping the tail closest to the filename — the
 * opposite end from `commonDirectoryPrefix`'s own strip, since what's cut here is the part of the
 * remaining directory that's still furthest from the file itself. The filename this directory
 * sits next to is never passed through this function and never truncates at all; only the
 * de-emphasized directory text shrinks. A leading-ellipsis CSS trick (`direction: rtl` +
 * `unicode-bidi: plaintext`) was tried instead of this and rejected: `unicode-bidi: plaintext`
 * lets the browser redetect directionality from the (ordinary LTR) content, which silently
 * overrides the forced `rtl` and leaves the browser truncating at the trailing end regardless —
 * same failure mode `truncateFilePathMiddle` was rewritten to fix before this, so it isn't
 * repeated here either.
 */
const truncateDirectoryFromFront = (
  directory: string,
  maxLength: number,
): string => {
  if (directory.length <= maxLength) {
    return directory;
  }
  const ellipsis = '…';
  if (maxLength <= ellipsis.length) {
    return ellipsis;
  }
  return `${ellipsis}${directory.slice(directory.length - (maxLength - ellipsis.length))}`;
};

/**
 * The clickable "call N" badge in front of a subagent's cost row — reuses the
 * same `CallCitationContext` the markdown citation links read (the whole card
 * renders inside the dialog's `CallCitationContext.Provider`, see the bottom of
 * this file), so a dispatch call number jumps to the waterfall row exactly the
 * way a cited call number in the review text does. Renders as plain, non-clickable
 * text — never a dead link — when the trace's spans haven't resolved a matching
 * call number yet (no `citationTarget`) or the number falls outside what this
 * trace has (see the call-number gotcha in TraceDetailPage/CLAUDE.md for why
 * that can legitimately happen).
 */
const SubagentDispatchCallNumberBadge = ({
  callNumber,
}: {
  callNumber: number;
}) => {
  const citationTarget = useContext(CallCitationContext);
  const known = citationTarget?.isKnownCall(callNumber) ?? false;
  if (!citationTarget || !known) {
    return (
      <Box
        component="span"
        sx={{ typography: 'mono', fontSize: 11, color: 'text.disabled' }}
      >
        call {callNumber}
      </Box>
    );
  }
  return (
    <Box
      component="button"
      type="button"
      aria-label={`Show call ${callNumber} in the waterfall`}
      title={`Show call ${callNumber} in the waterfall`}
      onClick={() => citationTarget.onNavigateToCall(callNumber)}
      sx={{
        font: 'inherit',
        typography: 'mono',
        fontSize: 11,
        p: 0,
        border: 0,
        bgcolor: 'transparent',
        color: 'primary.main',
        fontWeight: 700,
        cursor: 'pointer',
        textDecoration: 'underline',
        textDecorationStyle: 'dotted',
        textUnderlineOffset: '2px',
        '&:hover': { textDecorationStyle: 'solid' },
      }}
    >
      call {callNumber}
    </Box>
  );
};

const TraceSummaryCard = ({
  summary,
  spans,
  logsBySpanId,
  traceCostUsd,
  costBreakdown,
  onDetailsExpand,
}: {
  summary: string;
  spans?: SpanRow[];
  logsBySpanId?: Map<string, LogRow[]>;
  traceCostUsd?: number | null;
  costBreakdown?: TraceCostBreakdown | null;
  onDetailsExpand?: () => void;
}) => {
  const [detailsOpen, setDetailsOpen] = useState(false);

  const work = useMemo(
    () => (spans ? summarizeTraceWork(spans) : null),
    [spans],
  );

  // The directory segments every file in this list shares, stripped once here rather than per
  // row — see commonDirectoryPrefix's own comment for why a list this small (typically single
  // digits) still benefits: the shared part is usually the whole project root, which says
  // nothing about any individual file and otherwise pushes the part that differs off the row.
  const commonFileDirectoryPrefix = useMemo(
    () => commonDirectoryPrefix(work?.files.map((file) => file.path) ?? []),
    [work],
  );

  // Looked up by name rather than re-classified: a file's tool chips (e.g. "[Read]" on a file
  // also shown in the Tools section) should always match the color that same tool's chip up in
  // Tools already took, never a second, possibly-different classification of the same name.
  const toolKindByName = useMemo(
    () => new Map(work?.tools.map((tool) => [tool.name, tool.kind]) ?? []),
    [work],
  );

  // The per-call figure behind "measured across N model calls" — summed over
  // exactly the spans `work.modelCalls` counted, through costOfSelectedSpan
  // (the same per-span resolver the waterfall row and drawer use). Never
  // shown as, or added into, the trace total: see the Cost section doc above.
  const measuredModelCallCostUsd = useMemo(() => {
    if (!spans) {
      return 0;
    }
    let total = 0;
    spans.forEach((span) => {
      if (tokenBreakdownForSpan(span).total > 0) {
        total += costOfSelectedSpan(span, logsBySpanId?.get(span.spanId));
      }
    });
    return total;
  }, [spans, logsBySpanId]);

  // See SUMMARY_ALWAYS_VISIBLE_LABELS above: only filters once spans made the
  // client-computed sections possible, so a caller with no spans keeps the
  // card's original, fully backend-driven rendering unchanged.
  const rows = summaryRows(summary).filter(
    ({ label }) =>
      work === null || label === '' || SUMMARY_ALWAYS_VISIBLE_LABELS.has(label),
  );

  const costSectionVisible =
    traceCostUsd != null || measuredModelCallCostUsd > 0;
  const hasSubagentCostBreakdown =
    (costBreakdown?.subagentCosts.length ?? 0) > 0;
  const hasSupplementaryContent =
    work !== null &&
    (work.tools.length > 0 ||
      work.models.length > 0 ||
      work.files.length > 0 ||
      costSectionVisible);

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        gap: 1.5,
        border: 1,
        borderColor: 'divider',
        borderRadius: radii.sm,
        p: 1.75,
        bgcolor: (t) => alpha(t.palette.text.primary, 0.03),
      }}
    >
      {rows.map(({ label, value, clauses }, i) => {
        // Work used to restate the same tool/model call counts the Tools/Models chip sections
        // below already show — now it's just the one figure those sections don't carry, duration.
        const isComputedWorkRow = label === 'Work' && work !== null;
        // Outcome used to restate the same failure detail "What went wrong" already covers below
        // — now a short status line (error count only, read off the backend's own deterministic
        // Outcome prefix — see outcomeErrorCount) with an ok/warning icon+color pair.
        const isComputedOutcomeRow = label === 'Outcome' && work !== null;
        return (
          <Box
            key={label || value}
            sx={{
              display: 'flex',
              flexDirection: 'column',
              gap: 0.6,
              pt: i === 0 ? 0 : 1.5,
              borderTop: i === 0 ? 0 : 1,
              borderColor: 'divider',
            }}
          >
            {label && (
              <Typography sx={SUMMARY_SECTION_LABEL_SX}>{label}</Typography>
            )}
            {isComputedWorkRow && work ? (
              <Typography
                sx={{ fontSize: 13.5, color: 'text.primary', lineHeight: 1.55 }}
              >
                {formatDuration(work.durationMs * 1e6)}
              </Typography>
            ) : isComputedOutcomeRow ? (
              (() => {
                const errorCount = outcomeErrorCount(value);
                const ok = errorCount === 0;
                return (
                  <Box
                    sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}
                  >
                    {ok ? (
                      <CheckCircleOutlineIcon
                        sx={{
                          fontSize: 16,
                          color: 'success.main',
                          flexShrink: 0,
                        }}
                      />
                    ) : (
                      <WarningAmberIcon
                        sx={{
                          fontSize: 16,
                          color: severity.warning,
                          flexShrink: 0,
                        }}
                      />
                    )}
                    <Typography
                      sx={{
                        fontSize: 13.5,
                        color: 'text.primary',
                        lineHeight: 1.55,
                      }}
                    >
                      {ok ? 'No errors' : pluralize(errorCount, 'error')}
                    </Typography>
                  </Box>
                );
              })()
            ) : clauses.length > 1 ? (
              <SummaryStatList items={clauses} />
            ) : (
              <Typography
                sx={{ fontSize: 13.5, color: 'text.primary', lineHeight: 1.55 }}
              >
                {value}
              </Typography>
            )}
          </Box>
        );
      })}

      {hasSupplementaryContent && work && (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.25 }}>
          <Box
            component="button"
            type="button"
            onClick={() =>
              setDetailsOpen((previous) => {
                const next = !previous;
                // Notify the container the first (and every) time this opens, so it can start
                // the cost-breakdown query on demand rather than on every dialog open — see
                // onSupplementaryExpand's own doc comment on Props above.
                if (next) {
                  onDetailsExpand?.();
                }
                return next;
              })
            }
            aria-expanded={detailsOpen}
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 0.5,
              alignSelf: 'flex-start',
              border: 0,
              bgcolor: 'transparent',
              cursor: 'pointer',
              p: 0,
              fontFamily: fontFamilies.display,
              fontSize: 10,
              fontWeight: 700,
              letterSpacing: '.5px',
              textTransform: 'uppercase',
              color: 'text.secondary',
              '&:hover': { color: 'primary.main' },
            }}
          >
            <ExpandMoreIcon
              sx={{
                fontSize: 15,
                color: 'text.disabled',
                transform: detailsOpen ? 'none' : 'rotate(-90deg)',
                transition: 'transform .15s',
              }}
            />
            {detailsOpen ? 'Hide' : 'Show'} Tools, Models, Files, Cost
          </Box>

          {detailsOpen && (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
              {work.tools.length > 0 && (
                <SummarySection label={`Tools (${work.toolCalls})`}>
                  <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.6 }}>
                    {work.tools.map((tool) => (
                      <SummaryNameChip
                        key={tool.name}
                        name={tool.name}
                        count={tool.count}
                        kind={tool.kind}
                      />
                    ))}
                  </Box>
                </SummarySection>
              )}
              {work.models.length > 0 && (
                <SummarySection label={`Models (${work.modelCalls})`}>
                  <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.6 }}>
                    {work.models.map((model) => (
                      <SummaryNameChip
                        key={model.name}
                        name={model.name}
                        count={model.count}
                      />
                    ))}
                  </Box>
                </SummarySection>
              )}
              {work.files.length > 0 && (
                <SummarySection label={`Files (${work.files.length})`}>
                  <Box
                    sx={{ display: 'flex', flexDirection: 'column', gap: 0.6 }}
                  >
                    {work.files.map((file) => {
                      // The prefix every file shares (usually the whole project root) stripped
                      // once above; what's left is split at the last slash so the directory —
                      // de-emphasized and the one part allowed to truncate — and the filename —
                      // bold, `text.primary`, and never truncated — can each get their own
                      // treatment instead of one flat path string.
                      const relativePath =
                        commonFileDirectoryPrefix &&
                        file.path.startsWith(commonFileDirectoryPrefix)
                          ? file.path
                              .slice(commonFileDirectoryPrefix.length)
                              .replace(/^\//, '')
                          : file.path;
                      const lastSlashIndex = relativePath.lastIndexOf('/');
                      const directory =
                        lastSlashIndex === -1
                          ? ''
                          : relativePath.slice(0, lastSlashIndex + 1);
                      const fileName =
                        lastSlashIndex === -1
                          ? relativePath
                          : relativePath.slice(lastSlashIndex + 1);
                      return (
                        <Box
                          key={file.path}
                          sx={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 0.6,
                            minWidth: 0,
                          }}
                        >
                          <FileTypeIcon path={file.path} />
                          <Box
                            title={file.path}
                            sx={{
                              display: 'flex',
                              // Grows to use whatever width the row's tool chips (flexShrink: 0)
                              // leave behind, rather than sitting at its own content width and
                              // wasting the rest of the row.
                              flex: '1 1 auto',
                              minWidth: 0,
                              overflow: 'hidden',
                            }}
                          >
                            {directory && (
                              <Typography
                                component="span"
                                sx={{
                                  typography: 'mono',
                                  fontSize: 12.5,
                                  color: 'text.secondary',
                                  flexShrink: 1,
                                  minWidth: 0,
                                  whiteSpace: 'nowrap',
                                  overflow: 'hidden',
                                  textOverflow: 'ellipsis',
                                }}
                              >
                                {truncateDirectoryFromFront(
                                  directory,
                                  FILE_DIRECTORY_TRUNCATE_LENGTH,
                                )}
                              </Typography>
                            )}
                            <Typography
                              component="span"
                              sx={{
                                typography: 'mono',
                                fontSize: 12.5,
                                fontWeight: 700,
                                color: 'text.primary',
                                flexShrink: 0,
                                whiteSpace: 'nowrap',
                              }}
                            >
                              {fileName}
                            </Typography>
                          </Box>
                          <Box
                            sx={{
                              display: 'flex',
                              flexWrap: 'wrap',
                              gap: 0.4,
                              flexShrink: 0,
                            }}
                          >
                            {file.tools.map((tool) => (
                              <SummaryNameChip
                                key={tool.name}
                                name={tool.name}
                                count={tool.count}
                                kind={toolKindByName.get(tool.name)}
                              />
                            ))}
                          </Box>
                        </Box>
                      );
                    })}
                  </Box>
                </SummarySection>
              )}
              {costSectionVisible && (
                <SummarySection
                  label={`Cost (${formatUsd(traceCostUsd ?? 0)})`}
                >
                  {work.modelCalls > 0 && (
                    <SummaryStatList
                      items={[
                        `${formatUsd(measuredModelCallCostUsd)} measured across ${pluralize(work.modelCalls, 'model call')}`,
                      ]}
                    />
                  )}
                  {hasSubagentCostBreakdown && costBreakdown && (
                    <Box
                      sx={{
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 0.6,
                        mt: 0.4,
                        pt: 0.9,
                        borderTop: 1,
                        borderColor: 'divider',
                      }}
                    >
                      <Typography
                        sx={{ fontSize: 11.5, color: 'text.disabled' }}
                      >
                        {formatUsd(costBreakdown.measuredCostUsd)} attributed
                        across the main loop and{' '}
                        {pluralize(
                          costBreakdown.subagentCosts.length,
                          'subagent dispatch',
                        )}
                        {costBreakdown.auxiliaryCostUsd > 0
                          ? ', plus auxiliary calls'
                          : ''}{' '}
                        — a separate measurement from the total above; the two
                        aren't expected to reconcile.
                      </Typography>
                      <Box
                        component="ul"
                        sx={{
                          m: 0,
                          p: 0,
                          listStyle: 'none',
                          display: 'flex',
                          flexDirection: 'column',
                          gap: 0.5,
                        }}
                      >
                        {costBreakdown.subagentCosts.map(
                          (subagentCost, subagentIndex) => (
                            <Box
                              key={`${subagentCost.dispatchCallNumber}-${subagentCost.subagentLabel}`}
                              component="li"
                              sx={{
                                display: 'flex',
                                alignItems: 'center',
                                flexWrap: 'wrap',
                                gap: 0.6,
                                fontSize: 13,
                                color: 'text.primary',
                              }}
                            >
                              <Typography
                                component="span"
                                sx={{ fontSize: 13, color: 'text.disabled' }}
                              >
                                Subagent #{subagentIndex + 1}:
                              </Typography>
                              <SubagentDispatchCallNumberBadge
                                callNumber={subagentCost.dispatchCallNumber}
                              />
                              <Typography
                                component="span"
                                sx={{ fontSize: 13, fontWeight: 600 }}
                              >
                                {subagentCost.subagentLabel}
                              </Typography>
                              <Typography
                                component="span"
                                sx={{ fontSize: 13, color: 'text.secondary' }}
                              >
                                {formatUsd(subagentCost.costUsd)} ·{' '}
                                {pluralize(
                                  subagentCost.modelCallCount,
                                  'model call',
                                )}{' '}
                                ·{' '}
                                {pluralize(
                                  subagentCost.toolCallCount,
                                  'tool call',
                                )}
                              </Typography>
                            </Box>
                          ),
                        )}
                      </Box>
                    </Box>
                  )}
                </SummarySection>
              )}
            </Box>
          )}
        </Box>
      )}
    </Box>
  );
};

/**
 * The wording advice, shown against the request it is about.
 *
 * The comparison is the point of this card and the reason it does not render like
 * the other two "Apply this" items. Those are text with a future: a rule goes into
 * a CLAUDE.md and changes every trace after it, a tool swap is a standing
 * correction. This one is about a request that has already run — pasting it
 * somewhere would only re-issue finished work — so its value is entirely in what
 * it teaches, and that only lands next to the words it is replacing.
 *
 * The "before" half is the stored `userPrompt` (see the backend's V24 migration),
 * never anything the model produced: the answer contract already spends a rule on
 * "never invent prompt wording", and having the model quote the request back would
 * put the one half this application can supply verbatim into the half it has to
 * police. Absent — an older stored row, or a trace whose wording nobody authored —
 * the caller renders the suggestion alone rather than an empty comparison.
 */
const WordingComparison = ({
  originalRequest,
  suggestedWording,
}: {
  originalRequest: string;
  suggestedWording: string;
}) => {
  const [expanded, setExpanded] = useState(false);
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.1 }}>
      <Box>
        <Typography sx={WORDING_HALF_LABEL_SX}>You wrote</Typography>
        <Typography
          sx={{
            ...APPLY_ITEM_TEXT_SX,
            color: 'text.secondary',
            ...(expanded
              ? {}
              : {
                  display: '-webkit-box',
                  WebkitLineClamp: ORIGINAL_REQUEST_CLAMP_LINES,
                  WebkitBoxOrient: 'vertical',
                  overflow: 'hidden',
                }),
          }}
        >
          {originalRequest}
        </Typography>
        {isLongerThanTheClamp(originalRequest) && (
          <Box
            component="button"
            type="button"
            onClick={() => setExpanded(!expanded)}
            sx={{
              border: 0,
              bgcolor: 'transparent',
              cursor: 'pointer',
              p: 0,
              mt: 0.4,
              fontFamily: fontFamilies.display,
              fontSize: 9.5,
              fontWeight: 700,
              letterSpacing: '.4px',
              textTransform: 'uppercase',
              color: 'text.disabled',
              '&:hover': { color: 'primary.main' },
            }}
          >
            {expanded ? 'Show less' : 'Show full request'}
          </Box>
        )}
      </Box>
      <Box>
        <Typography sx={WORDING_HALF_LABEL_SX}>
          Say instead, next time
        </Typography>
        <Typography sx={APPLY_ITEM_TEXT_SX}>{suggestedWording}</Typography>
      </Box>
    </Box>
  );
};

const AnalyzeTraceDialogView = ({
  open,
  onClose,
  traceId,
  isLoadingStoredAnalysis,
  analysis,
  onRunAnalysis,
  onRegenerate,
  isRegenerating,
  regenerateError,
  progress = NO_PROGRESS,
  knownCallNumbers,
  onNavigateToCall,
  spans,
  logsBySpanId,
  traceCostUsd,
  costBreakdown,
  onSupplementaryExpand,
}: Props) => {
  // Null unless the caller supplied both halves — a set of numbers with nothing
  // to do when one is clicked would render links that go nowhere.
  const citationTarget = useMemo<CallCitationTarget | null>(
    () =>
      knownCallNumbers && onNavigateToCall
        ? {
            isKnownCall: (callNumber: number) =>
              knownCallNumbers.has(callNumber),
            onNavigateToCall,
          }
        : null,
    [knownCallNumbers, onNavigateToCall],
  );

  const renderBody = () => {
    if (isLoadingStoredAnalysis) {
      return (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1.5,
            color: 'text.secondary',
            py: 4,
          }}
        >
          <CircularProgress size={18} thickness={5} />
          Checking for a saved analysis…
        </Box>
      );
    }

    if (isRegenerating) {
      return <AnalysisRunProgressView progress={progress} />;
    }

    if (regenerateError) {
      return (
        <Box sx={{ py: 3 }}>
          <Typography sx={{ fontSize: 13, color: 'error.main', mb: 2 }}>
            {regenerateError.message}
          </Typography>
          <GhostButton onClick={onRegenerate}>Try again</GhostButton>
        </Box>
      );
    }

    if (analysis) {
      const { body, items: applyItems } = parseApplyThis(analysis.analysis);
      return (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          <Typography
            sx={{
              fontSize: 12,
              color: 'text.disabled',
              fontStyle: 'italic',
              lineHeight: 1.5,
            }}
          >
            AI-generated review — a starting point, not a verdict. Verify
            findings against the trace before acting on them.
          </Typography>
          {analysis.outdated && (
            <Alert severity="warning" sx={{ fontSize: 13 }}>
              This trace has had new activity since this analysis was generated
              — it may no longer reflect everything that happened. Regenerate
              for an up-to-date read.
            </Alert>
          )}
          {analysis.timelineTruncated && (
            <Alert severity="info" sx={{ fontSize: 13 }}>
              This trace's call timeline was too long to fit whole —{' '}
              {analysis.omittedLineCount} call
              {analysis.omittedLineCount === 1 ? '' : 's'} were left out of the
              middle before this review was written. The model still saw the
              start and end of the trace, marked with where the gap is. This
              review predates full-timeline coverage; regenerate for one that
              sees every call.
            </Alert>
          )}
          {analysis.reviewPassCount > 1 && (
            <Alert severity="info" sx={{ fontSize: 13 }}>
              This trace's {analysis.timelineCallCount.toLocaleString()} calls
              were too many to review in one pass, so they were reviewed in{' '}
              {analysis.reviewPassCount} consecutive passes and the findings
              combined. Every call was seen.
            </Alert>
          )}
          {analysis.summary && (
            <TraceSummaryCard
              summary={analysis.summary}
              spans={spans}
              logsBySpanId={logsBySpanId}
              traceCostUsd={traceCostUsd}
              costBreakdown={costBreakdown}
              onDetailsExpand={onSupplementaryExpand}
            />
          )}
          {/* The finished result grows with its content instead of scrolling
              internally — the dialog's own single scroll (see the Dialog's
              paper sx) is what handles overflow now, so this box doesn't need
              its own. The streaming draft below keeps its fixed-height scroll;
              that panel has to hold still while text is still arriving. */}
          <Box
            sx={{
              ...ANALYSIS_SURFACE_SX,
              maxHeight: 'none',
              overflowY: 'visible',
            }}
          >
            <AnalysisMarkdown>{body}</AnalysisMarkdown>
            {applyItems.length > 0 && (
              <>
                <Typography
                  component="h3"
                  sx={{
                    fontFamily: fontFamilies.display,
                    fontSize: '1.05em',
                    fontWeight: 700,
                    mt: 2.5,
                    mb: 1.25,
                    pb: 0.75,
                    borderBottom: 1,
                    borderColor: 'divider',
                  }}
                >
                  Apply this
                </Typography>
                {applyItems.map((item) => (
                  <Box
                    key={item.label}
                    sx={{
                      border: 1,
                      borderColor: (t) => alpha(t.palette.primary.main, 0.22),
                      borderRadius: radii.sm,
                      p: 1.25,
                      mb: 1.1,
                      bgcolor: (t) => alpha(t.palette.primary.main, 0.06),
                      '&:last-of-type': { mb: 0 },
                    }}
                  >
                    <Box
                      sx={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        gap: 1,
                        mb: 0.75,
                      }}
                    >
                      <Box
                        sx={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 0.75,
                          minWidth: 0,
                        }}
                      >
                        <Typography
                          sx={{
                            fontFamily: fontFamilies.display,
                            fontSize: 10,
                            fontWeight: 700,
                            letterSpacing: '.6px',
                            textTransform: 'uppercase',
                            color: 'text.disabled',
                            flexShrink: 0,
                          }}
                        >
                          {item.label}
                        </Typography>
                        {item.target && (
                          <Typography
                            title={`Paste this into ${item.target}`}
                            sx={{
                              typography: 'mono',
                              fontSize: 10,
                              fontWeight: 700,
                              px: 0.625,
                              py: 0.125,
                              borderRadius: '5px',
                              color: 'primary.main',
                              bgcolor: (t) =>
                                alpha(t.palette.primary.main, 0.12),
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                            }}
                          >
                            {item.target}
                          </Typography>
                        )}
                      </Box>
                      <Box
                        component="button"
                        type="button"
                        onClick={() => {
                          void navigator.clipboard.writeText(item.text);
                        }}
                        sx={{
                          display: 'inline-flex',
                          alignItems: 'center',
                          gap: 0.5,
                          border: 0,
                          bgcolor: 'transparent',
                          cursor: 'pointer',
                          p: 0,
                          fontFamily: fontFamilies.display,
                          fontSize: 9.5,
                          fontWeight: 700,
                          letterSpacing: '.4px',
                          textTransform: 'uppercase',
                          color: 'text.disabled',
                          '&:hover': { color: 'primary.main' },
                          '& svg': { fontSize: 11 },
                        }}
                      >
                        <ContentCopyIcon /> Copy
                      </Box>
                    </Box>
                    {item.label === BETTER_WORDING_ITEM_LABEL &&
                    analysis.userPrompt ? (
                      <WordingComparison
                        originalRequest={analysis.userPrompt}
                        suggestedWording={item.text}
                      />
                    ) : (
                      <Typography sx={APPLY_ITEM_TEXT_SX}>
                        {item.text}
                      </Typography>
                    )}
                  </Box>
                ))}
              </>
            )}
          </Box>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.25 }}>
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              Analyzed with{' '}
              <Box component="span" sx={{ typography: 'mono', fontSize: 12 }}>
                {analysis.model}
              </Box>{' '}
              at {formatTimestamp(analysis.generatedAt)} in{' '}
              {formatDuration(analysis.generationDurationMs * 1e6)}
            </Typography>
            <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}>
              <CopyAnalysisButton analysisText={analysis.analysis} />
              <GhostButton
                tone="primary"
                onClick={onRegenerate}
                disabled={isRegenerating}
              >
                <AutorenewIcon /> Regenerate
              </GhostButton>
            </Box>
          </Box>
        </Box>
      );
    }

    return (
      <Box sx={{ py: 2 }}>
        <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 2 }}>
          Send this trace's overview, prompt, tool calls and response to a
          locally-running Ollama model for a review of two things: how well the
          agent executed — tool choices, repeated or redundant work, how it
          recovered from errors — and how well the prompt set it up. It answers
          with what went wrong, then an "Apply this" section: a rule to paste
          into your CLAUDE.md, the tools to use instead, and — beside what you
          actually typed — how to word a request like this one next time. The
          result is saved and shown here again next time you open this trace.
        </Typography>
        <GhostButton onClick={onRunAnalysis}>Run analysis</GhostButton>
      </Box>
    );
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth={false}
      slotProps={{
        paper: {
          sx: {
            width: 'min(920px, 92vw)',
            // Grows to fit its content instead of scrolling internally; only
            // falls back to scrolling as a whole (never nested with the fixed-
            // height analysis box below) on a viewport too short for the rest
            // of the dialog's chrome.
            maxHeight: '90vh',
            overflowY: 'auto',
            borderRadius: radii.lg,
          },
        },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 1.5,
          px: 2.5,
          py: 1.75,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        <Typography
          sx={{ fontSize: 14.5, fontWeight: 700, color: 'text.primary' }}
        >
          Analyze trace{' '}
          <Box
            component="span"
            sx={{
              typography: 'mono',
              fontSize: 13,
              fontWeight: 400,
              color: 'text.secondary',
            }}
          >
            · {traceId}
          </Box>
        </Typography>
        <IconButton
          onClick={onClose}
          aria-label="Close"
          size="small"
          sx={{
            border: 1,
            borderColor: 'divider',
            borderRadius: '8px',
            color: 'text.secondary',
            '&:hover': { color: 'primary.main', borderColor: 'primary.main' },
          }}
        >
          <CloseIcon sx={{ fontSize: 16 }} />
        </IconButton>
      </Box>
      <DialogContent sx={{ px: 2.5, py: 2, overflowY: 'visible' }}>
        {/* Wraps the whole body, not just the finished review: the streaming
            draft renders through the same markdown component, three levels down
            inside the progress view. */}
        <CallCitationContext.Provider value={citationTarget}>
          {renderBody()}
        </CallCitationContext.Provider>
      </DialogContent>
    </Dialog>
  );
};

/**
 * What a run looks like while it is happening: all four phases as a checklist
 * — done ones checked, the current one spinning, later ones dim — over the
 * panel the review will land in — a skeleton until the model starts writing,
 * then the draft itself.
 *
 * A checklist, not a single active-phase line: the four phases are fixed and
 * known up front, so showing all of them states "how much is left" at a
 * glance instead of asking the reader to track a step counter. The container's
 * MINIMUM_PHASE_DISPLAY_MS pacing (see AnalyzeTraceDialog.tsx) is what makes
 * this legible — without it, the two phases that finish in single-digit
 * milliseconds would check off before a render ever painted them.
 *
 * The character count travels with whichever row is active, live, for the
 * whole run — not just as a structured-output fallback — because it is the
 * one signal that keeps visibly moving regardless of phase or output path.
 */
const AnalysisRunProgressView = ({
  progress,
}: {
  progress: AnalysisRunProgress;
}) => {
  const activeIndex = progress.phases.findIndex(
    (phase) => phase.key === progress.activeKey,
  );
  const isDrafting =
    progress.draftText.length > 0 || progress.draftCharacters > 0;

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.25, py: 1 }}>
      {/* One live region, so a phase completing is announced rather than
          silently swapped — it is the only thing on screen that moves for
          minutes at a time. */}
      <Box
        aria-live="polite"
        sx={{ display: 'flex', flexDirection: 'column', gap: 1.1 }}
      >
        {progress.phases.map((phase, i) => {
          const state =
            activeIndex < 0
              ? 'pending'
              : i < activeIndex
                ? 'done'
                : i === activeIndex
                  ? 'active'
                  : 'pending';
          return (
            <Box
              key={phase.key}
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 1.25,
                fontSize: 13,
                fontWeight: state === 'active' ? 600 : 400,
                color:
                  state === 'pending'
                    ? 'text.disabled'
                    : state === 'active'
                      ? 'text.primary'
                      : 'text.secondary',
                transition: 'color .2s ease',
              }}
            >
              <Box
                sx={{
                  width: 18,
                  height: 18,
                  borderRadius: '50%',
                  flexShrink: 0,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  boxSizing: 'border-box',
                  ...(state === 'done' && {
                    bgcolor: 'primary.main',
                    color: 'primary.contrastText',
                  }),
                  ...(state === 'active' && {
                    border: '2px solid',
                    borderColor: 'divider',
                    borderTopColor: 'primary.main',
                    animation: 'analysisPhaseSpin .9s linear infinite',
                    '@keyframes analysisPhaseSpin': {
                      to: { transform: 'rotate(360deg)' },
                    },
                  }),
                  ...(state === 'pending' && {
                    border: '2px solid',
                    borderColor: 'divider',
                  }),
                }}
              >
                {state === 'done' && <CheckIcon sx={{ fontSize: 11 }} />}
              </Box>
              <Box component="span">
                {phase.label}
                {phase.stepCount > 1
                  ? ` — pass ${phase.stepNumber} of ${phase.stepCount}`
                  : null}
              </Box>
              {state === 'active' && isDrafting && (
                <Box
                  component="span"
                  sx={{
                    typography: 'mono',
                    fontSize: 11,
                    color: 'text.secondary',
                    ml: 'auto',
                  }}
                >
                  {progress.draftCharacters.toLocaleString()} characters
                </Box>
              )}
            </Box>
          );
        })}
        {progress.phases.length === 0 && (
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1.25,
              fontSize: 13,
              color: 'text.primary',
              minHeight: 20,
            }}
          >
            <CircularProgress size={16} thickness={6} />
            Starting the analysis…
          </Box>
        )}
      </Box>
      {progress.draftText ? (
        <AnalysisDraft draftText={progress.draftText} />
      ) : (
        <AnalysisSurfacePlaceholder isDrafting={isDrafting} />
      )}
      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
        Running locally against Ollama — this can take a few seconds up to a
        couple of minutes.
      </Typography>
    </Box>
  );
};

/**
 * The blinking block caret shared by the live draft and, below, the
 * structured-output placeholder — one animation definition so the two never
 * drift into looking like different indicators of the same "still writing"
 * fact.
 */
const DraftCursor = ({ sx }: { sx?: object }) => (
  <Box
    component="span"
    aria-hidden
    sx={{
      display: 'inline-block',
      width: '0.5em',
      height: '1em',
      bgcolor: 'primary.main',
      animation: 'analysisDraftCaret 1s steps(2, start) infinite',
      '@keyframes analysisDraftCaret': { '50%': { opacity: 0 } },
      ...sx,
    }}
  />
);

/**
 * The analysis panel before there is any TEXT to put in it — same box, same
 * height, a single softly pulsing block. It exists to hold that height:
 * without it the dialog opened at the size of a checklist and then jumped to a
 * half-screen panel the instant the first delta landed, which is the one
 * moment the reader is most likely to be looking at it.
 *
 * Deliberately not shaped like the review (no fake heading/bullet lines) —
 * that read as more specific than the placeholder can honestly be, since the
 * model hasn't written anything yet to shape it against.
 *
 * `isDrafting` is true once the run has actually started producing output
 * (`draftCharacters > 0`) but is on the structured-output path, where `delta`
 * carries no renderable text at all (see `streamTraceAnalysis`'s own doc
 * comment) — so this box is what a reader watches for the whole findings
 * pass, not just for the instant before the first delta. Without a caret here
 * too, that whole stretch reads as a dead pulse with no sign the model is
 * still working, which is the exact "did this die?" feeling the draft
 * caret exists to prevent in the free-form case.
 *
 * `aria-hidden` because it says nothing the checklist's live region above
 * hasn't already said.
 */
const AnalysisSurfacePlaceholder = ({
  isDrafting,
}: {
  isDrafting: boolean;
}) => (
  <Box
    aria-hidden
    sx={{
      ...ANALYSIS_SURFACE_SX,
      height: ANALYSIS_SURFACE_HEIGHT,
      overflowY: 'hidden',
      position: 'relative',
    }}
  >
    <Box
      sx={{
        height: '100%',
        borderRadius: radii.sm,
        bgcolor: 'divider',
        animation: 'analysisPlaceholderPulse 1.6s ease-in-out infinite',
        '@keyframes analysisPlaceholderPulse': {
          '0%, 100%': { opacity: 0.35 },
          '50%': { opacity: 0.65 },
        },
      }}
    />
    {isDrafting && (
      <DraftCursor sx={{ position: 'absolute', top: 10, left: 10 }} />
    )}
  </Box>
);

/**
 * The half-written review, in the same panel the finished one lands in.
 *
 * Sticks to the bottom as text arrives, but only while the reader is already
 * there: scrolling up to re-read something the model wrote earlier must not be
 * yanked back down by the next delta. `SCROLL_STICK_THRESHOLD_PX` is the slack
 * that keeps "at the bottom" true across sub-pixel rounding and the reflow a
 * newly-closed markdown block causes.
 *
 * Takes the panel's full height from its first character rather than growing
 * into it, so it lands exactly where the skeleton it replaces was standing and
 * the dialog stops resizing under the reader for the rest of the run.
 */
const AnalysisDraft = ({ draftText }: { draftText: string }) => {
  const draftRef = useRef<HTMLDivElement | null>(null);
  const isPinnedToBottomRef = useRef(true);

  useEffect(() => {
    const draftElement = draftRef.current;
    if (!draftElement || !isPinnedToBottomRef.current) {
      return;
    }
    draftElement.scrollTop = draftElement.scrollHeight;
  }, [draftText]);

  return (
    <Box
      ref={draftRef}
      onScroll={() => {
        const draftElement = draftRef.current;
        if (!draftElement) {
          return;
        }
        const distanceFromBottom =
          draftElement.scrollHeight -
          draftElement.scrollTop -
          draftElement.clientHeight;
        isPinnedToBottomRef.current =
          distanceFromBottom <= SCROLL_STICK_THRESHOLD_PX;
      }}
      sx={{ ...ANALYSIS_SURFACE_SX, height: ANALYSIS_SURFACE_HEIGHT }}
    >
      <AnalysisMarkdown>{draftText}</AnalysisMarkdown>
      {/* A cursor, so a pause between deltas reads as the model thinking rather
          than as the stream having died. */}
      <DraftCursor sx={{ ml: 0.25, verticalAlign: 'text-bottom' }} />
    </Box>
  );
};

const SCROLL_STICK_THRESHOLD_PX = 24;

// Was previously a plain GhostButton whose onClick only wrote to the
// clipboard — no confirmation, unlike every other copy affordance on this
// page (SpanDetailDock's "Copy error", the JSON modal's "Copy"). Local
// isCopied state now flips the label to "Copied!" for 1.2s, matching them.
const CopyAnalysisButton = ({ analysisText }: { analysisText: string }) => {
  const [isCopied, setIsCopied] = useState(false);
  return (
    <GhostButton
      tone="info"
      onClick={() => {
        void navigator.clipboard.writeText(analysisText);
        setIsCopied(true);
        setTimeout(() => setIsCopied(false), 1200);
      }}
    >
      <ContentCopyIcon /> {isCopied ? 'Copied!' : 'Copy all'}
    </GhostButton>
  );
};

export default AnalyzeTraceDialogView;
