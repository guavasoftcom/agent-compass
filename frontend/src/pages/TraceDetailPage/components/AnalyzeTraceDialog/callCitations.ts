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

// A review cites its evidence by call number — "Call 20 was an outlier", "calls
// 15 and 16 read the same file" — and those numbers are the timeline's, not the
// waterfall's: the backend numbers only tool calls and model requests, in trace
// order, while the waterfall's index badge counts every span in DFS order (see
// TraceCallNumbering on the backend). So a citation named a row the reader had no
// way to find. This module turns each cited number into something clickable; the
// row it lands on carries the matching `call N` badge.

// One citation phrase: the word `call`/`calls` followed by one or more numbers,
// however the model chained them ("calls 15 and 16", "calls 3, 4 and 7", "calls
// 31 through 95"). Matching the phrase rather than a bare number is what keeps
// the other numbers in a review — durations, token counts, dollar figures — from
// being linked to a call they have nothing to do with.
//
// `number`/`numbers`/`#` is optional between the two, and is not a nicety: the
// prompt template asks for citations in the words "cite call numbers", and the
// model duly writes them back that way. On trace bc222c551f5acf3c77fc44f8bc53c0f8
// every citation in "What went wrong" read `(call numbers 27, 29, 31, 35, 37)`
// and not one of them linked, while the two bare `at call 3` in "What went well"
// did — the reader sees the linking work in one section and silently fail in the
// section they are actually reading. Trace 9b65e40faab46fe018b1065e7dd8d63a is the
// same failure with a third phrasing: `call(s) 15, 145, 153` — `call(?:s)?` alone
// doesn't match the literal "(s)", so `call(s)` must be matched explicitly rather
// than folded into the plural suffix.
const CALL_CITATION_PHRASE =
  /\bcall(?:\(s\)|s)?\s+(?:numbers?\s+)?#?\d+(?:\s*(?:,|and|&|through|to|–|-)\s*#?\d+)*/gi;
const CALL_NUMBER = /\d+/g;

// The one citation shape that appears *inside* backticks: `TraceAnalysisPromptBuilder`
// appends the literal text "(repeat of call N)" to a timeline line when a request
// repeats an earlier one, and the model quotes that whole timeline line back
// verbatim as inline code — e.g. `` `llm_request (repeat of call 11)` `` — rather
// than as prose. `CALL_CITATION_PHRASE` is deliberately not reused inside inline
// code: it would just as happily match a real shell/file literal like
// `` `grep -n "call 20" file.txt` ``, and unlike prose there is no way to tell a
// genuine citation from a coincidence once the text is something the reader is
// meant to copy. This phrase is narrow enough to be safe there: the backend only
// ever writes it in this exact shape, so matching it by that shape (rather than
// the general "call"-plus-numbers rule) can't mistake an unrelated backtick
// literal for a citation.
const REPEAT_OF_CALL_PHRASE = /\(repeat of call (\d+)\)/gi;

const CALL_HREF_PREFIX = '#call-';
const CALL_HREF = /^#call-(\d+)$/;

// The distinct href scheme a citation to a call number OUTSIDE the trace's
// valid range is rewritten to. See remarkCallCitations for why this is no
// longer folded into plain text.
const UNKNOWN_CALL_HREF_PREFIX = '#unknown-call-';
const UNKNOWN_CALL_HREF = /^#unknown-call-(\d+)$/;

export type CallCitationSegment =
  | { kind: 'text'; text: string }
  | { kind: 'call'; text: string; callNumber: number };

/**
 * One run of text split into plain stretches and the call numbers cited inside
 * it. The words around the numbers ("calls", ", ", " and ") stay text — only the
 * digits become the target, so the sentence still reads as written.
 *
 * Pure, and the single definition of what counts as a citation: the remark plugin
 * below is a thin wrapper over it, which is what makes the rule testable without
 * a markdown tree.
 */
export const splitCallCitations = (text: string): CallCitationSegment[] => {
  const segments: CallCitationSegment[] = [];
  let plainTextStart = 0;
  // Fresh regex objects per call: both patterns are global, and a shared lastIndex
  // across calls would make the same input parse differently the second time.
  const phrases = new RegExp(CALL_CITATION_PHRASE.source, CALL_CITATION_PHRASE.flags);
  let phrase = phrases.exec(text);
  while (phrase !== null) {
    const numbers = new RegExp(CALL_NUMBER.source, CALL_NUMBER.flags);
    let number = numbers.exec(phrase[0]);
    while (number !== null) {
      const numberStart = phrase.index + number.index;
      if (numberStart > plainTextStart) {
        segments.push({ kind: 'text', text: text.slice(plainTextStart, numberStart) });
      }
      segments.push({
        kind: 'call',
        text: number[0],
        callNumber: Number(number[0]),
      });
      plainTextStart = numberStart + number[0].length;
      number = numbers.exec(phrase[0]);
    }
    phrase = phrases.exec(text);
  }
  if (plainTextStart < text.length) {
    segments.push({ kind: 'text', text: text.slice(plainTextStart) });
  }
  return segments;
};

/**
 * The inline-code counterpart of {@link splitCallCitations}: finds only the
 * "(repeat of call N)" phrase, and links just the digits inside it — the
 * surrounding text (the span name, the parens) stays part of the quoted
 * literal. See {@link REPEAT_OF_CALL_PHRASE} for why this needs its own,
 * narrower pattern instead of reusing the prose one.
 */
const REPEAT_OF_CALL_PREFIX = '(repeat of call ';

export const splitRepeatOfCallCitation = (text: string): CallCitationSegment[] => {
  const segments: CallCitationSegment[] = [];
  let plainTextStart = 0;
  const phrases = new RegExp(REPEAT_OF_CALL_PHRASE.source, REPEAT_OF_CALL_PHRASE.flags);
  let phrase = phrases.exec(text);
  while (phrase !== null) {
    const numberText = phrase[1];
    const numberStart = phrase.index + REPEAT_OF_CALL_PREFIX.length;
    if (numberStart > plainTextStart) {
      segments.push({ kind: 'text', text: text.slice(plainTextStart, numberStart) });
    }
    segments.push({ kind: 'call', text: numberText, callNumber: Number(numberText) });
    plainTextStart = numberStart + numberText.length;
    phrase = phrases.exec(text);
  }
  if (plainTextStart < text.length) {
    segments.push({ kind: 'text', text: text.slice(plainTextStart) });
  }
  return segments;
};

/** The link target a cited call number is rewritten to, and its inverse. */
export const callCitationHref = (callNumber: number) => `${CALL_HREF_PREFIX}${callNumber}`;

export const callNumberFromHref = (href: string | undefined): number | null => {
  const match = href ? CALL_HREF.exec(href) : null;
  return match ? Number(match[1]) : null;
};

/**
 * The link target a citation to a call number OUTSIDE the trace's valid range
 * is rewritten to, and its inverse. Distinct from {@link callCitationHref} so
 * the view can tell the two apart and render an unknown citation as a marked,
 * non-clickable span rather than a link that goes nowhere.
 */
export const unknownCallCitationHref = (callNumber: number) =>
  `${UNKNOWN_CALL_HREF_PREFIX}${callNumber}`;

export const unknownCallNumberFromHref = (href: string | undefined): number | null => {
  const match = href ? UNKNOWN_CALL_HREF.exec(href) : null;
  return match ? Number(match[1]) : null;
};

// The subset of mdast this walker needs. Typed structurally rather than pulled
// from @types/mdast, which isn't a dependency and would be one for four fields.
interface MarkdownNode {
  type: string;
  value?: string;
  url?: string;
  children?: MarkdownNode[];
}

// Node types whose text is quoted rather than prose: a file path or a command in
// backticks can carry the word "call" and a number, and rewriting inside one on
// the general prose rule would corrupt something the reader is meant to copy.
// `link`/`linkReference` are excluded for a structural reason instead — a link
// inside a link is not valid markdown. `inlineCode` still gets one narrow,
// dedicated rewrite below (the "(repeat of call N)" phrase) before falling
// through to this untouched treatment.
const UNTOUCHED_NODE_TYPES = new Set(['code', 'inlineCode', 'link', 'linkReference']);

/**
 * remark plugin: rewrites every cited call number into a link — to
 * {@link callCitationHref} when the number is one of the trace's real calls
 * (`AnalyzeTraceDialogView` renders that as a button), or to
 * {@link unknownCallCitationHref} when it is not (rendered as a subdued,
 * non-clickable span carrying a title explaining why).
 *
 * `isKnownCall` is what keeps a citation honest. The review is model output,
 * and a number it invents — one past the end of the trace's timeline — has no
 * row to land on. Before the windowed-review rework this could also mean a
 * call elided from a timeline truncated to fit a prompt budget, so an
 * out-of-range number was ambiguous: invented, or just hidden. That ambiguity
 * is gone now that an oversized timeline is split into passes instead of
 * having calls dropped — every call the trace has is always in range, so any
 * citation outside it is unambiguously something the model made up, and is
 * marked as such rather than rendered indistinguishably from ordinary prose.
 * The caller answers from the spans it has, so a trace still loading simply
 * renders every citation as unknown until they arrive.
 */
export const remarkCallCitations =
  (isKnownCall: (callNumber: number) => boolean) => () => (tree: MarkdownNode) => {
    // Shared by both cases below: split `value` with `splitFn`, and build one
    // node per segment via `wrapText` (renders the words around a citation) — a
    // linked segment's own text still goes through `wrapText`, so an inline-code
    // citation stays monospace inside the link (`[`code`](url)` is valid mdast,
    // same as this app's other markdown links). Every call-shaped segment is
    // rewritten to a link now, known or not — a known call links to
    // `callCitationHref` (rendered as a button), an unknown one to
    // `unknownCallCitationHref` (rendered as a marked, non-clickable span).
    // Returns null only when there is no call-shaped segment at all, so the
    // caller can leave a citation-free node untouched rather than replacing it
    // with an equivalent split.
    const rewriteValue = (
      value: string,
      splitFn: (value: string) => CallCitationSegment[],
      wrapText: (text: string) => MarkdownNode,
    ) => {
      const segments = splitFn(value);
      const hasCallCitation = segments.some((segment) => segment.kind === 'call');
      if (!hasCallCitation) {
        return null;
      }
      return segments.map((segment) => {
        if (segment.kind !== 'call') {
          return wrapText(segment.text);
        }
        const known = isKnownCall(segment.callNumber);
        return {
          type: 'link',
          url: known
            ? callCitationHref(segment.callNumber)
            : unknownCallCitationHref(segment.callNumber),
          children: [wrapText(segment.text)],
        };
      });
    };
    const rewriteChildren = (node: MarkdownNode) => {
      if (!node.children) {
        return;
      }
      const rewritten: MarkdownNode[] = [];
      node.children.forEach((child) => {
        // `llm_request (repeat of call 14)` — a real timeline line the model
        // quoted verbatim as inline code. Trace 9b65e40faab46fe018b1065e7dd8d63a
        // is why this runs before the general `UNTOUCHED_NODE_TYPES` check
        // below: without it, the number never links, because inline code is
        // otherwise left fully alone (see that set's own comment for why).
        if (child.type === 'inlineCode' && child.value) {
          const rewrittenValue = rewriteValue(child.value, splitRepeatOfCallCitation, (text) => ({
            type: 'inlineCode',
            value: text,
          }));
          rewritten.push(...(rewrittenValue ?? [child]));
          return;
        }
        if (UNTOUCHED_NODE_TYPES.has(child.type)) {
          rewritten.push(child);
          return;
        }
        if (child.type !== 'text' || !child.value) {
          rewriteChildren(child);
          rewritten.push(child);
          return;
        }
        // No citation at all — the node is kept whole rather than re-split
        // into text nodes that render the same: an untouched tree is easier
        // to reason about. A citation this trace cannot resolve is NOT this
        // case any more (see rewriteValue above) — it still gets rewritten,
        // just to an unknown-call link instead of a known-call one.
        const rewrittenValue = rewriteValue(child.value, splitCallCitations, (text) => ({
          type: 'text',
          value: text,
        }));
        rewritten.push(...(rewrittenValue ?? [child]));
      });
      node.children = rewritten;
    };
    rewriteChildren(tree);
  };
