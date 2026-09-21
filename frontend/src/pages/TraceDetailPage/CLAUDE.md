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
# Trace detail page

Single-trace span waterfall: renders every span in a trace as a horizontally-scaled waterfall,
with a minimap zoom brush, a per-span inspector drawer (timing, tokens, attributes, span events,
and correlated log entries), and error navigation. Reached from `TracesPage` by clicking a trace row;
mounted at `/traces/:traceId`. Backend counterpart: `TracesController` →
`TraceService` / `LogService` (`backend/.../controller/TracesController.java`).

## Architecture (read this first)

This page follows the standard container/presentational split from
[frontend/CLAUDE.md](../../../CLAUDE.md) but is more complex than most: the container
(`TraceDetailPage.tsx`) runs two queries and pre-computes five expensive `useMemo` derivations
before passing plain props to the view; the view owns all interaction state (collapse, selection,
zoom window) and renders the full layout directly — it does not use a page-scoped context.

```
TraceDetailPage.tsx          container — useParams, two useQuery calls, five useMemo
                             derivations (span tree, indices, depths, trace window,
                             descendant error counts, self time, log bucketing, sessionId,
                             agent-dispatch coloring), passes ~15 plain props to the view
TraceDetailPageView.tsx      view — owns all UI state (collapsed set, selected span,
                             zoom ZoomView), derives visible row list per-render,
                             computes bar geometry, and composes all sub-components
TraceDetailPageView.test.tsx vitest coverage for the view (renderWithProviders, prop fixtures)
```

The view renders five sub-components inline (no further drilling), plus a sixth mounted only
while its dialog is open:

```
TraceDetailHeader  ──  breadcrumb + SummaryStrip KPIs (container/view split)
WaterfallToolbar   ──  "Span waterfall" label + legend + Expand/Collapse all (tool spans
                       only — see Collapse and expand) + "Analyze trace" + Next error
TraceMinimap       ──  full-trace overview with drag-to-zoom brush
SpanWaterfallRow   ──  one row per visible span (single-file component, no view split)
SpanInspectorDrawer ── right-side width-resizable drawer for the selected span
AnalyzeTraceDialog ──  "Analyze trace" dialog — review of this trace by a local Ollama
                       model (agent execution quality + request quality), answering in two
                       sections: "What went wrong", each bullet ending in a Fix, and
                       "Apply this" — three lines (an instruction rule and the tool swaps,
                       both paste-ready; then better wording, shown against the request the
                       reader actually sent) — preceded by an optional
                       "What went well" when the backend verified a positive. The
                       instruction rule carries a
                       target (`CLAUDE.md` or `skill:<name>`) rendered as a chip, since a
                       rule belonging in a skill's own definition is useless written
                       against CLAUDE.md — see the Apply-this gotcha below. Saves the
                       result (container/view
                       split; only mounted while
                       `analyzeTraceDialogOpen` is true, matching the `errorCount ?` / `canToggleAll ?`
                       conditional-render idiom the toolbar buttons already use)
```

**Cross-page utilities.** The page imports from `../TracesPage/` rather than duplicating:

- `fetchSpansForTrace` from `../TracesPage/tracesApi` — wraps `fetchTraceSpans` from the shared
  `api/` with sample-data support.
- `NANOS_PER_MILLI`, `formatDuration`, `formatTokens`, `formatUsd` from `../TracesPage/tracesApi`.
- `tokenBreakdownForSpan` from `../TracesPage/tokenBreakdown` — extracts `input / output /
  cacheCreate / cacheRead` counts from any span's attribute bag; the same module's
  `fullRateTokens`, `cacheHitRateLabel`, and `tokenShareLabel` are what the header card and the
  waterfall chips scale and label with.
- `isToolCallSpan` from `../TracesPage/traceDerivations` — the header's Tool calls tile counts
  spans through the same rule the Traces page uses, so the two never disagree; the container
  reuses it to pick which spans "Collapse all" folds.
- `spanColor` from `../TracesPage/components/traceColors` — maps span name to a service hue
  (used by the minimap); see [../TracesPage/CLAUDE.md](../TracesPage/CLAUDE.md) for the
  hue-to-service mapping.

## Files

```
TraceDetailPage/
├── TraceDetailPage.tsx         container — useParams + two queries + five useMemo
├── TraceDetailPageView.tsx     view — all interaction state + bar geometry + layout
├── spanTree.ts                 SpanTree/TraceWindow types + buildSpanTree/buildSpanIndices/
│                               buildSpanDepths/computeTraceWindow — pure functions, no React
├── logBuckets.ts               bucketLogsBySpan — attaches each LogRow to its OTLP spanId;
│                               falls back to root span for logs with no usable span_id; for
│                               tool_decision/tool_result, only overrides that when the log's
│                               own span_id lands outside the tool call's whole span family
│                               (wrapper claude_code.tool span + its tool.execution/
│                               blocked_on_user sub-spans) — re-pointing at the wrapper via
│                               tool_use_id, never guessing a specific sub-span (see Log
│                               bucketing below); sorts by event.sequence then event.timestamp
├── logBuckets.test.ts           tool_use_id fallback correlation + the blocked_on_user/
│                               execution "already correctly attributed, leave alone" cases +
│                               span_id/root fallback coverage
├── severity.ts                 severityLabel(n) + severityColor(n) — OTLP severityNumber
│                               thresholds → 'TRACE'/'DEBUG'/'INFO'/'WARN'/'ERROR'/'FATAL'
│                               and MUI chip color; used by LogEntry and SpanInspectorDrawer
├── attrFormat.ts               attrValueAsString(v) — objects → JSON, null/undefined →
│                               literal string, primitives → String()
├── agentDispatch.ts             buildAgentDispatchColoring(tree, spans) → per-span coloring for the
│                               waterfall's subagent-dispatch highlight: a dispatch span (tool_name
│                               === 'Agent') and its whole subtree share one color, keyed by
│                               subagent_type — two "Explore" dispatches share a color, "Explore" and
│                               "Plan" don't. Colors come from colorForAgentDispatchIndex (theme.ts)
│                               — a dedicated palette, disjoint from the waterfall toolbar's six
│                               fixed legend colors, not colorForIndex/CHART_PALETTE — assigned in
│                               first-seen DFS order over DISTINCT labels (the same order
│                               buildSpanIndices walks for the row index badge), not per dispatch
│                               instance. A dispatch with no readable subagent_type falls back to the
│                               generic label "Subagent" and still gets a color. One DFS walk over
│                               the tree resolves every span directly to `colorBySpanId.get(spanId):
│                               string | undefined` (absent for a main-loop span), a parallel
│                               `labelBySpanId.get(spanId): string | undefined` with the identical
│                               key set (same dispatch's label, not color — SpanInspectorDrawer
│                               pairs the two to render the header's "which subagent" name chip for
│                               the selected span), plus an ordered `legend:
│                               AgentDispatchLegendEntry[]` (`{ label, color, dispatchSpanIds }`)
│                               for the toolbar — `dispatchSpanIds` is every dispatch span sharing
│                               that label, first-seen order, what lets a legend click jump to (and
│                               cycle through) each one — see the gotcha below for the
│                               innermost-dispatch-wins rule on nested dispatches
├── agentDispatch.test.ts        vitest coverage: no dispatches (empty legend, nothing colored), a
│                               dispatch's subtree colored (and labeled) while a sibling main-loop
│                               span isn't, two same-type dispatches sharing a color and one legend
│                               entry whose dispatchSpanIds lists both in DFS order, two
│                               different-type dispatches not sharing one, the generic-label
│                               fallback, and nested dispatches resolving to the innermost (color
│                               and label both)
├── spanRelations.ts            buildSpanRelations(span, spans, sortedToolCalls) → the related calls
│                               the drawer shows for the selected span, plus resolveToolCall, the
│                               rule for whether a row is a call at all. `sortedToolCalls` is every
│                               tool call in the trace sorted by start time — `sortedToolCallsOf
│                               (spans)`, exported so a caller invoking this once per span selection
│                               (`CallContextSection.tsx`) memoizes that list once per trace rather
│                               than it being re-filtered/re-sorted on every selection change.
│                               Selects by RELATION, not position: earlier/later calls against the
│                               same file_path, each stating how many calls away it is. The +/-1
│                               window was measured against the live database and rejected — only
│                               36% of edits whose file was read earlier in the trace had that read
│                               in the preceding call (median gap 2, p90 gap 15). Returns null for
│                               anything that isn't a tool call. Each RelatedCall carries the target
│                               `spanId`, which is what lets the rendered call number link to that
│                               row. The earlier/later file-relation lists share one
│                               `relatedByFile(candidates, filePath)` pipeline rather than two
│                               copies differing only in which candidate list they scan. A
│                               same-command relation (earlier calls sharing a Bash command's first
│                               two tokens) was removed on request — don't reintroduce it without
│                               checking why
├── spanRelations.test.ts       vitest coverage for the selector: a same-file read 15 calls back, a
│                               later touch, sub-span resolution, and the non-tool-span null
├── spanCallFacts.ts            buildSpanCallFacts(span, spans, logsBySpanId) → what is already
│                               KNOWN about one tool call: the agent's own `description` of its
│                               intent, success/failure + error, the permission decision and
│                               whether it was pre-authorized, and the result size.
│                               The DESCRIPTION is the point: it is the most direct answer to "what
│                               was this call for" and nothing else on the page surfaces it, since
│                               it lives inside the tool_result log's `tool_input` JSON string.
│                               Reachable client-side only because LogService#resolveLeafSpans has
│                               already re-pointed each tool log onto its exact leaf span by
│                               tool_use_id — so the buckets sit on the call's CHILDREN
│                               (tool_result on the execution span, tool_decision on the
│                               approval-wait span), never on the wrapper, which is why this scans
│                               children. Every match is re-checked against the call's own
│                               tool_use_id rather than trusted from the bucket
├── spanCallFacts.test.ts       vitest coverage built from a real trace's shape: the description
│                               parse, resolution from the sub-span, the tool_use_id re-check
│                               rejecting a stray log, a failure with error text, a truncated
│                               tool_input surviving rather than throwing, and all-null when
│                               nothing resolved
├── spanCost.ts                 costOfSpan(span) → SpanRow.costUsd, a per-span cost breakdown figure
│                               (0 when none was logged against it) — NOT the source of the header's
│                               Cost KPI, which reads the backend-authoritative trace total instead;
│                               costOfSpanRequests(logs) sums cost_usd over a span's own api_request
│                               logs, and costOfSelectedSpan(span, logs) prefers the stamped figure
│                               and falls back to the logs — 0 on a claude_code.interaction span,
│                               whose rollup is the trace total (the row/drawer number — see Cost)
├── chipVisibility.ts           ChipFamily ('tok'|'cr'|'cost'|'mdl'|'tool') + loadChipsOff/
│                               persistChipsOff — reads/writes the muted-badge-family set to
│                               localStorage['ac-wf-chips-off']; pure, no React (see Badge
│                               visibility below)
├── summaryStripVisibility.ts   loadOverviewCollapsed/persistOverviewCollapsed — reads/writes the
│                               Overview panel's collapsed flag to
│                               localStorage['ac-wf-overview-collapsed']; pure, no React; same
│                               idiom as chipVisibility.ts (see Gotchas)
├── traceInsightsDerivations.ts pure functions, no React: `classifyToolCall`/`PhaseKind`/
│                               `PHASE_KIND_COLOR_INDEX` — a READ/EDIT/SEARCH/VERIFY/OTHER
│                               taxonomy over a tool call's name, plus the palette index each kind
│                               maps to (paired with `colorForIndex` from `theme.ts`). Named after
│                               a since-removed phase-timeline feature and kept purely because
│                               `components/AnalyzeTraceDialog/summarizeTraceWork.ts` and
│                               `AnalyzeTraceDialogView.tsx` both reuse it to color the Analyze
│                               Trace dialog's Tools/Files chips consistently — see the "no panel,
│                               just a surviving taxonomy" note at the end of this file for the
│                               history and why it lives at the page root rather than in a
│                               component directory
├── components/AnalyzeTraceDialog/callCitations.ts
│                               splitCallCitations (a run of text → plain stretches + the call
│                               numbers cited in it) + the remark plugin that rewrites each cited
│                               number into a #call-N link + callCitationHref/callNumberFromHref.
│                               Pure, no React; see the call-number gotcha
├── components/AnalyzeTraceDialog/summarizeTraceWork.ts
│                               summarizeTraceWork(spans) → { toolCalls, modelCalls, durationMs,
│                               tools, models, files } — the client-side breakdown behind the
│                               summary card's Work stat line and its collapsed-by-default
│                               Tools/Models/Files supplementary sections (Cost is threaded
│                               separately — see the summary-card gotcha below). Pure, no React,
│                               same idiom as callCitations.ts in this folder. Reuses rather than
│                               reinvents: `isToolCallSpan` (../TracesPage/traceDerivations) for
│                               "was this a tool call", `tokenBreakdownForSpan(span).total > 0`
│                               (../TracesPage/tokenBreakdown) for "was this a model call" — the
│                               same two rules TraceDetailHeader's KPIs and WaterfallToolbar's
│                               Collapse-all use, so the card can never disagree with them —
│                               `shortModelName` (../../lib/format) for the Models chip labels,
│                               `classifyToolCall` (../../traceInsightsDerivations)
│                               for each tool count's `kind` (`TraceWorkToolCount`, first-seen per
│                               distinct tool name — the same READ/EDIT/SEARCH/VERIFY/OTHER
│                               taxonomy that module exports purely for this reuse — see its own
│                               "Tool-call classification" section comment), and
│                               `computeTraceWindow` (../../spanTree) for durationMs (guarded to 0
│                               for an empty trace, rather than that function's own 1ms zoom-window
│                               floor). Tool name / file path read off `tool_name` / `file_path`
│                               span attributes, the same keys SpanWaterfallRow/SpanToolBadge
│                               already rely on. Never sent to the backend or to Ollama
├── components/AnalyzeTraceDialog/summarizeTraceWork.test.ts
│                               vitest coverage: empty trace, repeated vs. once-seen tool/model
│                               names (count badge only on the former), each tool's kind
│                               classification (Grep→SEARCH, Bash's own command→VERIFY,
│                               TodoWrite→OTHER), a file touched by more than one tool, and
│                               duration from earliest span start to latest span end
├── components/AnalyzeTraceDialog/fileTypeBadge.ts
│                               fileTypeBadge(path) → { label, color } — the small colored
│                               language badge shown at the start of each Files-section row.
│                               Reads the extension off the filename (`java`→JAVA/orange,
│                               `tsx`→TSX/blue, ...), or a handful of common extension-less
│                               filenames (Dockerfile, Makefile, `.gitignore`) matched by name,
│                               falling back to a neutral badge naming the raw extension (or
│                               "FILE" when there is none) rather than rendering nothing. Pure, no
│                               React — deliberately not a per-language icon/logo package: this
│                               app already favors hand-built SVG/CSS over a component library for
│                               every visualization (frontend/CLAUDE.md's "Charts and grids"), and
│                               `@mui/icons-material` has no Java/Python/Go/Rust glyph to reach for
│                               anyway (just a handful of web-stack ones — Html/Css/Javascript/Php)
├── components/AnalyzeTraceDialog/fileTypeBadge.test.ts
│                               vitest coverage: Java vs. TypeScript badged distinctly, extension
│                               read off the filename only (not a directory segment containing a
│                               dot), case-insensitive matching, a common extension-less filename
│                               (Dockerfile, `.gitignore`) badged by name, an unmapped extension
│                               falling back to itself (uppercased) rather than a blank badge, and
│                               a name with neither an extension nor a filename match → "FILE"
├── traceAnalysisApi.ts         fetchTraceAnalysis — page-local fetcher for the
│                               `/api/traces/{traceId}/analysis` sub-resource (GET = load any
│                               stored analysis, resolves `null` on 404 rather than throwing since
│                               "not analyzed yet" is a normal state). Bypasses the shared
│                               `api/http` `getJson` helper, same pattern as
│                               `SettingsPage/settingsApi.ts#purgeTelemetry`, so the plain-text
│                               error body reaches the dialog instead of being collapsed into a
│                               generic status-text message. `streamTraceAnalysis` is what the
│                               dialog actually calls to run/regenerate an analysis: POST to
│                               `/analysis/stream`, parsing the SSE body frame by frame
│                               (`started`/`plan`/`phase`/`delta`/`done`/`failed`) via the shared
│                               `readServerSentEvents`/`parseServerSentEvent` (`../../lib/
│                               serverSentEvents.ts` — this app's first and, so far, only SSE
│                               consumer; see that module's own doc comment for the generic frame
│                               reader/parser) and resolving with the same `TraceAnalysisResult`
│                               shape `fetchTraceAnalysis` loads for a stored row. It rethrows a
│                               `failed` event's message as an Error, so callers keep one
│                               try/catch shape across both fetchers. There used to be a third,
│                               non-streaming `regenerateTraceAnalysis` POST fetcher; it had no
│                               callers left once the dialog moved onto `streamTraceAnalysis` and
│                               was deleted rather than kept around unused
├── traceAnalysisApi.test.ts    vitest coverage for `streamTraceAnalysis`: event order,
│                               chunk-boundary reassembly, `failed` → thrown Error, and a stream
│                               that ends with no terminal event. The SSE frame reader/parser
│                               itself (`readServerSentEvents`/`parseServerSentEvent`) is only
│                               exercised end-to-end here; its own direct unit tests live in
│                               `../../lib/serverSentEvents.test.ts` next to the module they cover
├── index.ts                    re-exports TraceDetailPage as default
└── components/
    ├── TraceDetailHeader/
    │   ├── TraceDetailHeader.tsx      container — memoizes serviceLabels, the four-way token breakdown,
    │   │                             and model-call/tool-call counts in one pass over spans; max depth
    │   │                             comes from the page's depthBySpanId and cost from the trace-summary
    │   │                             query (traceCostUsd), both threaded down rather than recomputed here
    │   ├── TraceDetailHeaderView.tsx  view — "Observability › Trace detail" breadcrumb, with the combined
    │   │                             IdentityPill (session + trace) right after the h1, + SummaryStrip,
    │   │                             whose KPI tiles are Cost/Duration/Spans/Tool calls/Depth/Errors
    │   │                             (Cost leads, gradient-emphasized)
    │   ├── TraceDetailHeaderView.test.tsx  vitest coverage for the view (renderWithProviders,
    │   │                             prop fixtures)
    │   ├── IdentityPill.tsx           breadcrumb-row identity pill: one bordered/rounded container, two
    │   │                             segments sharing it (no gap, a 1px divider between). Both segments
    │   │                             are plain, uncopyable text — eyebrow label + mono value (`title`
    │   │                             carries the full value). The trace segment additionally carries a
    │   │                             caret and is clickable (the whole segment, no inner icon target),
    │   │                             opening SwitchTraceModal — but only when the session has more than
    │   │                             one distinct trace to offer. Runs its own `fetchSessionPrompts`
    │   │                             query (same key as SwitchTraceModal's own, `enabled: Boolean
    │   │                             (sessionId)` rather than gated on the modal being open) purely to
    │   │                             count `hasTraceAndPrompt`-filtered distinct trace ids before the
    │   │                             first click; `canSwitchTraces` is false — segment inert, no caret,
    │   │                             default cursor — both while that count is still loading (0) and
    │   │                             once resolved to a single-trace session, not just when there's no
    │   │                             `sessionId` at all
    │   ├── SwitchTraceModal.tsx       container — fetches `fetchSessionPrompts(sessionId)` (query key
    │   │                             `['session-prompts', sessionId]`, shared with both SessionsPage's
    │   │                             own query and IdentityPill's count query, so by the time this opens
    │   │                             the data is almost always already in cache) filtered through the
    │   │                             same `hasTraceAndPrompt` predicate IdentityPill counts with
    │   │                             (single source of truth for "is this row a switchable trace"),
    │   │                             then run through `nestSwitchTraceRows` to nest a background-
    │   │                             dispatched subagent's trace under its dispatcher (both now live in
    │   │                             switchTraceRows.ts — see the nesting gotcha below), and on a
    │   │                             non-current row click closes the modal and navigates to
    │   │                             `/traces/:traceId`
    │   ├── switchTraceRows.ts         SwitchTraceRow/NestedSwitchTraceRow types + hasTraceAndPrompt +
    │   │                             nestSwitchTraceRows — pure, no React, colocated (this modal is
    │   │                             the only consumer). nestSwitchTraceRows is a thin wrapper
    │   │                             around the shared ../../../lib/nestDispatchedRows.ts core (see
    │   │                             that module's own doc comment) — this file's wrapper supplies
    │   │                             the traceId/dispatchingTraceId accessors and strips the core's
    │   │                             originalIndex field, which this modal has no use for. Re-exported
    │   │                             from SwitchTraceModalView.tsx for existing import sites
    │   │                             (IdentityPill, tests) — see the nesting gotcha below
    │   ├── switchTraceRows.test.ts    vitest coverage: fast path (no dispatches), one reordered child,
    │   │                             two children kept chronological with only the last clearing
    │   │                             railBelow[0], a chained depth-2 grandchild, an absent/self-
    │   │                             referencing/out-of-order dispatcher all staying top-level, and the
    │   │                             earliest-of-two-shared-trace-ids resolution rule
    │   ├── SwitchTraceModalView.tsx   view — MUI Dialog, `min(800px, 92vw)` wide, `64vh` max height with
    │   │                             internal scroll. Header: "Switch trace · session <mono id>" +
    │   │                             GhostButton Close. Body: one SwitchTraceModalRow per turn, wrapped
    │   │                             in LongValueModalProvider (the same "view formatted" dialog
    │   │                             SpanInspectorDrawer uses — see SwitchTraceModalRow) so a long
    │   │                             ordinary prompt can be opened full-size rather than only ever
    │   │                             showing as a clipped line
    │   ├── SwitchTraceModalView.test.tsx  vitest coverage for the view (renderWithProviders,
    │   │                             prop fixtures)
    │   ├── SwitchTraceModalRow.tsx    one row: 5-column grid — time / prompt / cost / tokens / flag —
    │   │                             newest (current) at the bottom, the order the endpoint already
    │   │                             returns. The current row (matching the page's own traceId) gets the
    │   │                             same `action.selected`-tinted background + `inset 2px 0 0
    │   │                             primary.main` left accent SpanWaterfallRow uses for a selected span,
    │   │                             is inert (no onClick, default cursor), and shows a "current" flag
    │   │                             pill. Other rows are hover+pointer and navigate on click. No ERROR
    │   │                             flag — that needs a per-row cross-reference against each trace's
    │   │                             error count, which the prompts endpoint doesn't carry and which
    │   │                             fetching per-row here would turn into an N+1 on every open; left for
    │   │                             a future backend field. The Prompt cell renders through the shared
    │   │                             `components/PromptSummaryText` (see frontend/CLAUDE.md's
    │   │                             components/ section) rather than its own subagent-notification
    │   │                             branching: an ordinary prompt renders through the `renderOrdinary`
    │   │                             render-prop, wired here to `LongAttrValue` (clamp at
    │   │                             LONG_VALUE_ATTR, "view formatted (N chars)" button → the shared
    │   │                             modal, jsonrepair-then-raw-text display — the same path the drawer's
    │   │                             attribute grids use); a `<task-notification>` envelope instead shows
    │   │                             only the muted-italic "SUBAGENT · <summary text>" line — no way to
    │   │                             open the raw envelope from this row (an earlier revision added a
    │   │                             "view envelope" button wired to the same modal; removed as
    │   │                             unnecessary — the summary is judged sufficient here). `title` still
    │   │                             carries the full raw prompt either way
    │   ├── SummaryStrip.tsx           collapsible "Overview" panel: header (click to collapse) +
    │   │                             optional "Prompt" row (rendered through `components/PromptSummaryText`,
    │   │                             same as SwitchTraceModalRow — no `renderOrdinary` passed, so an
    │   │                             ordinary prompt falls through to its plain-text default) + the KPI
    │   │                             tile row + TokenCompositionCard
    │   │                             (one log-scaled list — one row per nonzero token category, sorted
    │   │                             by magnitude, swatch + label [+ "0.1×" rate tag, cache-read row
    │   │                             only] + bar + value + share%, plus the "N% cached" chip, model-call
    │   │                             count, and total cost) + MetaFooter (root span, services, started —
    │   │                             no ids; those live in the header's IdentityPill), laid out
    │   │                             space-between across the card's full width. Tooltip fires only
    │   │                             when a value element overflows; the collapsed state persists
    │   │                             to localStorage across traces and reloads (see Gotchas)
    │   └── index.ts
    ├── WaterfallToolbar/
    │   ├── WaterfallToolbar.tsx       toolbar row: "Span waterfall" label + a six-key legend
    │   │                             (error/tokens/cache/cost/model/tool — one key per badge
    │   │                             family, `ok` dropped as uninformative) where five of the
    │   │                             six keys double as SpanWaterfallRow badge-visibility
    │   │                             toggles (chipsOff — see Badge visibility below; `error`
    │   │                             is not a toggle) + GhostButton "Expand all / Collapse all"
    │   │                             (hidden when there is nothing to fold — see Collapse and
    │   │                             expand) + "Analyze trace" (AutoAwesomeIcon, always shown —
    │   │                             opens AnalyzeTraceDialog) + "Next error" (when errors > 0).
    │   │                             Optional `agentLegend` prop (agentDispatch.ts) appends one
    │   │                             swatch per distinct dispatched agent type through the same
    │   │                             `!key.family` branch 'error' already renders through — reactKey
    │   │                             `agent:<label>` rather than the label itself, since a
    │   │                             subagent_type is live data and the fixed six keys' bare `label`
    │   │                             react-key is only safe because none of them collide with each
    │   │                             other. Empty/absent renders nothing new. Unlike 'error', these
    │   │                             entries are clickable (`onAgentLegendClick` prop) — jump to
    │   │                             (and, via the entry's own `dispatchSpanIds`, cycle through) that
    │   │                             agent type's dispatch spans; see the Subagent-dispatch coloring
    │   │                             section's click-to-jump addendum below
    │   └── index.ts
    ├── AnalyzeTraceDialog/
    │   ├── AnalyzeTraceDialog.tsx      container — `useQuery(['trace-analysis', traceId])`
    │   │                             (`enabled: open`) for any stored analysis, a second
    │   │                             `useQuery(['trace-cost-breakdown', traceId])` for the
    │   │                             per-subagent cost list fed to the summary card's Cost
    │   │                             section, `enabled: open && supplementaryOpen` — that section
    │   │                             starts collapsed (TraceSummaryCard's own "Show Tools,
    │   │                             Models, Files, Cost" toggle), so this stays disabled until
    │   │                             the reader actually expands it rather than firing on every
    │   │                             dialog open regardless of whether the section is ever
    │   │                             opened; `supplementaryOpen` is a container `useState(false)`
    │   │                             flipped true by `onSupplementaryExpand`, a callback threaded
    │   │                             down through `AnalyzeTraceDialogView` to
    │   │                             `TraceSummaryCard`'s own toggle handler (the view keeps
    │   │                             owning `detailsOpen` itself — this is a one-way notification,
    │   │                             not a controlled prop) — plus a `useMutation` for "Run
    │   │                             analysis"/"Regenerate" whose
    │   │                             `onSuccess` writes the fresh result straight into that
    │   │                             same query's cache entry (`queryClient.setQueryData`) —
    │   │                             see the traceAnalysisApi.ts gotcha below for why this is
    │   │                             the one page-local fetcher that bypasses `getJson`. The
    │   │                             mutation runs `streamTraceAnalysis`, so the container also
    │   │                             holds an `AnalysisRunProgress` state (phases, active/draft
    │   │                             KEY — not phase name, see the multi-pass gotcha below —
    │   │                             accumulated draft text, character count) fed by the stream's
    │   │                             callbacks and reset in `onMutate` — react-query models a
    │   │                             call's result, and everything interesting here happens
    │   │                             before there is one. Also takes optional `spans`/
    │   │                             `logsBySpanId`/`traceCostUsd` props, threaded straight
    │   │                             through from `TraceDetailPageView` (which already holds
    │   │                             them) to the view, unmodified — this container does no
    │   │                             computation on them itself. See the summary-card gotcha
    │   │                             below for what the view does with them
    │   ├── AnalyzeTraceDialogView.tsx  view — five states in priority order: loading the
    │   │                             stored query (spinner) → regenerating
    │   │                             (`isRegenerating`: one spinner naming the step the backend
    │   │                             says it is on, over the panel the review appears in as the
    │   │                             model writes it — a skeleton until then; see the streaming
    │   │                             gotcha below) → regenerate failed (backend's plain-text
    │   │                             error + "Try again") → no stored analysis (explanation +
    │   │                             "Run analysis") → have a result (analysis text +
    │   │                             `model`/`generatedAt`/`generationDurationMs` caption +
    │   │                             Regenerate/Copy/Close), plus an `Alert severity="warning"`
    │   │                             banner on top when `analysis.outdated` is true — the
    │   │                             backend flags this by comparing the trace's CURRENT latest
    │   │                             span `end_timestamp` against `analyzedThroughTimestamp`
    │   │                             (the snapshot taken when the analysis was generated), so a
    │   │                             still-running trace or a late background/subagent
    │   │                             completion that lands after "Analyze trace" was run shows a
    │   │                             "may no longer reflect everything" nudge to regenerate
    │   │                             rather than silently going stale. No client-side staleness
    │   │                             math — the view only reads the boolean. Two more info-severity
    │   │                             banners are mutually exclusive (see the windowed-review
    │   │                             gotcha below): `analysis.timelineTruncated` — LEGACY, only
    │   │                             ever true on a row stored before the windowed-review rework
    │   │                             — `ollama.max-prompt-chars` forced the call timeline to be
    │   │                             elided when that analysis was generated (backend
    │   │                             `timeline_truncated`/`omitted_line_count` columns, `V25`), so
    │   │                             the reader is told the review was written from a partial
    │   │                             timeline AND nudged to regenerate for full coverage; or
    │   │                             `analysis.reviewPassCount > 1` — a FRESH analysis whose
    │   │                             oversized timeline was split into that many consecutive
    │   │                             review passes and merged, naming `analysis.timelineCallCount`
    │   │                             so the reader knows every call was still seen. A fresh run
    │   │                             never sets both — see the gotcha for why they can't overlap.
    │   │                             Below the banners, a `TraceSummaryCard`
    │   │                             renders `analysis.summary` (backend `trace_analyses.summary`
    │   │                             column, `V26`) when present — a code-composed recap, plain text
    │   │                             (never markdown), never sent to Ollama and never judged by it:
    │   │                             see `TraceAnalysisPromptBuilder#buildTraceSummary` on the backend
    │   │                             for why a narrated retelling is assembled in code rather than
    │   │                             asked of the model. One row per labelled line — `Request` (shown
    │   │                             as "Prompt", a display-only relabel; the parser keys off the ": "
    │   │                             position, never the label text), `Work`, then whichever of
    │   │                             `Tools` / `Models` / `Files` / `Cost` / `Skills` / `Compaction`
    │   │                             the backend had something to say about, then `Outcome`. A line
    │   │                             with several clauses splits into its own bullet list, **on the
    │   │                             middle dot the backend joins them with and never on a comma** —
    │   │                             see the summary-separator gotcha below. Null for an analysis
    │   │                             stored before that column existed, in which case the card renders
    │   │                             nothing. Renders the
    │   │                             analysis text as markdown through `react-markdown` +
    │   │                             `rehype-sanitize`, never `dangerouslySetInnerHTML` — it is
    │   │                             model output derived from trace content (prompts, file
    │   │                             paths, tool inputs) this app did not author, so any stray
    │   │                             HTML in it is stripped rather than becoming live DOM (see
    │   │                             the markdown-rendering gotcha below). **Prompt is always the
    │   │                             backend's own text; Work, Outcome, and the four
    │   │                             Tools/Models/Files/Cost rows are not** — see the summary-card
    │   │                             gotcha below for the client-computed replacement
    │   ├── AnalyzeTraceDialogView.test.tsx  vitest coverage for the view — one fixture per
    │   │                             state above, plus the summary card's collapsed-by-default
    │   │                             and expanded states (the only tested file per this
    │   │                             feature's container/view test split)
    │   └── index.ts
    ├── TraceMinimap/
    │   ├── TraceMinimap.tsx           full-trace overview bar-per-span (height staggered by depth ≤ 4)
    │   │                             + draggable zoom brush; drag body to pan, drag left/right edge to resize;
    │   │                             double-click resets to full trace; exports ZoomView { s, e }
    │   └── index.ts
    ├── SpanWaterfallRow/
    │   ├── SpanWaterfallRow.tsx       single row: index badge + span name + SpanCallNumberBadge
    │   │                             (muted outlined `call N` chip, only on the tool/model spans the
    │   │                             backend numbered — see the call-number gotcha) + SpanFullRateBadge (pink
    │   │                             input+output+cache-create pill, the three-way split in its
    │   │                             tooltip) + SpanCacheReadBadge (the quiet neutral half of the
    │   │                             pair, its tooltip carrying the hit rate and the 0.1x note) +
    │   │                             SpanCostBadge (amber formatUsd chip, only when the resolved
    │   │                             costUsd > 0; its tooltip says whether that is a rollup or one
    │   │                             call) + model/effort pill + SpanToolBadge (tool_name chip whose
    │   │                             tooltip shows the tool's status and the command it ran, clamped
    │   │                             at 300 chars) + error/descendant-error pills + timeline bar +
    │   │                             duration label. All badges share spanChipSx, so only palette and
    │   │                             weight differ; pure component (no view split). Every badge except
    │   │                             the call number and error/descendant-error is gated on the
    │   │                             chipsOff prop (see Badge visibility) — the toolbar legend's
    │   │                             per-family mute toggle; those three name the row rather than
    │   │                             report an optional figure, so they are never hidden (the call
    │   │                             number alone has a separate opt-out, `showCallNumber`,
    │   │                             default true, for the Metrics page's exemplar drawer — this
    │   │                             page never passes it). Optional
    │   │                             `agentColor` prop (agentDispatch.ts) tints the timeline bar
    │   │                             (`linear-gradient(90deg, agentColor, alpha(agentColor, 0.6))`,
    │   │                             replacing the default primary gradient) and washes the row's
    │   │                             own background at low opacity — see the agent-dispatch color
    │   │                             gotcha below for why error still overrides the bar and why the
    │   │                             wash is a second, independent property rather than fighting the
    │   │                             selected row's own `inset 2px 0 0 primary.main` accent
    │   └── index.ts
    └── SpanInspectorDrawer/
        ├── SpanInspectorDrawer.tsx    right-side drawer, a flex sibling of the waterfall card (no
        │                             scrim — the waterfall stays visible/scrollable): left-edge
        │                             resize grip + a two-row header — row 1: span name + waterfall
        │                             prev/next nav ↑ "n / N" ↓ when >1 row is rendered + close ×
        │                             (unchanged layout from before the subagent chip); row 2, only
        │                             when the selected span belongs to a dispatch: a colored
        │                             subagent-name chip (agentColorBySpanId/agentLabelBySpanId,
        │                             agentDispatch.ts, same color the waterfall row's bar/wash
        │                             already uses), on its own row so a long subagent_type can
        │                             never crowd the name column into wrapping — plus one
        │                             scrolling column — meta grid (cost row, amber bold, after
        │                             duration when costUsd > 0), self-time bar, ErrorSection,
        │                             CallContextSection, then the Tokens/Tool/Attributes/Events/
        │                             Logs sections. Stays
        │                             mounted while closed (width 0) so the 0.2s width transition
        │                             runs; content is keyed by span id so section/log expand
        │                             state resets per selection; keeps the last selection
        │                             rendered during the close animation (guarded render-phase
        │                             setState, compared by span id) and drops it on the closing
        │                             transitionend, with `inert` while closed so nothing behind
        │                             `width: 0` stays tabbable. Wraps its content in
        │                             LongValueModalProvider, so a clamped value in any section opens
        │                             the same dialog
        ├── CallContextSection.tsx     the untitled call-context panel — the call's own
        │                             computed facts (buildSpanCallFacts: the agent's stated
        │                             intent, outcome, permission) and its related calls
        │                             (buildSpanRelations). Entirely computed: no request, no
        │                             model, nothing to validate, and it renders instantly.
        │                             It carries no heading of its own (a "What was this call
        │                             for?" title was removed on request), so the box renders
        │                             only when there is at least one fact or relation in it —
        │                             relationRowsOf is shared between that emptiness check and
        │                             RelationList so the two can't disagree
        │                             Each relation's call number is a real <button> that calls
        │                             onRevealSpan — the row it names may be scrolled away, folded
        │                             inside a subagent dispatch, or outside the zoom, all of which
        │                             TraceDetailPageView#revealSpan handles.
        │                             HISTORY WORTH KEEPING: this slot briefly held an on-demand
        │                             Ollama "analyze this call" button. Measured against nine real
        │                             spans it returned the computed summary verbatim on three,
        │                             restated the relation list on three more, and fabricated once
        │                             ("read 40 characters from a file at offset 2104" — a line
        │                             count reported as a character count, which passed validation
        │                             because both numbers were technically in the evidence). It
        │                             was removed rather than tuned further; the facts below it
        │                             were carrying the section the whole time. Don't reintroduce a
        │                             per-span model call without measuring it against the computed
        │                             layer first.
        │                             A non-tool span renders nothing at all. An earlier revision
        │                             showed a placeholder ("Only tool calls carry call context —
        │                             select a tool row to see it.") on the two waterfall rows out
        │                             of three that carry no tool_name; it was removed on request,
        │                             so the drawer simply omits the section there
        ├── CollapsibleSection.tsx     collapsible section primitive: the header row is a real
        │                             <button> (Tab/Enter/Space, aria-expanded) that toggles the
        │                             body, 11px chevron rotates -90° when collapsed, optional
        │                             leading icon + plain mono count (with native tooltip) +
        │                             token/tool/error tone variants; state is local, so it resets
        │                             when the drawer content remounts for a new span
        ├── TokensSection.tsx          collapsible amber section — header count is the four-way
        │                             total with the input/output/cache-create/cache-read split in
        │                             its tooltip (so a collapsed header still explains itself);
        │                             body rows are tokens only (no cost row — the drawer states
        │                             cost once, in the meta grid):
        │                             input/output/cache_creation in amber, then cache_read below
        │                             a dashed rule, deliberately muted (text.disabled/secondary,
        │                             neutral fill) with an outlined hit-rate badge (cacheHitRateLabel,
        │                             so a near-total hit reads ">99% HIT"). Renders its
        │                             own rows (not AttrRows) because that row needs its own
        │                             weight, separator, and trailing badge
        ├── ErrorSection.tsx           collapsible red section, rendered only for
        │                             statusCode === 'error' spans: statusMessage box + AttrRows
        │                             with exit_code/command (span attributes) and stderr (from
        │                             the span's ERROR-severity log, classified with the shared
        │                             `LogsPage/logsDerivations.severityOf` — severityText is null
        │                             on real telemetry) + "Copy error" button that
        │                             puts all of it on the clipboard as plain text; replaced the
        │                             old inline statusMessage-only red box
        ├── SpanAttributeSections.tsx  filters redundant keys; renders a collapsible "Tool" section
        │                             (info-tinted, wrench icon) + collapsible "Attributes" section,
        │                             each sorted alphabetically by key (byKey, localeCompare) --
        │                             a flat attribute bag has no meaningful emission order, so
        │                             alphabetical is what puts the same key in the same relative
        │                             spot across different spans instead of wherever the OTLP
        │                             payload happened to serialize it
        ├── SpanAttributeSections.test.tsx  vitest coverage: Attributes-section rows sorted
        │                             alphabetically regardless of input order, and the Tool section
        │                             sorted independently of Attributes (each section has its own
        │                             sort, not one global sort before the Tool/Attributes split)
        ├── SpanEventsList.tsx         collapsible Events section: timestamped cards (T+offset from
        │                             span start) with each event's attribute grid, values clamped
        │                             through LongAttrValue (a process.exit event carries the whole
        │                             stderr dump)
        ├── LogEntry.tsx               per-log row: leading expand caret (▸→▾; same-width spacer on
        │                             rows with no detail so columns align) + offset + severity +
        │                             event.name + tool badges + body; click to expand attributes,
        │                             whose values clamp through LongAttrValue at LONG_VALUE_LOG
        ├── longValue.tsx              the drawer's one truncate-and-expand path: LongValueModalProvider
        │                             (hosted once at the drawer root, so N clamped rows don't mount N
        │                             dialogs) + LongAttrValue (clamped preview + "view formatted (N
        │                             chars)" button) + the two budgets, LONG_VALUE_LOG (240, full-width
        │                             log rows) and LONG_VALUE_ATTR (110, the narrower grids). The modal
        │                             runs the raw text through tryParseJson (jsonrepair) and offers
        │                             copy-to-clipboard. Outside a provider the preview renders with no
        │                             button rather than throwing
        ├── drawerParts.tsx            shared drawer primitives: clock(ms) wall-clock formatter,
        │                             AttrRows (key/value grid with tinted borders per tone; values go
        │                             through LongAttrValue, numbers pre-formatted and never clamped)
        ├── useResizableWidth.ts       drag-to-resize hook for the left-edge grip; clamps 340px–62%
        │                             viewport width; width is null until first drag (drawer falls
        │                             back to min(440px, 42vw)) and persists across selections;
        │                             exposes isResizing so the width transition is disabled while
        │                             dragging. The document mousemove/mouseup listeners live in an
        │                             effect keyed on isResizing (so unmounting mid-drag can't leak
        │                             them) and the drag also ends on document mouseleave / window
        │                             blur — releasing outside the viewport never delivers a mouseup
        └── index.ts
```

## Visual layout

```
┌─ TraceDetailHeaderView ──────────────────────────────────────────────────────────┐
│ Observability                                                                    │
│ Traces › Trace detail  [SESSION abc… │ TRACE 0102…⧉⌄]  ← IdentityPill, next to the h1 │
│ ┌─ SummaryStrip: "Overview" ─────────────────────────────────────────┐          │
│ │ ▾ OVERVIEW                                                          │  ← click to
│ ├────────────────────────────────────────────────────────────────────┤    collapse
│ │ PROMPT   Refactor the Aurora theme overlay so it applies cleanly…  │  ← only when
│ ├──────┬────────┬───────┬────────────┬───────┬────────┬──────────────┤    firstUserPrompt
│ │ Cost │Duration│ Spans │ Tool calls │ Depth │ Errors │              │  ← KPI tiles
│ ├────────────────────────────────────────────────────────────────────┤
│ │ TOKEN COMPOSITION 1.2M [>99% cached]         3 model calls · $0.42 │
│ │ ■ Cache read 0.1× ▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇   1.1M   >99.9% │  ← one row per
│ │ ■ Input           ▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇                42K    3.5% │    nonzero
│ │ ■ Cache creation   ▇▇▇▇▇▇▇▇▇▇▇▇▇▇                        9K    0.8% │    category,
│ │ ■ Output            ▇▇▇▇▇▇▇▇▇                             3K    0.2% │    log-scaled,
│ │ Bars scaled logarithmically — cache read runs 10–100× the other …  │    sorted desc
│ ├────────────────────────────────────────────────────────────────────┤
│ │ ROOT SPAN ■ …                SERVICES 2                STARTED …  │  ← MetaFooter,
│ └────────────────────────────────────────────────────────────────────┘    space-between
└──────────────────────────────────────────────────────────────────────────────────┘

Collapsed, the whole panel is one line: `▸ OVERVIEW   $0.42 · 4.2s · 31 spans ·
12 tool calls · 1.2M tokens · 2 errors`.

Below the header, a flex row fills the remaining height: the waterfall card
(`flex: 1; min-width: 0`) and the inspector drawer as a flex *sibling* — no
scrim/backdrop, so opening or resizing the drawer just narrows the waterfall
while every row stays visible at full height.

┌─ Waterfall card (flex: 1, min-width: 0) ────────────────┐ ┌─ SpanInspectorDrawer ────────┐
│ WaterfallToolbar: ≡ Span waterfall  [err][tok][cache]  │▐│ claude_code.llm_request  [✕] │
│    [cost][model][tool] [Expand all] [▲ Next error]      │▐├──────────────────────────────┤
├─ TraceMinimap ──────────────────────────────────────────┤▐│ span id   <hex>              │
│  drag to zoom · dbl-click resets                        │▐│ kind      CLIENT             │
│  ░░░░░░░░▓▓▓▓▓▓▓▓░░░░░  ← span bars staggered by depth │▐│ scope     …                  │
│        [←│ brush │→]    ← draggable zoom window         │▐│ status    ok/error           │
├─ Axis (ticks at 0 / 25 / 50 / 75 / 100%) ──────────────┤▐│ started   HH:MM:SS.mmm       │
│ Span              │  0ms   125ms   250ms   375ms  500ms │▐│ ended     HH:MM:SS.mmm       │
├─ Body (overflowY auto, overflowX hidden) ───────────────┤▐│ duration  310ms              │
│ ▶ [1] claude_code.interaction ●54k ⟳1.1M [██████] 500ms│▐│ cost      $0.42  (amber)     │
│   · [2] llm_request ●2.4k ⟳98k $0.01 [Opus 5] [███] 310ms│▐│ self time [████░░] 87%     │
│   · [3] tool.execution $0.03 [Bash] [█] 45ms err       │▐│ ▾ ERROR   (red, error spans) │
│   · [4] tool.execution [Read] [█] 12ms                 │▐│ ▾ TOKENS 2,400   (amber)     │
│   (scroll continues …)                                   │▐│    input / output / cache_cr  │
│                                                          │▐│    ┄┄ cache_read  [88% HIT]  │
│                                                          │▐│       (muted, not amber)     │
│                                                          │▐│ ▾ TOOL (n) 🔧    (blue)      │
│                                                          │▐│ ▾ ATTRIBUTES (n)             │
│                                                          │▐│ ▾ EVENTS (n)                 │
│                                                          │▐│ ▸ LOGS (n)  ← starts closed  │
└──────────────────────────────────────────────────────────┘ └──────────────────────────────┘
                                                            ▲
                                             left-edge grip (drag to resize, 340px–62vw)
```

Row chips read left to right in a fixed order — `●` full-rate tokens (pink), `⟳` cache read
(neutral), cost (amber), model/effort, tool name — so the eye can scan one column down the trace.
The `interaction` root shows tokens but no cost (see Cost).

Drawer behavior: closed = `width: 0; overflow: hidden` (no footprint, no gap); open =
`min(440px, 42vw)` with a 16px left margin, animating `width 0.2s cubic-bezier(0.4, 0, 0.2, 1)`
(transition disabled while dragging the grip). Clicking the selected row again, or the ×, closes
it; selecting a different span swaps content in place and keeps the current width. The dragged
width persists for the session (state only, not storage). Every section is collapsible via its
header row; all default expanded except Logs, which starts collapsed. Section and log-row expand
state resets per span selection (content keyed by span id).

Span navigation: the drawer header shows ↑ / "n / N" / ↓ buttons that step to the waterfall row
above/below the selected one and scroll it into view. `ArrowUp`/`ArrowDown` do the same globally
while a span is selected. Because that listener is on `window` and calls `preventDefault`, it
stands down whenever the key press plausibly meant something else: text entry
(`input`/`textarea`/`select`/`contenteditable`), any modifier combination, anything inside an open
`[role="dialog"]` (a `LogEntry` value modal — swapping spans there remounts the span-id-keyed
drawer content and destroys the modal mid-read), and anything inside the drawer's own scroll
column (`[data-drawer-scroll]`), where arrow keys should scroll. The nav does not wrap: the first
row's ↑ and the last row's ↓ are disabled.

## Who calls which API

All three fetchers live in the shared `api/` barrel (`from '../../api'`); spans and the summary
are additionally wrapped by `tracesApi` (`fetchSpansForTrace` / `fetchTraceSummaryOrNull`) for
sample-data compatibility.

| Source                                      | Query key                        | Fetcher → endpoint                                          |
|---------------------------------------------|----------------------------------|------------------------------------------------------------|
| `TraceDetailPage` (`useQuery`)              | `['trace-spans', traceId]`       | `fetchSpansForTrace(traceId)` → `GET /api/traces/{traceId}` |
| `TraceDetailPage` (`useQuery`, eager)       | `['trace-logs', traceId]`        | `fetchTraceLogs(traceId)` → `GET /api/traces/{traceId}/logs` |
| `TraceDetailPage` (`useQuery`)              | `['trace-summary', traceId]`     | `fetchTraceSummaryOrNull(traceId)` → `GET /api/traces/{traceId}/summary` |
| `AnalyzeTraceDialog` (`useQuery`, `enabled: open`) | `['trace-analysis', traceId]` | `fetchTraceAnalysis(traceId)` → `GET /api/traces/{traceId}/analysis` (404 → `null`, not an error) |
| `AnalyzeTraceDialog` (`useMutation`)        | n/a (writes `['trace-analysis', traceId]` via `setQueryData` on success) | `streamTraceAnalysis(traceId, handlers)` → `POST /api/traces/{traceId}/analysis/stream` (SSE) |
| `AnalyzeTraceDialog` (`useQuery`, `enabled: open && supplementaryOpen`) | `['trace-cost-breakdown', traceId]` | `fetchTraceCostBreakdown(traceId)` (shared `api/` barrel) → `GET /api/traces/{traceId}/cost-breakdown` — per-subagent cost list + main-loop/auxiliary totals for `TraceSummaryCard`'s Cost section; `supplementaryOpen` only flips true once the reader expands that section's "Show Tools, Models, Files, Cost" toggle (`onSupplementaryExpand`), so this no longer fires on every dialog open; see that section's gotcha below for why the figure is never reconciled against the trace total |
| `TraceDetailPage` (`useQuery`)              | `['system-ollama-settings']`     | `fetchOllamaSettings` (imported from `../SettingsPage/settingsApi`) → `GET /api/system/ollama-settings` — same query key as SettingsPage's Ollama tab, so the two share one cache entry |

**"Analyze trace" is hidden, not just disabled, when Ollama is off.** `ollamaSettings?.enabled` (default
`false` while the query is loading, matching both the Settings page's own initial toggle state and the
backend's off-by-default `ollama.enabled`) is threaded
through `TraceDetailPageView` to `WaterfallToolbar` as `ollamaAnalysisEnabled`, which renders the
button conditionally rather than greying it out — there's nothing useful to show mid-click if the
feature is off. This is client-side convenience only; `TraceAnalysisService.regenerate` on the backend
independently re-checks the effective `enabled` flag before ever calling Ollama (see SettingsPage's
Ollama configuration section for the enforcement detail), so a stale tab still cannot trigger a real
analysis even if this check is bypassed.

All of the page's own `useQuery` calls (the first three) are enabled
only when `traceId` is truthy (`enabled: Boolean(traceId)`). All three poll conditionally —
see the `inProgress` bullet immediately below; every `AnalyzeTraceDialog`/Ollama query still never
polls. The logs query is intentionally
eager (not gated on a span being selected)
so the drawer's Logs section has data the moment the user first selects a span. The summary query
feeds three things: the header's Prompt row (`firstUserPrompt`), the header's Cost KPI
(`traceCostUsd`, from `TraceRow.totalCostUsd`), and that same tile's background-cost tooltip
(`traceBackgroundCostUsd`, from `TraceRow.backgroundCostUsd`) — see Gotchas and the Cost section
below. Every other header figure (tokens, span/tool counts, depth) stays derived from the spans
query.

**`TraceRow.inProgress` drives three conditional `refetchInterval`s, all keyed off the
`trace-summary` query's own resolved data.** `traceSummaryQuery` (the container's name for the
`['trace-summary', traceId]` query — declared FIRST in the container, ahead of the trace-spans
query, specifically so its data can be referenced from both) sets `refetchInterval` as a function
reading the query's own last result (`(query) => query.state.data?.inProgress ?
RUNNING_TRACE_POLL_INTERVAL_MS : false`) rather than closing over the `traceSummaryQuery` variable
itself, which isn't assigned yet at that point in the hook call (a real temporal-dead-zone
hazard, not just a style preference). The `['trace-spans', traceId]` query then reads
`traceSummaryQuery.data?.inProgress` directly (safe there — the variable is already fully
assigned) for the identical `refetchInterval`, so a still-running trace's waterfall keeps picking
up newly-emitted spans until the summary query itself observes the trace finishing. The
`['trace-logs', traceId]` query takes the same `refetchInterval` and must keep it: an `llm_request`
span is never stamped with its own cost in `span_costs`, so its per-span cost (`costOfSelectedSpan`
-> `costOfSpanRequests`) is summed purely from the `api_request` logs bucketed onto it, and Claude
Code emits those logs after the span. Without the poll, a span that arrives mid-run shows no cost
(and an empty Logs section in the dock) until the page is reloaded.
`RUNNING_TRACE_POLL_INTERVAL_MS` (5 s) is imported from `../TracesPage/tracesApi` — the same
constant `useTracesExplorer`'s running-row polling and `TraceSummaryInline`'s spans query use (see
`../TracesPage/CLAUDE.md`'s `inProgress` bullet). `traceInProgress` (`traceSummary?.inProgress ??
false`) threads container → `TraceDetailPageView` → `TraceDetailHeader` → `TraceDetailHeaderView`
as `inProgress`, rendered as a ticking `TraceDetailHeader/TraceLiveChip` right after the
breadcrumb's `IdentityPill` (replacing the older full-width banner); `TraceDetailPageView` also
appends a `components/LiveTailRow` after the waterfall's last row while `traceInProgress` is true,
showing where the trace is still extending.

**While `traceInProgress`, the view's window extends to "now", not to the last span received.**
`computeTraceWindow` only knows about spans that have arrived, and a running trace's newest span
ends well before the present, so with a window fixed at that end the live tail had no room to draw
in — `LiveTailRow`'s `right` is clamped to the window and collapsed to its 3px `minWidth`. The view
now takes `totalMs = max(recordedTotalMs, now - earliest)` (`now` from `useNowTick`, 1 s), which
also means every bar already on screen and every minimap bar narrows a little per tick to make room
— what the running bar needs. The header keeps the *recorded* `traceWindow`, so its figures don't
tick. Zoom is stored as `zoomView: ZoomView | null`, where `null` is "the full window" and keeps
following `totalMs`; `changeView` (the minimap's `onViewChange`) stores `null` for any view that
covers the whole window, since a stored full-extent view would freeze at the moment it was made.
The reset-zoom effect is keyed on the *recorded* total so a new span batch still resets the zoom
but the per-second tick doesn't. `now` is the browser clock against server span timestamps, so a
skewed client clock shifts the tail's edge; the `max` keeps a behind-running clock from clipping a
real span.

**A running trace also follows and flags its new rows.** (1) *Follow:* a layout effect keyed on
`visible.length`/`traceInProgress` scrolls the waterfall body to its new bottom, but only when the
reader was already at the bottom (within `AUTO_SCROLL_BOTTOM_TOLERANCE_PX`). "Was at the bottom" is
judged against the `scrollHeight` recorded after the previous row-count change
(`previousScrollHeightRef`), not the current one — by the time the effect runs the new rows are
already in the DOM. It never scrolls a finished trace, so expanding a row at the bottom doesn't
yank the list away. (2) *Flag:* spans whose ids weren't in the previous `spans` array are held in
`newlyArrivedSpanIds` for `NEW_SPAN_HIGHLIGHT_MS` (`spanArrivalHighlight.ts`, shared with the row's
fade animation) and their `SpanWaterfallRow` gets `isNewlyArrived`. The first batch is the baseline,
so nothing flashes on arrival; the keyframe has only a `0%` frame, so the fade lands on the row's own
tint (selected/agent wash). Not gated on `traceInProgress`, since the final batch often arrives in the
poll that flips it false. `LiveTailRow` no longer draws a pulsing dot at the bar's leading edge —
the bar, its "running…" label, and the header chip already say so.

`TraceSummaryInline` in `TracesPage` uses `['trace-inline-spans', traceId]` (a different key)
for the same spans endpoint — the two caches are separate.

## Data flow and semantics

### Span tree building

`buildSpanTree(spans)` in `spanTree.ts` partitions the flat `SpanRow[]` response into:

- `roots` — spans whose `parentSpanId` is null/absent or references a span not in the response.
- `childrenByParentId` — a `Map<string, SpanRow[]>` for parent → sorted children lookup.

Children and roots are both sorted by `startTimestamp` ascending. The result is a `SpanTree` that
is passed verbatim to the view; the waterfall's visible-rows traversal walks it each render.

Three derived maps are computed once in the container's `useMemo` pool:

- `spanIndices` (`buildSpanIndices`) — 1-based DFS counter for the index badge on each row. **Not
  the same number a trace-analysis review cites** — see the call-number gotcha below.
- `depthBySpanId` (`buildSpanDepths`) — number of ancestors for each span; capped at 4 for
  minimap stagger, used directly for the waterfall indent (`pl: 10 + depth * 15`), and also
  threaded into `TraceDetailHeader` (`Math.max(...depthBySpanId.values()) + 1`) for the Depth KPI
  tile — one depth algorithm for the whole page, not a second walker in the header.
- `descendantErrorCounts` — recursive count of `statusCode === 'error'` spans below each span;
  shown as "+N below" in warning color on collapsed parent rows.

`selfTimeNanosBySpanId` subtracts the union of children's wall-clock intervals from the span's
`durationNanos`. Children are sorted by start and merged (standard sweep-line union), so
overlapping children count only once. Leaf spans retain their full duration as self time. The
result drives the self-time progress bar in the drawer.

`sessionId` is extracted from the root span's `attributes['session.id']` or
`resourceAttributes['session.id']`, surfaced as plain text in the Overview panel's meta footer.

### Subagent-dispatch coloring

`buildAgentDispatchColoring(tree, spans)` (`agentDispatch.ts`) is the fourth `useMemo` built on
`tree`, alongside `spanIndices`/`depthBySpanId`/`descendantErrorCounts` above. A dispatch span —
`attributes?.['tool_name'] === 'Agent'`, the identical check `SpanWaterfallRow`'s `SpanToolBadge`
already special-cases for its own tooltip — and every span in its subtree get colored the same,
so a reader can see at a glance which rows belong to which dispatched subagent while scanning the
waterfall.

**Colored by agent TYPE, not by dispatch instance.** The color key is the dispatch's own
`subagent_type` attribute (same `hasSubagentType`-style presence/non-null/non-empty guard
`SpanToolBadge` uses before trusting it), so two "Explore" dispatches in one trace share a color
and an "Explore" dispatch and a "Plan" dispatch don't. A dispatch with no readable `subagent_type`
falls back to the generic label `"Subagent"` and is still colored — it is a real dispatch, just an
unnamed one. Colors come from `colorForAgentDispatchIndex` (`theme/theme.ts`), assigned in
**first-seen DFS order over distinct labels** — the same traversal order `buildSpanIndices` walks
for the row index badge — not one index per dispatch span. **This is a dedicated palette, not
`colorForIndex`/`CHART_PALETTE`** (the general chart palette every other categorical coloring on
this page uses — see `traceInsightsDerivations.ts`'s `PHASE_KIND_COLOR_INDEX` for that sibling
case): `CHART_PALETTE` leads with violet and pink, which are exactly the waterfall toolbar's
"model" and "tokens" legend colors (`theme.palette.primary.main` and `tokenFigureColor`), so the
first two agent types dispatched in a trace would otherwise silently take on the same colors as
two of the six fixed badge-family swatches rendered in the same legend row. The dedicated palette
(`greenDeep`/`gold`/`teal`/`purple`) is chosen to also stay clear of the other four fixed keys —
error (red), cache (`text.disabled` gray), cost (`warning.main` amber), tool (`info.main` blue) —
and to keep its own four hues mutually distinguishable: `green` and `cyan` were dropped because
each sits right next to `greenDeep`/`teal` respectively (near-duplicate hues), which used to make
a 4th dispatched agent type's color collide with the 1st once the cycle wrapped. `legend` is that same first-seen list as `AgentDispatchLegendEntry`
(`{ label, color, dispatchSpanIds }`) pairs, which `WaterfallToolbar` renders as extra swatches (see
that component's own Files entry).

**Legend swatches are clickable — jump to (and cycle through) that agent type's dispatches.**
Because color is per label, not per instance, `dispatchSpanIds` carries every dispatch span sharing
that label/color (first-seen DFS order, pushed onto the entry's array on every dispatch-span visit —
a `Map<string, AgentDispatchLegendEntry>` keyed by label, not the old create-once-per-label object).
`WaterfallToolbar` wires each non-`error` `!key.family` entry's `onClick` to the new
`onAgentLegendClick(label, dispatchSpanIds)` prop; `TraceDetailPageView`'s `revealNextAgentDispatch`
implements it with the same ref-indexed cycling idiom as `nextError`/`errorIndexRef` (`nextError`
above), but keyed per label in one `agentDispatchCycleIndexByLabelRef: Map<string, number>` rather
than a single index, since each label cycles independently — the entry starts at -1 so the first
click lands on dispatch 1 of N, and calls the page's existing `revealSpan(spanId)` unmodified — the
same function `CallContextSection`'s related-call links use (expands any collapsed ancestor, widens
the zoom window only if the target sits outside it, selects the span, scrolls it into view; see
`TraceDetailPageView.tsx`'s own comment above `revealSpan`). `error` stays inert — only
agent-dispatch entries set `LegendKey.onClick`/`title`.

**Nested dispatches resolve to the innermost one**, mirroring the backend's
`SubagentCostAttributor#enclosingDispatchChain` (`backend/.../service/SubagentCostAttributor.java`),
which aggregates a call's cost and call counts onto the innermost enclosing dispatch for the
identical reason: the subagent that actually made the call is the one whose work it is, and any
dispatch chain above it is identity, not ownership. The module's one DFS walk implements this by
carrying the current enclosing dispatch's color down from parent to child and overriding it
whenever it enters a (possibly nested) dispatch span, so every descendant under the inner dispatch
takes the inner color rather than the outer one. Dispatches never nest in real Claude Code data
today (confirmed on the backend side too — see `SubagentCostAttributor`'s own doc comment), so
this is currently unreachable in practice; it is implemented anyway because it is cheap to get
right in one pass and silently wrong dispatch attribution is a worse failure mode than an unused
branch.

**Error still overrides the bar's color unconditionally, and the row background wash is a second,
independent property rather than a competing accent.** `SpanWaterfallRow`'s `barBackground` checks
`isError` before `agentColor` — one hue means one thing on the timeline bar, and this page's own
rule (see the token/cost color gotchas below) is that error is never shared with anything else, so
an errored span inside a dispatch still shows a plain red bar like any other error. What keeps that
span visibly part of its dispatch anyway is a second, independent per-row identity cue: a faint
`alpha(agentColor, 0.08)` (`0.16` in dark mode) background wash on the row itself, applied only when
unselected and not hovered. It is a different CSS property from both the bar (a separate track
column) and the selected row's own inset box-shadow accent, so it never has to compete with either
for the same visual slot — an errored dispatched row still shows both the red bar and the wash.

**Hover and selection on a dispatched row reuse `agentColor` too, at progressively brighter
alphas, rather than falling back to the generic `action.hover`/`primary.main` treatment.** Hovering
a dispatched row's background jumps to `alpha(agentColor, 0.18)` (`0.28` dark); selecting one jumps
further to `alpha(agentColor, 0.28)` (`0.4` dark), and the left-edge `inset 2px 0 0` accent switches
from `primary.main` to `agentColor` as well — so interacting with a dispatched row keeps reading as
"this subagent" instead of switching to an unrelated highlight hue partway through the interaction.
A non-dispatched (main-loop) row is unaffected: `agentColor` is `undefined` there, so it falls
through to the original `action.hover`/`primary.main` behavior unchanged. A bolder per-row treatment
(a dedicated colored left-edge stripe, matching the selected row's own inset-accent idiom more
literally) was considered and set aside for the first version of this feature: it would have needed
either a second inset box-shadow slot stacked against the selected accent's, or an absolutely
positioned overlay competing for paint order with it, and the CLAUDE.md's own instructions call out
that exact risk. Reusing the existing `bgcolor`/`boxShadow` slots at brighter alphas (rather than a
new stripe) gets the "clearly belongs to it, more so on hover/select" requirement with no new
box-shadow slot and no stacking-order reasoning.

**The drawer repeats the identity as a name chip, not just a color — and it gets its own row.**
`SpanInspectorDrawer`'s header renders a small pill (colored text on an `alpha(agentColor, 0.16)`
fill, same treatment as `SpanWaterfallRow`'s badges) naming the dispatch's `subagent_type` — or the
generic `"Subagent"` fallback — whenever the selected span resolves in both `agentColorBySpanId`
and the parallel `agentLabelBySpanId` (agentDispatch.ts). This exists because the row-level
wash/bar tint only communicates "same color as some other rows" — useful for scanning the
waterfall, useless once you've opened the drawer and the rest of the waterfall (and the toolbar
legend that decodes the color) may be scrolled out of view. The chip's own `title` spells out the
label in words (`Part of a dispatched "Explore" subagent`) for the same reason. Absent (renders
nothing) for a main-loop span — both maps miss it, the same "no entry means not dispatched"
convention `colorBySpanId` already documents.
**The chip is a second row under the name+nav+close row, not inline with it.** A first version put
it inline, sharing the row with the span-name `Typography` (`flex: 1, minWidth: 0`) and the
fixed-width nav/close controls. At a narrow drawer width a long `subagent_type` (e.g.
`expert-java-spring-boot-engineer`) left too little room for the name's flex basis, and
`wordBreak: 'break-all'` then wrapped every character of the name onto its own line — a resizable
drawer can be dragged this narrow, so this wasn't an edge case. The header is now a column: the
name+nav+close row is unchanged from before this feature (nothing new competes with the name for
width), and the chip sits alone on the row below it, `alignSelf: 'flex-start'` with `maxWidth:
'100%'` and its own ellipsis truncation against the drawer's current width. Don't move the chip
back inline with the name.

### Cost

The header's Cost KPI (and the Token composition card's cost line) reads `TraceRow.totalCostUsd`
from the **`trace-summary`** query (`traceCostUsd` prop, threaded `TraceDetailPage` →
`TraceDetailPageView` → `TraceDetailHeader`) — the same backend-authoritative total the Traces list
Cost column shows, from the `trace_costs` view (`V14`: the summed `cost_usd` of the `api_request`
logs Claude Code stamped with that trace id). `traceCostUsd` is `null` while the summary query
hasn't resolved yet or resolved with no cost; `TraceDetailHeader` treats both the same (`?? 0`),
which renders as "—" through `formatUsd`.

**This is deliberately not summed client-side from the spans.** Spans (`GET
/api/traces/{traceId}`) and the cost-bearing `api_request` logs arrive over separate OTLP
endpoints, so a client-side sum of `costOfSpan(span)` (`spanCost.ts`) over the spans in hand can
land on a different, and specifically lower, number than the trace's real total — e.g. when the
spans haven't finished ingesting yet, or a request logged without a span id still counts toward
the trace total but has no span to attribute it to. `costOfSpan` is the per-span
breakdown figure (its own read of `SpanRow.costUsd`, filled from the sibling `span_costs` view) —
it's just not the source of the trace-level KPI. These are real billed amounts, so they carry no
"~"/"est." qualifier.

**`TraceRow.backgroundCostUsd`** (also from the `trace-summary` query, threaded as
`traceBackgroundCostUsd` through the same `TraceDetailPage` → `TraceDetailPageView` →
`TraceDetailHeader` → `TraceDetailHeaderView` chain) is the portion of `totalCostUsd` billed
*after* this trace's own `claude_code.interaction` root span closed — e.g. a fire-and-forget
`Agent` tool dispatch (its own span closes in milliseconds) that kept issuing requests long after
the turn that launched it ended. `totalCostUsd` already includes it; the Cost KPI tile shows a
small warning-tinted `InfoOutlinedIcon` + `Tooltip` (only when `backgroundCostUsd > 0`) explaining
the split, rather than a second tile. It's populated by a *second*, small query in
`TraceExplorerService.traceSummary` — `LogRecordRepository.findCostSplitByTraceIds` called with a
single-element trace id list — not a new column on `SpanRepository.traceSummaryById`: that
query's row mapper (`toTraceSummary`) is shared by 20 other list/sort/histogram queries with an
identical column order, so adding a column there for a field only this single-trace endpoint
needs would mean touching all of them. This mirrors the Sessions prompt timeline's own
`backgroundCostUsd`/`backgroundTools` fields (`SessionsPage/SESSIONS-BACKEND.md`'s "Background
split" section) — same backend query, same underlying concept, surfaced on both pages that show a
trace's cost.

**Both per-span displays resolve through `costOfSelectedSpan(span, logs)`** — the waterfall row's
`SpanCostBadge` chip, and the drawer's meta grid `cost` row (amber bold, after duration; the
drawer's Tokens section carries no cost row, so the drawer states cost once). It prefers
`costOfSpan` and falls back to `costOfSpanRequests(logs)` — the summed
`cost_usd` of the `api_request` logs bucketed onto that span — when nothing was stamped against the
span itself. That fallback is the whole point: it is what puts a per-call price on an `llm_request`
row, which `span_costs` leaves at 0 (see the consequence below). The two are never added, so a
`tool.execution` span, which is both stamped *and* holds its own request logs, shows its stamped
total once rather than twice.

**A `claude_code.interaction` span shows no cost at all** — `costOfSelectedSpan` returns 0 for it
whatever it was stamped with, so the row's badge and the drawer's `cost` row both disappear on the
turn root. Its rollup *is* the whole turn, i.e. the trace-level number the header's Cost KPI
already states in a place built for it; repeating it on a span row invited reading it as that
span's own cost and adding it to the `llm_request` rows underneath. This is display-only
suppression — `costOfSpan(span)` still returns the real `span_costs` figure, and the header KPI
(from the `trace-summary` query) is unaffected. The match is on the span name, normalized the way
`serviceOf` / `isToolCallSpan` do it, so `claude_code.interaction` and a bare `interaction` both
hit.

**The badge column still does not sum to the trace total, by construction.** A stamped
`tool.execution` running a subagent covers every request made under it while the `llm_request` rows
below show their own, so the same dollar appears at two depths. The `isRollupCost` prop (true when
`costOfSpan(span) > 0`) picks the badge's tooltip — "Cost of the requests made under this span" vs
"Cost of this model call" — which is what tells a reader which kind they're looking at. The
authoritative total is the header KPI; don't try to reconcile it against a column sum.

Two consequences worth remembering:

- **The cost lands on the span that issued the request** — the `claude_code.interaction` root, or a
  `tool.execution` span for a request made inside a tool run — **not on the `llm_request` child.** So
  a waterfall row can show tokens with no cost, and the raw `span_costs` figure for a turn sits
  entirely on its root — which, on an `interaction` root, is exactly the figure the row and drawer
  now suppress. Both surfaces work around the misattribution client-side (above), which is why a
  stamped `tool.execution` row can carry a rollup badge and its `llm_request` children their own.
  Fixing it in the data instead — re-keying `span_costs` to `request_id`,
  the way `span_efforts` (`V15`) already correlates — was considered and rejected: it would disperse
  a Task subagent's cost across its children (4,652 logs / $360.81 locally) and move 37 cross-trace
  requests out of the trace their logs were recorded under.
- **There is no client-side estimate.** An earlier revision priced the span's tokens at published
  per-model rates; it ran 2-3x off real spend and disagreed with the Sessions page, which reports what
  Claude Code actually billed. A trace whose requests predate trace-id correlation totals 0 and renders
  "—" rather than a fabricated number.
- **Tokens and cost can disagree while ingestion is in flight.** `TokenCompositionCard` shows "Token
  counts aren't available yet for this trace." (with the cost line still shown) rather than "no model
  calls" when `tokenBreakdown.total <= 0` but `totalCostUsd > 0` — spans (which carry token
  attributes) and cost-bearing request logs land at different times, so a trace can briefly have a
  real cost with no token-bearing spans yet. "No model tokens — this trace made no model calls." is
  reserved for when both are absent.

### Log bucketing

`bucketLogsBySpan(logs, tree, rootSpanId)` in `logBuckets.ts` attaches each `LogRow` to its
OTLP `spanId` if that span exists in the tree; otherwise the log falls back to `rootSpanId`.
Within each bucket logs are sorted by `event.sequence` (when present on both sides) then by
`event.timestamp` attribute (the authoritative SDK wall-clock time) then by
`log.timestamp` (the OTLP record time, which can lag by seconds when the exporter batches).

**`tool_decision` and `tool_result` logs can land outside the `claude_code.tool` call they're
about, and `bucketLogsBySpan` corrects that — but only when the log's own `span_id` doesn't
already put it somewhere meaningful.** Claude Code stamps both event types with whatever span was
*active* at the instant they fired. That's sometimes exactly right and worth keeping as-is: a
`tool_decision` made while `claude_code.tool.blocked_on_user` (a real permission wait) was active
is genuinely that specific span, more precise than the wrapper `claude_code.tool` span itself —
verified on live data (an `ExitPlanMode` call blocked ~28s on user approval: the `tool_decision`
log's own `span_id` was the `blocked_on_user` span's id exactly). It's sometimes wrong: the same
event firing outside any of the call's own spans lands on the `claude_code.interaction` turn root
instead — verified on live data for a same-turn `Read` call's `tool_result`, which otherwise left
the `Read` span's own Logs section empty. Same "logged against the wrong span" shape the Cost
section documents for `api_request`, but fixed differently: `tool_decision`/`tool_result` don't
need a backend view, because the correlating key is already in the same trace payload the page
has in memory — both event types carry their own `tool_use_id`, which is unique per call and
matches the `tool_use_id` on the wrapper `claude_code.tool` span (verified 1:1, no collisions,
always same trace).

`collectToolCallFamilies` walks the tree once and, for every span `isToolCallSpan` recognizes as a
wrapper (the same rule the header's Tool calls tile and Collapse-all use — excludes
`tool.execution`/`tool.blocked_on_user` themselves) that also carries a `tool_use_id`, records that
wrapper's own span id plus the full set of span ids in its subtree (itself and every descendant,
however deep). For each log: if its own `span_id` already falls inside that subtree, it's left
alone — that includes a real `blocked_on_user` attribution, which is *more* specific than the
wrapper and must not be clobbered. Only when the log's own `span_id` points **outside** the whole
family (absent, or on an ambient ancestor like the turn root) does the `tool_use_id` fallback
kick in, re-pointing the log at the wrapper span. **It never redirects a log into a specific
sub-span** (`tool.execution` vs `tool.blocked_on_user`) — nothing in the log itself says which
phase it belongs to, and landing one level up on the wrapper beats guessing wrong between two SDK
sub-spans that mean very different things (an instant auto-approve vs. tens of seconds waiting on
a human). This is an **exact correlation, not a heuristic**, same caution as `span_efforts`' exact
`request_id` join (see the `effort` gotcha below). `toolUseIdOf` treats a missing/non-string/empty
`tool_use_id` as "no correlation key," so a log or span from an older Claude Code build without the
attribute falls straight through to the plain `span_id`/root resolution, unaffected.

**A prior revision of this correlation was wrong in a way worth remembering**: it matched purely
by `tool_use_id` — any span carrying it, wrapper or `tool.execution` sub-span alike — and
overrode the log's own `span_id` unconditionally. Since `tool.execution` carries the same
`tool_use_id` as its wrapper (verified: identical value on every sampled parent/child pair), that
version silently migrated *every* `tool_decision`, including ones already correctly attributed to
a real `blocked_on_user` wait, onto `tool.execution` — destroying the one distinction ("was this
decision instant, or did it wait on a human") the page could otherwise show. If you're tempted to
simplify this back to a flat `tool_use_id → span_id` map, this is why not to.

**`hook_execution_start`/`hook_execution_complete` (`PreToolUse`/`PostToolUse`) have no such key**
— they carry a `hook_name` like `"PreToolUse:Read"` but no `tool_use_id`, so there is no exact way
to attach one hook run to a specific tool call among several of the same name in one turn. They
stay wherever their own `span_id` resolves (in practice, the root) rather than guessing by
adjacency or hook name — a known, deliberately unfixed gap, not an oversight.

The buckets feed the drawer only — its Logs section receives the pre-bucketed array for the
selected span, and `costOfSpanRequests` reads the same array for the per-call cost (see Cost).
`costOfSpanRequests` is unaffected by the `tool_use_id` correlation above: it filters for
`event.name === 'api_request'` logs specifically, and `tool_decision`/`tool_result` are never
that event, so moving them off the root span changes nothing about cost. The waterfall row
deliberately carries no log-count badge: a count of correlated log records is a property of the
telemetry, not of what the span did, and it competed for row width with the token, cost, and
error pills that answer questions someone actually scans the waterfall for.

### Zoom window and visible row filtering

`ZoomView { s: number; e: number }` (milliseconds relative to `traceWindow.earliestStartMs`) is
owned by the view's `useState`. It initialises to `{ s: 0, e: totalMs }` and resets via
`useEffect` whenever `totalMs` changes (new trace loaded). The minimap brush writes new values
directly via `onViewChange`.

`visible` is a `useMemo` that walks the tree (respecting the `collapsed` set) and then filters
to rows that overlap the zoom window: `offMsOf(s) < view.e && offMsOf(s) + durMsOf(s) > view.s`.

### Bar geometry

All bar positions are expressed as percentages of `visibleSpanMs = view.e - view.s`:

```
left  = Math.max(0,   ((offMsOf(s) - view.s) / visibleSpanMs) * 100)
right = Math.min(100, ((offMsOf(s) + durMsOf(s) - view.s) / visibleSpanMs) * 100)
width = Math.max(0, right - left)
```

`left`, `right`, and `width` are computed in the view and passed as props to each
`SpanWaterfallRow`. The waterfall body container has `overflowX: hidden` so no bar geometry
value — even a mis-clamped one — can force a horizontal scrollbar.

**Duration-label placement** (in `SpanWaterfallRow`): the label is positioned to stay entirely
within the track:

- `right < 85` (bar ends with room) → label to the right of the bar end.
- `right >= 85 && left > 15` (bar ends near the edge, but started far enough right) → label to
  the left of the bar start.
- `right >= 85 && left <= 15` (full-width span, e.g. the root) → label *inside* the bar's right
  end (`right: calc(${100 - right}% + 8px)`) with `color: neutralColors.white` so it reads as a
  deliberate on-bar label rather than clipped text.

### Collapse and expand

`collapsed` is a `Set<string>` of span IDs in `useState`. `toggleCollapse(spanId)` flips
membership; `toggleAll` switches between the empty set (everything expanded) and
`new Set(collapsibleToolSpanIds)`, driven by `anyCollapsed = collapsed.size > 0`. The
`WaterfallToolbar` button label tracks `anyCollapsed` live, so "Expand all" also clears rows the
user collapsed one chevron at a time.

**"Collapse all" only folds tool-call spans**, not every parent. `collapsibleToolSpanIds` is a
container `useMemo` over the spans that pass `isToolCallSpan(span.name)` *and* have children — so
a `claude_code.tool` row folds away its SDK sub-spans (`tool.execution`, `tool.blocked_on_user`,
and anything nested under them) while the `claude_code.interaction` root and the `llm_request`
rows stay expanded. Collapsing every parent left a one- or two-row waterfall that hid the very
structure the page is read for. Using `isToolCallSpan` — the same rule the header's Tool calls
tile and the Traces list count with — means the sample store's bare names (`tool.Read`,
`mcp.connect`) collapse too, while the sub-spans that helper excludes are never themselves
collapse targets.

When that list is empty and nothing is collapsed the button would be a no-op, so `canToggleAll`
hides it: a trace with no tool calls shows the legend and "Next error" alone.

### Error navigation

`errorSpans` is a filtered `useMemo` of spans with `statusCode === 'error'`. `nextError` cycles
through it by index using `errorIndexRef` (a `useRef` so it doesn't trigger re-renders), which
starts at -1 — so the first press of "Next error" lands on error 1 of N.

**Nothing is auto-selected on arrival** (see the drawer gotcha below); the toolbar button is the
only way into the error walk.

`scrollToSpan` reads the waterfall container ref, queries `[data-span="${spanId}"]` for the row,
and scrolls **the minimum distance** that brings the row inside the container's visible band,
keeping `min(2 rows, a quarter of the container)` of margin at whichever edge the row entered
from. A row already comfortably in view doesn't move the list at all — so stepping through spans
slides the highlight instead of yanking the waterfall on every press. It's shared by span nav
and `nextError`.

### Span selection and the inspector drawer

`selected` is a `string | null` span ID and starts `null`, so the drawer is closed on arrival.
`selectSpan` toggles: clicking the already-selected span deselects it (closes the drawer). The view resolves `selected` into a
`SpanInspectorSelection` (the full `SpanRow` plus pre-computed `selfTimeNanos`, `tokens` from
`tokenBreakdownForSpan`, the `logs` bucket, `costUsd` via `costOfSelectedSpan`, and the span's
`waterfallIndex`/`waterfallCount`) and passes it — or null — to `SpanInspectorDrawer`, which always
stays mounted so the width transition can run and the dragged width survives across selections.

Alongside the selection the drawer takes `traceId`, **the whole `spans` array** and `logsBySpanId`.
`CallContextSection` scans the spans to find the calls related to the selected one — passing the
waterfall's *rendered* rows instead would be a silent bug, since a collapsed dispatch or a zoom
brush would hide exactly the far-apart earlier read this exists to surface — and reads
`logsBySpanId` for the selected call's own `tool_result` / `tool_decision`. Note the selection's own
`logs` field is deliberately not enough for that second job: it is the bucket for the *selected*
span, while a call's tool logs sit on its children (see `spanCallFacts.ts`).

`waterfallIndex`/`waterfallCount` are derived fresh each render as the selected span's position in
`visible` (cheap — same pattern as `errorSpans`; no new top-level state). `selectAdjacentSpan(delta)`
moves that index by `delta` and — if in range — calls the existing `setSelected` + `scrollToSpan`.
The drawer's ↑/↓ buttons and the view's `ArrowUp`/`ArrowDown` window keydown listener both go
through it.

**The nav walks `visible`, not the span's siblings.** An earlier revision stepped through
`siblingsOf(span, tree)` (parent's children, or the trace roots). Three things were wrong with
that, all fixed by navigating the rendered row list instead:

- A single-root trace's root span has no siblings, so the count was 1 and the nav hid itself —
  on the first span most readers click, which is where they reach for it.
- A span whose `parentSpanId` names a span outside the response is a *root* per `buildSpanTree`,
  but `siblingsOf` looked that id up in `childrenByParentId`, missed, and fell back to a
  one-element list — so orphan-rooted spans never got nav either.
- A sibling hidden inside a collapsed parent or filtered out by the zoom window has no rendered
  row, so `scrollToSpan`'s `[data-span]` query found nothing and silently did nothing: the drawer
  swapped while the waterfall sat still on a row that wasn't even on screen.

`visible` already respects both collapse and the zoom window, so every step lands on a row the
user can see. `waterfallCount` is deliberately 0 when the selected span isn't in `visible` (its
parent was collapsed, or the zoom moved off it), which hides the nav rather than showing "0 / N".

`SpanInspectorDrawer` is resizable via `useResizableWidth()`: dragging the left-edge grip flips
`isResizing`, whose effect registers `mousemove` (computing `startWidth + (startX - currentX)`, so
dragging left widens, clamped to `[340px, 62% viewport width]`) plus `mouseup` / `mouseleave` /
window `blur` to end the drag. The listeners deliberately live in the effect, not in the mousedown
handler: React's cleanup then covers both a release the document never sees (mouse let go outside
the viewport) and an unmount mid-drag. While dragging, `isResizing` disables the width transition
so the edge tracks the cursor 1:1.

## Gotchas

- **A review's "call 20" is not waterfall row 20, and the two numbers must never be conflated.**
  The index badge on each row is `spanIndices`, a 1-based DFS counter over *every* span — the
  `claude_code.interaction` root, each tool call's `tool.execution` and `blocked_on_user` children
  included. The number an analysis cites counts only tool calls and model requests, in trace order,
  and is computed on the backend (`TraceCallNumbering`) and delivered as **`SpanRow.callNumber`**,
  null on every span that isn't one. On a real trace the two diverge from the first row. Read the
  citation number off `callNumber` and nothing else; deriving it client-side would duplicate a rule
  that is configurable on the backend (`tuning.tool-span-name` / `tuning.llm-request-span-name`) and
  a citation pointing at the *wrong* row is worse than one pointing nowhere.
  `TraceDetailPageView` builds `spanIdByCallNumber`/`knownCallNumbers` from that field and hands the
  dialog a `onNavigateToCall`; the dialog's `callCitations` remark plugin turns each cited number
  into a button, but **only for a number the trace actually has**
  — and **the phrase it matches has to allow `call numbers 27, 29, 31` as well as `call 27`**,
  because the prompt template asks for citations in the words "cite call numbers" and the model
  writes them back that way. Trace `bc222c551f5acf3c77fc44f8bc53c0f8` is why: every citation in its
  "What went wrong" section read `(call numbers 22, 43 …)` and none of them linked, while the two
  bare `at call 3` in "What went well" did, so the feature looked like it worked right up to the
  section the reader came for. Widening the phrase is the fix rather than tightening the prompt's
  wording, since the answer is model output and the stored analyses are re-read, never migrated —
  a number the model INVENTS (one past the end of the trace's timeline) still has no row to land on.
  Since the windowed-review rework, that is the ONLY way an out-of-range citation happens — no call
  is ever elided any more (an oversized timeline is split into passes instead) — so it is
  unambiguously a hallucination, not "maybe hidden." Rather than folding it into indistinguishable
  plain text (the pre-rework behavior, back when it genuinely could have been either), it is rewritten
  to a distinctly-marked, non-clickable `<span title="This trace has no call N">` — `known: false` in
  effect, expressed as a distinct href scheme (`unknownCallCitationHref`/`unknownCallNumberFromHref`
  in `callCitations.ts`) rather than a field on `CallCitationSegment`, since `splitCallCitations` itself
  doesn't know which numbers are known — only `remarkCallCitations`'s `isKnownCall` does. Clicking a
  KNOWN citation closes the dialog (it is modal over the waterfall), expands any collapsed ancestor,
  widens the zoom only if the target sits outside the current window, then selects and scrolls to the
  row — which carries the matching `call N` badge, so the landing is verifiable rather than merely
  plausible.

- **An oversized timeline is now reviewed in multiple passes, never truncated — and the response
  carries how many.** `TraceAnalysisResult.reviewPassCount` (always ≥ 1) and `.timelineCallCount`
  are new; `.timelineTruncated`/`.omittedLineCount` are LEGACY, staying in the type only because an
  OLD stored row can still carry `true`/a nonzero count — a fresh analysis always writes
  `timelineTruncated: false, omittedLineCount: 0` even when `reviewPassCount > 1`. Don't read
  `timelineTruncated` as "this was a big trace" any more; read `reviewPassCount > 1` for that. The
  view's two banners are therefore mutually exclusive by construction (an old truncated row was never
  partitioned into passes; a fresh partitioned row is never truncated), not by an explicit
  cross-guard in the JSX — each `Alert` just checks its own field.
  **The SSE protocol gained a `plan` event and `key`/`stepNumber`/`stepCount` fields** so progress
  from a repeated phase (DRAFTING recurring once per pass) doesn't visually collide — see the
  `AnalysisRunProgressView` gotcha above for the client-side half of this. `onPlan` is wired
  identically to `onStarted` in the container (same reducer, replaces `phases`) — it exists because
  `started` is sent optimistically, before the backend knows the final pass count, and `plan` is sent
  once it does.

- **The SummaryStrip Prompt row (and the Cost KPI) have their own query.** `SummaryStrip` renders
  the prompt from the optional `prompt` prop, threaded up as `firstUserPrompt` through
  `TraceDetailHeaderView` / `TraceDetailHeader` / `TraceDetailPageView` from a **third** page
  query, `['trace-summary', traceId]` → `fetchTraceSummaryOrNull` → `GET /api/traces/{id}/summary`.
  The same query's `totalCostUsd` field feeds `traceCostUsd`, the Cost KPI's only source, and its
  `backgroundCostUsd` field feeds the tile's background-cost tooltip (see the Cost section) —
  don't re-derive either from the spans query. That endpoint exists because the prompt
  is a trace-level field and the spans endpoint returns a bare array that can't carry one; the
  fetcher swallows its 404 to `null` so an unknown trace
  id is the waterfall query's error to report, not this one's. Every other header tile — tokens,
  span/tool counts, depth — stays derived from the spans already in hand; don't migrate those onto
  this query, and don't migrate Cost back off it. Prompt's absent/null hides the row entirely (no
  "—" placeholder), which is the normal state for traces
  rooted in a tool/model/mcp/compaction span and for traces recorded with prompt-body capture
  off (see the same gotcha in [../TracesPage/CLAUDE.md](../TracesPage/CLAUDE.md)).
- **`?span=<spanId>` is the one deliberate exception to "nothing is auto-selected".** The Metrics
  page's exemplar drawer opens `/traces/:traceId?span=<spanId>` so a reader lands on the request they
  clicked, not the top of the trace. `TraceDetailPage` reads it with `useSearchParams` and passes it to
  the view as `initialSpanId`; the view's one-shot effect (guarded by `initialSpanRevealedRef`, declared
  after the zoom-reset effect so its widening lands on top of the reset) waits until that span is in
  `spans`, then calls the existing `revealSpan` — expand ancestors, widen the zoom, select, scroll, exactly
  as a related-call link does. An id the trace does not contain (or a still-loading trace) reveals
  nothing and leaves the normal closed-drawer arrival; it is one-shot so a poll on a running trace or
  the reader closing the drawer never re-selects it. Arriving without the param is unchanged, and
  the "Next error" walk is still the only way into an auto-selection from the toolbar.
- **The drawer starts closed on every trace, and nothing is auto-selected.** `selected` is
  `useState<string | null>(null)` and no effect writes to it on mount, so arriving at
  `/traces/:traceId` shows the waterfall at full width, scrolled to the top. An earlier revision
  ran an "error-first" one-shot effect that selected `errorSpans[0]` and scrolled to it, which
  opened the drawer and jumped past the top of the trace before the reader had looked at it — on
  a trace whose errors are expected, that is a panel to close on every navigation. Errors are
  still one click away through the toolbar's "Next error" (`errorIndexRef` starts at -1, so the
  first press selects the first error). Don't re-add the auto-select.
- **The "Analyzing trace…" progress is streamed from the backend, and every phase in it is real.**
  The dialog used to advance a four-item checklist (`Reading trace spans` → `Reviewing tool calls &
  errors` → `Checking prompt quality` → `Drafting review`) on a fixed 3s `setInterval`, clamped at
  the last item. That was not merely imprecise, it was backwards: all four drained in nine seconds
  while a real run is seconds to minutes, so `Drafting review` silently absorbed the entire wait and
  read as stuck — the one complaint the indicator existed to prevent. The fix is **not more labels**:
  everything before the model call is milliseconds, so any list long enough to fill the wait would be
  invented. `POST /api/traces/{traceId}/analysis/stream` reports the four phases the backend can
  honestly say it has reached (`TraceAnalysisPhase`, backend `model/`), and `DRAFTING` — the only
  slow one — reports its progress **as the answer text itself**, rendered live in the same
  `ANALYSIS_SURFACE_SX` panel the finished analysis lands in, so nothing re-styles or re-flows at the
  moment the run completes. The phase *labels* come over the wire too, so renaming or adding one is a
  backend-only change; don't reintroduce a client-side label table. And don't add a timer back as a
  fallback for a slow first event — before the `started` event the view shows a bare
  "Starting the analysis…" spinner with no step named or counted, which is the honest rendering of
  "the stream hasn't said anything yet".
- **`AnalysisRunProgressView` renders the whole checklist, keyed on `phase.key` — never
  `phase.phase`.** Every phase in `progress.phases` gets its own row (done/active/pending derived
  positionally from `activeIndex`, an `aria-live="polite"` region around the list so a row completing
  is announced rather than silently swapped — it's the only thing that moves for minutes), all of
  them known up front from the `started`/`plan` events rather than growing a line at a time. This
  documentation previously (incorrectly) described a single-step "step N of M" display with the other
  phases hidden — that state never existed in this component; if you're chasing that behavior, you're
  chasing a doc bug, not a regression.
  **The windowed-review rework (an oversized timeline split into several DRAFTING passes) is why
  `key` exists at all as a field distinct from `phase`.** A phase that only ever happens once has
  `key === phase` and `stepCount === 1` (no suffix rendered); a phase that recurs — DRAFTING once per
  review window — gets a per-occurrence `key` (e.g. `"DRAFTING#2"`) and its row's label gets an
  appended `" — pass N of M"` (only when `stepCount > 1`, so a single-pass run's checklist is
  pixel-identical to before). Keying the row's React `key` and `activeIndex`'s lookup
  (`progress.phases.findIndex((phase) => phase.key === progress.activeKey)`) on `phase.phase` instead
  of `phase.key` is the exact bug this was built to avoid: two DRAFTING occurrences sharing a key
  collide during reconciliation and can flip an already-`done` earlier occurrence back to
  `active`/`pending` the moment the later one becomes active — pinned by
  `AnalyzeTraceDialogView.test.tsx`'s repeated-DRAFTING regression test. The container's
  `phaseKeyQueueRef`/`MINIMUM_PHASE_DISPLAY_MS` pacing queue (`AnalyzeTraceDialog.tsx`) paces on
  `key` for the same reason.
  **`draftKey` is the analogous fix for the draft TEXT buffer.** The container resets `draftText` to
  the incoming delta's text (rather than appending) whenever `delta.key !== current.draftKey` — a
  second review window's draft no longer appends onto the first window's, and — with no special
  casing needed — the later "apply this" step's own delta text no longer bleeds onto the findings
  draft that preceded it, since that step's `key` differs too.
- **The analysis panel is one fixed height across all three of its states.**
  `ANALYSIS_SURFACE_HEIGHT` (48vh) is the finished result's `maxHeight`, the live draft's `height`,
  and the placeholder skeleton's `height`. `AnalysisSurfacePlaceholder` — MUI `Skeleton` text lines
  in the same bordered box — exists to hold that height from the moment the run starts: without it
  the dialog opened at the size of a spinner and jumped to a half-screen panel on the first delta,
  the one moment the reader is most likely watching. The draft box takes the full height from its
  first character for the same reason, rather than growing into it. The skeleton emits more lines
  than fit and clips (`overflowY: 'hidden'`), which is what lets one fixed list fill the box at any
  viewport height; it is `aria-hidden`, since the live region above already says what's happening.
  The **stored-analysis** loading state deliberately keeps its small spinner row and gets no
  skeleton — it usually resolves into the short "Run analysis" explanation, so holding half a screen
  there would open the dialog huge and then collapse it.
- **The summary card splits a line into bullets on a middle dot, never on a comma.** `summaryRows`
  breaks `analysis.summary` into one row per `Label: value` line and then splits the value on
  `SUMMARY_CLAUSE_SEPARATOR` (` · `), the separator the backend's `buildTraceSummary` joins clauses
  with. An earlier revision split on `/,\s+/`, which shredded the two lines on this card that are
  free prose — the quoted request, and the quoted final message on `Outcome` — into bullets
  mid-sentence, and would now also cut a file away from its own `(Read ×2, Edit)` tool list, which
  is the one place the backend still uses a comma (nested one level down, inside a clause). A
  summary **stored before that change simply has no dot in it** and renders as one line per fact
  rather than as bullets: stored analyses are re-read, never migrated, so that degradation is the
  behavior, not a bug — both paths are pinned by tests. Don't reintroduce a comma fallback for the
  older rows; it would bring the shredding back with it.
- **Work, Outcome, Tools, Models, Files, and Cost on the summary card are client-computed, and
  that supersedes the backend's own text for those labels — but only once spans are in hand.**
  `TraceSummaryCard` takes optional `spans`/`logsBySpanId`/`traceCostUsd` props (threaded
  `TraceDetailPageView` → `AnalyzeTraceDialog` → `AnalyzeTraceDialogView` → the card, all three
  the page already has — nothing new is fetched). When `spans` is present, `summarizeTraceWork
  (spans)` (`summarizeTraceWork.ts`, this folder) replaces `Work`'s content with duration
  alone — not "N tool calls" / "N model calls" / duration as an earlier revision had it, since
  those two counts are also the Tools/Models section labels below (`Tools (N)` / `Models (N)`)
  and repeating them on Work was the same redundancy the Cost/Files rows never had. `Outcome`
  is also replaced — not with a recount of errors (the backend's `TraceSummary.errorCount` stays
  the one count of what this trace considers an error; nothing here re-tallies it from spans),
  but with a short status line read off the backend's own deterministic Outcome prefix
  (`outcomeErrorCount`, parsing `outcomeSummaryLine`'s always-"no errors" or
  always-"N error(s)…" opening — see `TraceAnalysisPromptBuilder` on the backend) plus an
  ok/warning `CheckCircleOutlineIcon`/`WarningAmberIcon` pair. The
  call-by-call detail (which call failed, how the turn ended) stays in "What went wrong" below;
  restating it here was the same redundancy Work/Tools had. The backend's own optional
  `Tools`/`Files`/`Cost`/`Skills`/`Compaction` lines are dropped from the always-visible rows
  entirely (`SUMMARY_ALWAYS_VISIBLE_LABELS` keeps only `Prompt`/`Work`/`Outcome`) since they're
  now superseded by the sections below. Four supplementary sections — Tools, Models, Files,
  Cost — sit behind a "Show Tools, Models, Files, Cost" toggle, **collapsed by default**
  (`useState(false)`, chevron flips via `sx` the same way `SummaryStrip`'s Overview toggle does),
  and are omitted entirely when there is nothing in any of them (an all-empty
  `hasSupplementaryContent` check) rather than rendering an empty disclosure. Deliberately
  **not** persisted to `localStorage` the way `SummaryStrip`'s collapsed state is
  (`summaryStripVisibility.ts`) — this dialog remounts fresh every time it's opened (no
  container state survives a close), so there is nothing for a stored preference to outlive;
  don't add a persistence module for this one. **Each section's own label now carries its
  count** — `Tools (N)` (`work.toolCalls`), `Models (N)` (`work.modelCalls`), `Files (N)`
  (`work.files.length`), `Cost ($X)` (`traceCostUsd`) — so the collapsed toggle button ("Show
  Tools, Models, Files, Cost") is no longer the only clue what's behind it. Tools and Models
  render as name chips with a `×N` count badge that appears **only when a name repeats** (a bare
  "×1" states nothing the chip's own presence doesn't already); **Tools chips are additionally
  tinted by `classifyToolCall`'s READ/EDIT/SEARCH/VERIFY/OTHER kind** (imported from
  `../../traceInsightsDerivations.ts`, `PHASE_KIND_COLOR_INDEX` + `colorForIndex` from
  `theme.ts`) — first-seen classification per distinct tool NAME (see that file's own
  `TraceWorkToolCount` doc for why a "Bash" spanning both `git status` and `cat file` still
  needs one color). `summarizeTraceWork.ts` classifies each name; `AnalyzeTraceDialogView.tsx`
  itself also imports `PHASE_KIND_COLOR_INDEX` directly to resolve the chip's color — see the
  gotcha near the bottom of this file for why the module lives at the page root, not in a
  component directory named after a feature this page doesn't have. Models chips are **not**
  colored by kind — there is no equivalent taxonomy for models.
  **Each file row leads with a `FileTypeIcon`** — a small fixed-width colored badge from
  `fileTypeBadge(path)` (`fileTypeBadge.ts`, this folder) naming the file's language off its
  extension (`java`→"JAVA"/orange, `tsx`→"TSX"/blue, ...), so a Java file and a TypeScript file in
  the same list read as visibly different kinds of thing at a glance rather than only differing in
  the path text itself. Files renders each path
  next to small chips (not bracketed text) for the *distinct* tools that touched it
  (deduplicated, first-seen order — reading the same file twice with the same tool bumps that
  tool's own chip count rather than duplicating the chip), colored by the same per-name kind
  lookup (`toolKindByName`, built once from `work.tools` so a file's chip always matches the
  color its Tools-section chip already took, never a second classification of the same name).
  **Each file-row chip's `×N` count is per-file, not the tool's trace-wide count from the Tools
  section above it** — `summarizeTraceWork` tracks a `Map<string, number>` of tool counts per
  file path (`TraceWorkFileTouch.tools: TraceWorkNameCount[]`), so a tool used 8 times across the
  whole trace but only twice on one file shows `×2` on that file's row and `×8` on its Tools chip.
  **Every file row first has the directory prefix it shares with every OTHER row in the list
  stripped off, computed once per file list rather than per row.** `commonDirectoryPrefix` (this
  file) splits each `work.files[].path` on `/` and walks forward while every path agrees at that
  segment, requiring at least two paths (nothing to share a prefix with a single file) and at
  least two shared segments (`sharedSegmentCount > 1`, not `> 0` — a shared leading slash alone
  isn't a meaningful directory to strip, it's just how every absolute path starts). On a real
  trace this prefix is almost always the whole project root
  (`/Users/.../agent-compass`), which says nothing about any individual file and, left in,
  pushes the part that actually differs between rows — which project directory, which file — off
  the edge of the row. **What's left after stripping is split at the last remaining slash into a
  directory half and a filename half, styled and truncated completely differently: the
  filename is bold, `text.primary`, and *never* truncates; the directory is `text.secondary`
  (de-emphasized) and is the only part allowed to lose characters, and only off its FRONT**
  (`truncateDirectoryFromFront`, this file) — the opposite end from the shared-prefix strip above,
  since what's cut here is whatever's left that's still furthest from the file itself, and the
  filename right after it is what a reader almost always cares about more than which directory
  it's nested under. Both truncations are plain JS string functions against a character budget
  (`FILE_DIRECTORY_TRUNCATE_LENGTH`, 64 for the directory half — generous rather than tight, since
  it grows to fill whatever the row's filename and tool chips leave via `flex: '1 1 auto'` and the
  dialog itself runs up to 920px wide), not CSS `text-overflow` and not a pixel-measured budget,
  since the row renders in the same monospace font every other path/id on this card uses, where a
  character is a near-constant width — no `ResizeObserver` needed. Each budget is an upper bound
  on the JS-computed string, not a guarantee of the rendered width: a `textOverflow: 'ellipsis'` +
  `overflow: 'hidden'` CSS pair stays on the directory element as a safety net, clipping further
  (at the trailing end, same as any ordinary overflowing text) on a viewport too narrow to fit
  even that generous a string, rather than letting it overflow the row outright — the filename
  gets no such pair, since it is never meant to clip at all. **Don't reach for a leading-ellipsis
  CSS trick** (`direction: 'rtl'` + `textAlign: 'left'` + `unicodeBidi: 'plaintext'`, the trick the
  design mockup's own `.fpath .fdir` rule uses) **for the directory's front-truncation** —
  `unicode-bidi: plaintext` lets the browser redetect directionality from the (ordinary LTR)
  content itself, which silently overrides the forced `rtl` and leaves the browser truncating at
  the trailing end regardless of the rule, the exact failure mode this module's own predecessor,
  `truncateFilePathMiddle`, was rewritten to fix once already (same root cause, different symptom:
  that version dropped the *whole leading path*, not just enough of it to fit a budget). The
  `title` tooltip carrying the full untouched path sits on the directory+filename wrapper
  specifically, not the outer row — the outer row also contains the `FileTypeIcon` badge and the
  tool chips, and a `title` up there would fold their text into anything reading the tooltip
  element's own text content. **Without `spans`** (the view's own older test fixtures, or a caller
  with no waterfall to draw from) nothing is filtered and nothing is replaced — the card renders
  exactly as it did before this change, full backend text included. This is why the pre-existing
  "shows the code-composed summary ahead of the model findings" test (no `spans` prop) still
  asserts on the backend's own `Tools:`/`Files:` lines unchanged.
  - **Cost's wording is deliberately conservative.** The backend-authoritative `traceCostUsd` —
    **never** a client-side sum, per this file's own Cost section above and its bug history — is
    stated exactly once, in the section's own label (`Cost ($X)`); the body does not repeat it as
    a "Total: …" line, since the label is already visible whenever the body is. When there was at
    least one model call, the body adds one line: the per-call figure summed via
    `costOfSelectedSpan(span, logs)` over exactly the spans `work.modelCalls` counted, worded "$X
    measured across N model calls" so it reads as a partial measurement rather than a second
    total. There is **no "$X not attributed
    to any span" remainder line** — the mockup has one, but computing it as
    `max(0, total − measured)` and calling it "unattributed" would overclaim precision this file's
    own Cost section explicitly warns against (the per-span sum and the trace total are not
    expected to reconcile exactly, so a subtraction between them is not a real figure for a
    specific unattributed request). Add it only if a future revision can word it as an
    approximate remainder without implying the two numbers were ever meant to reconcile.
  - **A third, independent figure sits below those two: the per-subagent cost breakdown**, from
    the dialog's own `['trace-cost-breakdown', traceId]` query (`fetchTraceCostBreakdown` →
    `GET /api/traces/{traceId}/cost-breakdown`, `enabled: open && supplementaryOpen` — the section
    this feeds starts collapsed, so the query stays disabled until the reader actually expands it;
    not part of the mutation, since the figure never changes as a result of running or
    regenerating a review). Rendered only when `costBreakdown.subagentCosts.length > 0` — a trace
    that never dispatched a subagent shows nothing new, the pre-existing Total/measured lines
    unchanged. Each row shows the subagent's label, cost, model-call count, and tool-call count,
    led by a `SubagentDispatchCallNumberBadge` for its `dispatchCallNumber` — the same call-
    numbering space as a markdown citation (see the call-number gotcha above), rendered through the
    same `CallCitationContext` the review text's own citation links read (the whole card sits
    inside the dialog's `CallCitationContext.Provider`), so clicking it jumps to the waterfall row
    that dispatched it exactly the way a cited call number does. It renders as plain, non-clickable
    "call N" text — not a dead link — whenever no citation target is available or the number falls
    outside `knownCallNumbers`, same fallback the markdown link renderer uses.
    **`costBreakdown.measuredCostUsd` (main loop + subagents + auxiliary) is a fourth figure, and
    is stated as its own line, never blended into or reconciled against `traceCostUsd` or
    `measuredModelCallCostUsd` above it** — per this endpoint's own doc comment
    (`api/types.ts#TraceCostBreakdown`) it is not guaranteed to sum to the trace's authoritative
    total, for the identical reason the per-span sum above it isn't: different measurement, not a
    partial view of the same one. Don't scale the subagent rows to force them to add up to
    `traceCostUsd` — render every figure labeled by what it is, same rule this file's Cost section
    states for the per-span figure.
  - **`summarizeTraceWork` takes only `spans`, not `logsBySpanId`.** An early draft of its
    signature carried both, matching how the card's data arrives — but every field it computes
    (tool/model counts, tool and model name chips, tool kind, file→tools) reads only span
    attributes and `tokenBreakdownForSpan`, so a second, always-unused parameter would fail
    `@typescript-eslint/no-unused-vars` for no benefit. Cost is computed separately in
    `TraceSummaryCard` itself, over `costOfSelectedSpan(span, logsBySpanId?.get(span.spanId))`,
    which is the only place in this card that needs the log buckets.
  - **`outcomeErrorCount` never claims resolution, only presence.** The mockup's status line
    reads "1 error, unresolved" — but nothing this component (or the backend's `TraceSummary`)
    tracks whether a later part of the trace actually fixed an earlier error, so the real status
    line states only the count ("1 error") or its absence ("No errors"), never a resolved/
    unresolved judgment it has no signal for.
- **A half-written draft needs no partial-markdown handling.** `react-markdown` re-parses from
  scratch every render, so an unterminated `**` or a list cut mid-item renders as the literal
  characters so far and resolves itself when the rest arrives. The draft box sticks to the bottom as
  text lands, but only while the reader is already within `SCROLL_STICK_THRESHOLD_PX` of it —
  scrolling up to re-read something must not be yanked back down by the next delta.
- **On the structured-output path there is no live draft, and the view detects that without a flag.**
  When `ollama.structured-output` is on the answer is a JSON document that is only a readable review
  once fully parsed, so the backend sends `delta` events carrying an empty `text` and a character
  count only. The view shows the count beside the active phase precisely when text never arrived.
  Deciding it that way rather than threading a `streamsText` boolean is what keeps the frontend
  ignorant of a backend setting it has no other reason to know about.
- **Closing the dialog mid-run does not cancel it.** The backend finishes and stores the analysis
  once started (see `TraceAnalysisSseStreamer`); reopening the dialog loads it through the ordinary
  `['trace-analysis', traceId]` query. So a stream that ends with neither `done` nor `failed` throws
  "reopen this dialog to see whether it finished" rather than reporting a failure it cannot know
  about.
- **Analysis failures arrive as an event, not a status code.** By the time the model call runs the
  SSE response is committed at 200, so an unknown trace id (a 404 on the plain POST) and an
  unreachable Ollama (a 503) both come back as a `failed` event; `streamTraceAnalysis` rethrows its
  message as an `Error` so the dialog's existing `regenerateError` path renders it unchanged.
- **The analysis is markdown, and inline code must not be rendered by a `code` component that
  branches on `inline`.** react-markdown **9 removed that prop** — a custom `code` component still
  reading it gets `undefined` on every span, takes its block branch, and renders each `backticked`
  file name, tool name and quoted observation line as a full-width `<pre>`, breaking every sentence
  the model wrote into three pieces. Since block code is only ever `pre > code`, `AnalyzeTraceDialogView`
  styles `code` as an inline chip in the container's `sx` and resets it under `& pre code`. That
  container `sx` is where nearly all the markdown styling lives; react-markdown's `components` map
  is deliberately down to a single entry, for the one rule that changes *structure* rather than
  looks: **a paragraph whose entire content is one `<strong>` is promoted to a section heading.**
  The model writes its section titles as `**What went wrong**` / `**Apply this**` — plus an optional
  `**What went well**` ahead of them, on the minority of traces where the backend verified a
  positive — rather than as markdown headings, so `#`-based styling never fires on this output at
  all. Nothing here enumerates those titles, which is why the third one needed no frontend change:
  the promotion rule is structural, not a list of known headings. The check is
  deliberately strict (one child, a `strong`, nothing beside it) so a finding that merely *starts*
  bold — `- **Bash Misuse** — …` — stays a bullet. Both are pinned by tests.
  `remark-gfm` is **not** installed, so a markdown table or `~~strikethrough~~` would render as
  literal text; nothing the answer contract asks for produces either.
- **The "Better wording" item is a before/after, not a copy card, and that is the whole point of
  it.** The other two "Apply this" items are text with a future — a rule goes into a `CLAUDE.md` and
  changes every trace after it, a tool swap is a standing correction — so a copy button is the right
  affordance for both. The wording advice is about a request that has *already run*; the reader is
  looking at this trace precisely because it finished, so pasting a "ready to paste" rewritten
  version of it could only re-issue work that is already done. Its value is instructional, and that
  only lands beside the words it replaces. So `WordingComparison` renders "You wrote" (the original)
  above "Say instead, next time" (the model's suggestion), and the Copy button still copies the
  suggestion alone, as a template for the next request rather than as this one re-issued.
  **The "before" is `analysis.userPrompt`, never anything the model wrote** — the backend stores the
  request it judged (`trace_analyses.user_prompt`, `V24`), because the answer contract already
  spends a rule on "never invent prompt wording" and having the model quote the request back would
  put the one half that can be supplied verbatim into the half that has to be policed. It is
  **null** for a trace whose wording nobody authored (a slash command, a task notification, a
  subagent run — the same traces whose review skips request quality) and on every row stored before
  that column existed, and the card then renders the suggestion alone rather than half a comparison;
  both paths are pinned by tests. The expander under a long original uses a **text heuristic**
  (`isLongerThanTheClamp`), not a `scrollHeight` measurement, deliberately: measuring would tie the
  one interactive control on this card to layout jsdom does not compute, and the cost of the
  heuristic being wrong is a toggle that expands something already fully visible.
- **The "Apply this" split parses three fixed line prefixes, and two of them carry a legacy
  alternative that must stay.** `parseApplyThis` splits the analysis on the literal
  `**Apply this**` heading and then finds each of `Instruction rule:` / `Tool swap:` /
  `Better wording:` independently, so the model reordering them changes nothing; anything that
  fails to match falls back to rendering the whole text as one markdown block, which is also what a
  pre-split stored analysis does. **Two of the three carry a legacy alternative**: the rule pattern also accepts
  **`CLAUDE.md rule:`**, the prefix used before the line grew a target, and the wording pattern also
  accepts **`Rewritten request:`**, the prefix used while that advice was framed as a request to
  paste and re-run. Analyses live in `trace_analyses` and are re-read on every dialog open, so
  dropping either alternative silently downgrades every older row to raw markdown. `splitTarget` then peels the target off that one line only: a tool swap or
  wording suggestion containing an em dash must keep it, which is why the split isn't applied to all
  three. The target renders as a chip and is deliberately **not** part of what the item's Copy
  button writes — it names the file to paste into, it isn't text to paste.
  **Which half of that line is the target is decided by shape, not by position.** The prompt asks
  for `<target> — <rule>`, but the model writes it either way round: trace
  `9a647d0710ffb63efa4c5fb0c7dd809b` stored ``Instruction rule: `<the whole rule>` — CLAUDE.md``,
  which the original positional split rendered backwards — the entire rule inside the chip and the
  bare `CLAUDE.md` as the copyable instruction. `APPLY_TARGET_SHAPE` matches the closed list the
  backend actually offers (`skill:<name>`, or a `*.md` path) against each side and takes whichever
  one matches, trailing side included. When **neither** side matches, the line is treated as all
  instruction and no target — that keeps an em dash inside a targetless rule (every analysis stored
  before targets existed) instead of eating its first clause into a chip. All three cases are
  pinned by tests.
- **The Overview panel's collapsed state persists across traces and reloads.** `SummaryStrip`
  initializes `collapsed` from `loadOverviewCollapsed()` (`../../summaryStripVisibility.ts`,
  `localStorage['ac-wf-overview-collapsed']`, same read-once-on-init idiom as
  `chipVisibility.ts`'s badge-family mutes) and `toggleCollapsed` persists every flip via
  `persistOverviewCollapsed`. Navigating to a different trace (`key={traceId}` remounting the view)
  re-reads the same stored preference rather than resetting to expanded. A prior revision removed
  an earlier version of this persistence on the reasoning that the panel shouldn't arrive collapsed
  from an unrelated earlier session — reinstated on request, so don't re-remove it without
  checking whether that reasoning still applies.
- **The cache hit-rate chip excludes output tokens.** `cacheHitRatePercent` in
  `../TracesPage/tokenBreakdown.ts` (shared by `SummaryStrip`'s trace-level chip and the drawer
  `TokensSection`'s per-span one — one formula, so the two can't drift)
  is `cacheRead / (cacheRead + input + cacheCreate)` — the share of *input-side* tokens served from
  the prompt cache. Output tokens are generated and never cacheable, so including them would
  deflate the rate with a denominator the cache can't influence. Returns `null` (chip hidden, no
  "—" placeholder) when the trace logged no input-side tokens. Nothing is fetched for it; it's
  derived from the `TokenBreakdown` the header already computes. Its violet tint resolves through
  `tokenComposition.cacheRead` + `primary.main`, matching the cache-read row in the composition list
  below it (usually the top row, since rows sort by magnitude and cache read is normally the
  largest) — not MUI's default `info` palette, which is an unrelated blue in this theme.
  **Both chips render `cacheHitRateLabel`, not the raw percent** — it rounds asymmetrically at the
  top of the range, so only an exact 100% prints "100%" and anything above 99 prints ">99%". On a
  real trace cache read is 99.x% of every input-side token, and "100% cached" reads as "nothing was
  billed", which is wrong by thousands of full-rate tokens. Use the label wherever a rate is shown;
  `cacheHitRatePercent` is for the tooltip figures and arithmetic.
- **The token figures split by rate, not by billed/free.** Cache read is billed too — at a tenth of
  the input rate — but it routinely runs 10-1000x the other three counts, so anything that scales
  all four together paints one solid bar and hides the numbers a reader is actually deciding on.
  `SummaryStrip`'s `TokenCompositionCard` handles this with a single **log-scaled** list instead —
  one `TokenRow` per nonzero token category (cache read / input / cache creation / output), sorted
  by magnitude descending, each bar's width `Math.log10(value + 1) / Math.log10(maxValue + 1) * 100`
  (clamped to a 4% floor) against the unfiltered max across all four categories — same reasoning as
  the Token Usage "over time" chart's log y-axis (see frontend/CLAUDE.md's stacked-chart-labeling
  section). The cache-read row alone carries an inline "0.1×" rate tag. `SpanWaterfallRow` still
  renders `SpanFullRateBadge` beside a deliberately quiet `SpanCacheReadBadge` — that two-badge split
  is unaffected by this and follows the same "don't recombine into one total" rule. Row and badge
  values carry `tokenShareLabel`, which clamps to "<0.1%" / ">99.9%" so a 40-token output row never
  reads "0%".
- **Token chips are pink; amber means cost.** The row's token badges and the toolbar legend's
  "tokens" key resolve through `tokenFigureColor(mode)` in `theme/colors.ts` (deeper pink on light,
  the bright pink on dark). Amber is reserved for cost and the "+N below" warning, so one row never
  shows two amber numbers meaning different things. This is one instance of the legend's broader
  rule — one key per badge family, see Badge visibility below — not a special case. The drawer's
  Tokens *section* keeps its amber treatment: it reads as a panel, not as a figure.
- **Badge visibility is a display preference, and the legend keys double as its controls.** The
  toolbar legend is six keys (`error`/`tokens`/`cache`/`cost`/`model`/`tool`), one per badge family
  a row can show; `ok`, which just named every bar's default color, was dropped as carrying no
  information. Five of the six — every key but `error` — are also toggles: clicking (or
  Enter/Space on) a key hides that badge family on every `SpanWaterfallRow`, rendering the key
  itself as a hollow swatch at `opacity: .45`. `error` is deliberately not a toggle: it names the
  row's status (the red bar), not an optional figure, and is the one thing never worth muting.
  State lives in `chipVisibility.ts` (`ChipFamily = 'tok' | 'cr' | 'cost' | 'mdl' | 'tool'`,
  `loadChipsOff`/`persistChipsOff`) and is owned by `TraceDetailPageView`'s `chipsOff` state,
  threaded to `WaterfallToolbar` (renders + toggles it) and every `SpanWaterfallRow` (gates its
  badges on it). **Unlike `collapsed`/`selected`/`view`, this state is deliberately outside the
  `key={traceId}` reset** — it's persisted to `localStorage['ac-wf-chips-off']` and read back on
  every mount, so muting a family stays muted while paging between traces rather than resetting per
  trace like the rest of the view's interaction state. A side effect worth knowing: muting badges
  frees up the name column, which is the cheap fix if the span name or model pill is clipping on a
  crowded row — don't chase that by widening `gridColumns` first.
- **The trace/session ids live in one combined identity pill in the header, not footer text.**
  `TraceDetailHeaderView` renders `IdentityPill` (`TraceDetailHeader/IdentityPill.tsx`) inline in
  the breadcrumb row, right after the "Trace detail" h1 — a single bordered/rounded container with
  the session segment first (it's the trace's parent) and the trace segment second, divided by a
  1px rule, no gap. Both segments are plain text (`title` carries the full value; neither is
  copy-to-clipboard anymore — the pre-Aurora `IdChip` copy icon was dropped). The session segment
  is entirely inert; clicking the trace segment (its caret signals this) opens `SwitchTraceModal`,
  listing every other trace recorded under that session (see the switch-trace section below) —
  **but only when the session has more than one distinct trace.** `IdentityPill` runs its own
  `fetchSessionPrompts` query (`enabled: Boolean(sessionId)`, not gated on a click) purely to count
  distinct trace ids among `hasTraceAndPrompt`-filtered rows; `canSwitchTraces` is false — no caret,
  default cursor, no hover, click is a no-op — both while that count is still resolving and once
  it resolves to one (or zero). Don't read "no caret" as "no session"; check `sessionId` itself for
  that case. Without a `sessionId` at all, only the plain, non-interactive trace segment renders —
  the pre-pill single-chip fallback. `SummaryStrip`'s `MetaFooter` still doesn't take
  `traceId`/`sessionId` props — it's down to Root span / Services / Started, laid out
  `justifyContent: 'space-between'` across the card's full width instead of left-clustered.
- **Switching traces reads the Sessions page's own prompt-timeline endpoint, not a new one.**
  `SwitchTraceModal` (and `IdentityPill`'s count query above it) call `fetchSessionPrompts
  (sessionId)` (`GET /api/sessions/{sessionId}/prompts`) — the same fetcher `PromptTimelinePanel`
  on `SessionsPage` uses, and the same query key (`['session-prompts', sessionId]`) across all
  three call sites, so they share one cache entry: by the time a reader clicks the pill enough
  times to open the modal, `IdentityPill`'s own eager query has almost always already populated it.
  `SwitchTraceModal`'s own query keeps `enabled: open` regardless — harmless extra safety, not a
  second fetch, since it just subscribes to the same cached/in-flight query. Rows with a null
  `traceId` (pre-tracing sessions) or a null `prompt` (capture disabled) are filtered out client
  side (`hasTraceAndPrompt`, exported from `SwitchTraceModalView.tsx` — the one place this
  predicate is defined, shared by the modal's row list and the pill's count) before rendering or
  counting — neither is a trace a reader can jump to. The **current** row (matching
  the page's own `traceId`) gets the same selected-row treatment `SpanWaterfallRow` uses (tinted
  background + `inset 2px 0 0 primary.main` left accent) and is inert; every other row navigates
  to `/traces/:traceId` and closes the modal on click. **There is deliberately no ERROR flag on
  non-current rows** — the design calls for one, but flagging it correctly needs a per-row
  cross-reference against each trace's error count, which the prompts endpoint doesn't carry.
  Doing that lookup per row here would turn every modal open into an N+1 burst of
  `trace-summary`-style requests; left as an explicit gap for a future backend field
  (e.g. an `errorCount` or `hasError` column on `SessionPromptRow`) rather than adding it.
- **A background-dispatched subagent's trace nests under its dispatcher in the switch-trace
  modal, scoped to this modal only.** `SessionPromptRow.dispatchingTraceId` names the trace whose
  turn background-dispatched a row's own trace (an Agent tool call whose subagent's conversation
  became its own trace). `SwitchTraceModal`'s `rows` `useMemo` runs the filtered rows through
  `nestSwitchTraceRows` (`switchTraceRows.ts`) — a pure, no-React module colocated here (same idiom
  as `components/AnalyzeTraceDialog/callCitations.ts`) that REORDERS rows so a child sits directly
  beneath its dispatcher (chronological order — the input's own order — wins wherever the two would
  conflict) and returns `NestedSwitchTraceRow[]` (`{ row, depth, railBelow }`). Nesting is
  recursive (a chained dispatch nests to depth 2+), resolves a shared trace id to its EARLIEST row
  (several turns can legitimately share one trace id), and treats every edge case — dispatcher
  absent from the row list, a self-referencing `dispatchingTraceId`, a dispatcher appearing AFTER
  its claimed child in array order — as "stay top-level, in place" rather than dropping/crashing.
  **This documentation previously said `SessionsPage`'s `PromptTimelinePanel` "adopted the
  identical algorithm in its own colocated file rather than importing from here" — that's now
  stale.** The two independent copies were de-duplicated: `nestSwitchTraceRows` here and
  `nestPromptRows` (`SessionsPage/.../promptTimelineRows.ts`) are both thin wrappers around one
  shared core, `../../../lib/nestDispatchedRows.ts#nestDispatchedRows` (see that module's own doc
  comment for the full reordering/depth/edge-case contract — identical to what was inlined in each
  file before). Each wrapper supplies its own row shape's `traceId`/`dispatchingTraceId` accessors
  and maps the shared result back into its own existing type/export (`NestedSwitchTraceRow` here
  has no `originalIndex` field, so this wrapper drops the one the shared core always returns;
  `SessionsPage`'s wrapper keeps it, since `windowBoundariesByOriginalIndex` there needs it). The
  reason the two call sites were never merged into a single function — this modal's rows are
  pre-filtered to `SwitchTraceRow`'s non-null `traceId`/`prompt` shape by `hasTraceAndPrompt`
  before nesting, while `PromptTimelinePanel` nests the full, unfiltered turn list (a turn's
  `traceId`/`prompt` can be null there) — still stands; only the duplicated algorithm itself moved
  into `lib/`. `SwitchTraceRow` and `hasTraceAndPrompt` still live in `switchTraceRows.ts` and are
  re-exported from `SwitchTraceModalView.tsx` unchanged, so `IdentityPill.tsx`'s import of
  `hasTraceAndPrompt` didn't need to move. `SwitchTraceModalRow` takes `depth`/`railBelow` and
  renders the connector (elbow + optional continuation line, per ancestor depth) via the shared
  `components/NestingConnector` — also de-duplicated against `PromptTimelinePanel`'s own
  independent elbow/rail implementation; see that component's doc comment and this file's own
  `SWITCH_TRACE_CONNECTOR_GEOMETRY` for the geometry this row passes it
  (`indentAppliedViaMargin: false`, since this row indents via `pl`, not a margin shift) — as
  separate `aria-hidden` absolutely-positioned `Box` elements painted IN FRONT OF the row's
  existing selection treatment — **never folded into the current row's `inset 2px 0 0
  primary.main` box-shadow accent**, the same separation `SpanWaterfallRow`'s own agent-dispatch
  wash already uses against that same accent (see the Subagent-dispatch coloring section). The
  row's horizontal padding split from one `px: 2.5` into `pr: 2.5` + a per-depth `pl` (`20 + depth
  * 18`px, `20` being `2.5 * 8`, so depth 0 renders pixel-identical to before); `GRID_COLUMNS`
  stays one constant regardless of depth — the `1fr` prompt column absorbs the extra left padding,
  keeping the fixed-width cost/tokens/current columns aligned in a straight line down the modal
  regardless of nesting depth.
- **`kind` is deliberately not on the waterfall row.** Nearly every real Claude Code span is
  `kind: internal` (tool calls, model sampling, MCP sub-spans) — only session / model /
  mcp-client spans differ — so a per-row pill repeated the same word down the whole trace
  without conveying anything. `kind` is unchanged on `SpanRow` and still shown once in the
  drawer's meta grid, where the client/server distinction reads. Its slot now carries the span's
  `attributes['tool_name']` as an info-colored chip at the end of the chip run, which is what
  actually tells one tool-call row from the next. Don't re-add the kind pill.
- **The tool chip carries the command.** `SpanToolBadge`'s tooltip shows `tool.status` and the first
  populated key of `full_command` / `command` / `file_path` / `pattern` / `query` / `url` —
  `full_command` first because on a Bash span `command` is only the heredoc's first line. It clamps
  at 300 chars and points at the drawer for the rest rather than growing a second expand path; the
  drawer's `longValue.tsx` owns that. A span with none of those keys says so explicitly instead of
  rendering an empty card. **`tool_name === 'Agent'` is a special case**: `subagent_type` (when
  populated) wins over every `TOOL_ARG_KEYS` entry, since an Agent span's "what was it asked to do"
  is which subagent it dispatched to, not a shell/file/search argument it doesn't carry. Every other
  tool is unaffected — the fallback to `TOOL_ARG_KEYS.find(...)` only runs when the span isn't an
  Agent span or `subagent_type` is absent/empty.
- **No window context.** This page does not use `useWindowContext()` or `useSectionContext()`.
  It has no `WindowSelector`, no auto-refresh, and no polling. The three query keys are keyed only
  on `traceId`; staleTime and refetchOnWindowFocus from the global `QueryClient` apply.
- **`key={traceId}` on the view.** `TraceDetailPage` passes `key={traceId}` to
  `TraceDetailPageView` so all view state (collapsed, selected, zoom) resets cleanly when
  navigating between traces without unmounting the page component.
- **`fetchSpansForTrace` vs `fetchTraceSpans`.** The container imports `fetchSpansForTrace`
  from `../TracesPage/tracesApi` (which has sample-data support), not `fetchTraceSpans` from
  `../../api` directly. `fetchTraceLogs` is imported directly from `../../api` because there is
  no sample-data equivalent.
- **`overflowX: hidden` is load-bearing.** The waterfall body has `overflowX: hidden` to
  prevent any span bar geometry (clamped to 0–100%) from introducing a horizontal scrollbar.
  Removing it breaks the layout on narrow viewports.
- **`scrollToSpan` measures with `getBoundingClientRect`, never `offsetTop`.** An earlier version
  set `scrollTop = row.offsetTop - 70`, which scrolled the target clean out of view: `offsetTop`
  is measured from the nearest *positioned* ancestor, and nothing between a waterfall row and
  `<body>` is positioned (`AppShell`'s only `position: fixed` is the mobile menu button), so the
  value included the app chrome, page header, toolbar, minimap, and axis. Live rects are relative
  to the viewport, so the container-vs-row delta is correct regardless of what sits above. If you
  ever add `position: relative` to the waterfall body, `offsetTop` would start working — don't
  take that as an invitation to switch back; the rect math also gives the scroll-only-if-needed
  behavior.
- **Self-time union sweep.** The sweep-line in `TraceDetailPage` uses millisecond timestamps
  (`Date.parse(child.startTimestamp)`) for union arithmetic, then converts the result back to
  nanoseconds (`unionMs * NANOS_PER_MILLI`) before subtracting from `durationNanos`. Getting
  the unit conversion wrong silently produces negative or wildly inflated self times.
- **Log sort priority.** `compareLogs` prefers `event.sequence` (integer index on the SDK's
  emitted event stream) over `event.timestamp` over `log.timestamp`. If only one of two logs
  carries `event.sequence`, the sequence-less log sorts after the sequenced one. This mirrors
  the intent — Claude Code >= 2.1.152 stamps `event.sequence` on all events in a span, so
  mixed-sequence batches are unusual.
- **Attribute deduplication in the drawer.** `SpanAttributeSections` drops keys that are already
  shown in the left meta column (normalized: strip unit suffixes like `_ms`/`_ns`, take the
  last dot-segment, lowercase) and any key matching `tokens?$`. Removing or widening `LEFT_KEYS`
  will re-surface those fields in the attribute grid.
- **The model badge is on the waterfall row, not the drawer.** `SpanWaterfallRow` renders
  `shortModelName(attributes.model ?? attributes['gen_ai.request.model'])` as a primary-tinted
  pill next to the span name — the tool chip's counterpart for `llm_request` spans, so a trace
  that switched models mid-run reads straight off the tree. Both keys are present on 100% of real
  `llm_request` spans and always agree; the fallback is for the OTel-canonical key outliving the
  vendor one, not for disagreement. In the **drawer**, model is deliberately left as an ordinary
  row in the generic Attributes section — it is not promoted into the meta grid and is not in
  `LEFT_KEYS`. An earlier revision did promote it; it was reverted as redundant with the badge.
  Non-model spans (tool, session, interaction) carry neither key and render no badge.
  When the span has an effort, it folds into that same pill as `Opus 5[1m] · high` rather than
  taking a second badge — the row already carries tokens and cost, and a fourth pill crowds it.
  `shortModelName` is the shared `lib/format` formatter every other per-model surface uses — it
  renders `claude-opus-5[1m]` as `Opus 5[1m]` and is imperfect on dated ids
  (`claude-haiku-4-5-20251001` → `Haiku 4 5 20251001`); the full raw id is the pill's `title`
  tooltip. Don't add a second model formatter to pretty that up — fix the shared one.
- **`effort` reaches the span by correlation, not as an attribute.** Claude Code never emits it
  on a span; it lives only on the `api_request` log. `Span.effort` is filled from the
  `span_efforts` view (`V15`), which joins the two on the **`request_id`** attribute — *not*
  `span_id`, because per V14 those logs are stamped with the span that was *active* (the
  interaction root, or a `tool.execution` span), never the `llm_request` child. A `span_id` join
  would attach effort to the wrong span and look plausible doing it. The correlation is exact
  rather than heuristic: `request_id` is unique on both sides (14,055/14,055 spans;
  14,047/14,047 logs), and input/output token counts agree on all 13,999 joined rows.
  `effort` is **null when not recorded** — ~2% of recent calls, more in older traces — and the
  badge then shows the model alone. Never substitute a default level; "unrecorded" and "medium"
  are different facts. `speed` *is* a span attribute but is `normal` on 100% of rows, so it
  carries no information — don't add it.
- **Long attribute value modal.** `longValue.tsx` owns the whole truncate-and-expand path for the
  drawer: `LongAttrValue` clamps and shows a "view formatted" button, and `LongValueModalProvider`
  hosts the one dialog at the drawer root. The modal runs the raw text through `tryParseJson`
  (jsonrepair) so truncated OTLP payloads display as best-effort formatted JSON with a repair
  warning banner. Two budgets, because the columns differ: `LONG_VALUE_LOG` (240) for the
  full-width log rows, `LONG_VALUE_ATTR` (110) for the attribute and event grids, where the key
  takes up to 42% of the row. Reach for `LongAttrValue` in any new section rather than clamping
  inline — the grids that had no clamp are exactly where a 4KB stderr or a heredoc `full_command`
  pushed the layout out of shape.
- **`traceColors.spanColor` (not `serviceColor`).** The minimap uses `spanColor(s.name)` to
  assign hues from the span *name*, not the OTel scope, because all real Claude Code spans share
  one scope. See `../TracesPage/components/traceColors.ts` for the name-to-hue mapping.
- **Minimap minimum zoom.** `minimumZoomMs = Math.max(1, totalMs * 0.02)` — the brush cannot
  be narrowed below 2% of the total trace duration, preventing a degenerate zero-width view.
- **Minimap interactions.** Four click-and-drag behaviors are supported:
  1. **Drag-to-select on bare track:** Press on empty track (not the brush or handles) and drag to
     create a new zoom range anchored at the press point. This is a `'create'` mode that lets you
     zoom into an arbitrary sub-range in one gesture instead of two aimed handle drags.
  2. **Zoomed-range indicator:** When the view is a sub-range (`viewStart > 0 || viewEnd < total`),
     a small monospace pill appears next to the legend showing `2.10s–6.40s` (bold time range) +
     `of 12.30s` (muted total), paired with `dbl-click resets` text. Full-view mode shows the
     plain affordance text instead.
  3. **Dim-outside instead of tint-inside:** The brush interior is transparent (bordered only),
     and two `.mmdim` overlays cover the excluded left/right regions with a `0.6` opacity
     disabledBackground color. Ticks inside the selection render at full contrast — the standard
     range-brush idiom. Do not switch this back to a filled brush interior; the dimmed exterior
     is more readable.
  4. **Error ticks can't be hidden by z-order:** Error ticks are sorted to the end of the render
     list (so they paint last) and get a red `box-shadow: 0 0 0 1.5px` ring in addition to their
     fill, making them identifiable even at 3px height when an ok/model/tool tick might otherwise
     cover them at the same x-position. Do not remove the sort or the ring.

## `traceInsightsDerivations.ts` — no panel, just a surviving taxonomy

There is **no "Insights" panel on this page.** `TraceInsightsPanel.tsx`/`TraceInsightsPanelView.tsx`
don't exist, nothing renders between the header and the waterfall besides `SummaryStrip`, and there
is no `GET /api/traces/{traceId}/insights` or `POST .../findings/{detectorId}/caption` endpoint —
no percentile ranks, no Findings section, no validated model captions, no phase timeline. If you're
reading an older description of any of that (percentile comparison, an interruption ledger, hook
overhead, a wall-clock breakdown, subagent dispatches, or a caption-validation pipeline), it does
not match this codebase; don't build against it without checking the actual files first.

What's real is a single module, `traceInsightsDerivations.ts` (page root, alongside the other
pure-function, no-React page-local modules like `spanRelations.ts`/`spanCallFacts.ts`) — exporting a
small tool-call classification surface: `classifyToolCall`/`PhaseKind`/`PHASE_KIND_COLOR_INDEX`, a
READ/EDIT/SEARCH/VERIFY/OTHER taxonomy over a tool call's name, paired with the palette index each
kind maps to via `colorForIndex` (`theme.ts`). Its consumers are `AnalyzeTraceDialog/
summarizeTraceWork.ts`, which classifies each Tools/Files chip by the identical taxonomy, and
`AnalyzeTraceDialog/AnalyzeTraceDialogView.tsx` itself, which imports `PHASE_KIND_COLOR_INDEX` +
`PhaseKind` directly to resolve the chip's rendered color from that classification — together they
mean a Read/Edit/Search/Bash chip on the Analyze Trace dialog's summary card takes a consistent hue
— see `summarizeTraceWork.ts`'s own doc comment above and its gotcha entry ("Work, Outcome, Tools,
Models…") for the reuse detail.

This module used to live in `components/TraceInsightsPanel/` (a component-shaped directory holding
no component — just this file plus an `index.ts` barrel) purely because that's where a since-removed
phase-timeline feature had put it. It was moved to the page root and the empty directory deleted:
nothing about the taxonomy is component-shaped, both of its consumers already live under
`components/AnalyzeTraceDialog/`, and the flat page-root location matches every other pure-logic,
no-React module this page keeps outside a component directory. If you're looking for it under
`components/TraceInsightsPanel/`, that path no longer exists.
