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
                             descendant error counts, self time, log bucketing, sessionId),
                             passes ~13 plain props to the view
TraceDetailPageView.tsx      view — owns all UI state (collapsed set, selected span,
                             zoom ZoomView), derives visible row list per-render,
                             computes bar geometry, and composes all sub-components
```

The view renders four sub-components inline (no further drilling):

```
TraceDetailHeader  ──  breadcrumb + SummaryStrip KPIs (container/view split)
WaterfallToolbar   ──  "Span waterfall" label + legend + Expand/Collapse all (tool spans
                       only — see Collapse and expand) + Next error
TraceMinimap       ──  full-trace overview with drag-to-zoom brush
SpanWaterfallRow   ──  one row per visible span (single-file component, no view split)
SpanInspectorDrawer ── right-side width-resizable drawer for the selected span
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
    │   │                             (exported from SwitchTraceModalView, single source of truth for
    │   │                             "is this row a switchable trace"), and on a non-current row click
    │   │                             closes the modal and navigates to `/traces/:traceId`
    │   ├── SwitchTraceModalView.tsx   view — MUI Dialog, `min(800px, 92vw)` wide, `64vh` max height with
    │   │                             internal scroll. Header: "Switch trace · session <mono id>" +
    │   │                             GhostButton Close. Body: one SwitchTraceModalRow per turn, wrapped
    │   │                             in LongValueModalProvider (the same "view formatted" dialog
    │   │                             SpanInspectorDrawer uses — see SwitchTraceModalRow) so a long
    │   │                             ordinary prompt can be opened full-size rather than only ever
    │   │                             showing as a clipped line
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
    │   │                             when a value element overflows; the panel always starts expanded
    │   │                             on navigation (collapse is per-view only)
    │   └── index.ts
    ├── WaterfallToolbar/
    │   ├── WaterfallToolbar.tsx       toolbar row: "Span waterfall" label + a six-key legend
    │   │                             (error/tokens/cache/cost/model/tool — one key per badge
    │   │                             family, `ok` dropped as uninformative) where five of the
    │   │                             six keys double as SpanWaterfallRow badge-visibility
    │   │                             toggles (chipsOff — see Badge visibility below; `error`
    │   │                             is not a toggle) + GhostButton "Expand all / Collapse all"
    │   │                             (hidden when there is nothing to fold — see Collapse and
    │   │                             expand) + "Next error" (when errors > 0)
    │   └── index.ts
    ├── TraceMinimap/
    │   ├── TraceMinimap.tsx           full-trace overview bar-per-span (height staggered by depth ≤ 4)
    │   │                             + draggable zoom brush; drag body to pan, drag left/right edge to resize;
    │   │                             double-click resets to full trace; exports ZoomView { s, e }
    │   └── index.ts
    ├── SpanWaterfallRow/
    │   ├── SpanWaterfallRow.tsx       single row: index badge + span name + SpanFullRateBadge (pink
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
    │   │                             error/descendant-error is gated on the chipsOff prop (see Badge
    │   │                             visibility) — the toolbar legend's per-family mute toggle
    │   └── index.ts
    └── SpanInspectorDrawer/
        ├── SpanInspectorDrawer.tsx    right-side drawer, a flex sibling of the waterfall card (no
        │                             scrim — the waterfall stays visible/scrollable): left-edge
        │                             resize grip + header (span name, waterfall prev/next nav
        │                             ↑ "n / N" ↓ when >1 row is rendered, close ×) + one
        │                             scrolling column — meta grid (cost row, amber bold, after
        │                             duration when costUsd > 0), self-time bar, ErrorSection,
        │                             then the Tokens/Tool/Attributes/Events/Logs sections. Stays
        │                             mounted while closed (width 0) so the 0.2s width transition
        │                             runs; content is keyed by span id so section/log expand
        │                             state resets per selection; keeps the last selection
        │                             rendered during the close animation (guarded render-phase
        │                             setState, compared by span id) and drops it on the closing
        │                             transitionend, with `inert` while closed so nothing behind
        │                             `width: 0` stays tabbable. Wraps its content in
        │                             LongValueModalProvider, so a clamped value in any section opens
        │                             the same dialog
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
        │                             (info-tinted, wrench icon) + collapsible "Attributes" section
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

All three queries are enabled only when `traceId` is truthy (`enabled: Boolean(traceId)`).
None of them poll — this page has no `WindowSelection`, no auto-refresh, and no
`refetchInterval`. The logs query is intentionally eager (not gated on a span being selected)
so the drawer's Logs section has data the moment the user first selects a span. The summary query
feeds three things: the header's Prompt row (`firstUserPrompt`), the header's Cost KPI
(`traceCostUsd`, from `TraceRow.totalCostUsd`), and that same tile's background-cost tooltip
(`traceBackgroundCostUsd`, from `TraceRow.backgroundCostUsd`) — see Gotchas and the Cost section
below. Every other header figure (tokens, span/tool counts, depth) stays derived from the spans
query.

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

- `spanIndices` (`buildSpanIndices`) — 1-based DFS counter for the index badge on each row.
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
- **The drawer starts closed on every trace, and nothing is auto-selected.** `selected` is
  `useState<string | null>(null)` and no effect writes to it on mount, so arriving at
  `/traces/:traceId` shows the waterfall at full width, scrolled to the top. An earlier revision
  ran an "error-first" one-shot effect that selected `errorSpans[0]` and scrolled to it, which
  opened the drawer and jumped past the top of the trace before the reader had looked at it — on
  a trace whose errors are expected, that is a panel to close on every navigation. Errors are
  still one click away through the toolbar's "Next error" (`errorIndexRef` starts at -1, so the
  first press selects the first error). Don't re-add the auto-select.
- **The Overview panel always starts expanded.** `SummaryStrip` owns `collapsed` as plain
  `useState(false)` — no `localStorage` persistence. Navigating to a trace (including
  `key={traceId}` remounting the view for a different trace) always shows the panel expanded;
  a click-to-collapse only lasts for the current view. An earlier revision persisted the choice
  to `localStorage['trace-detail-overview-collapsed']` so it carried across traces; that was
  deliberately removed so the panel doesn't arrive collapsed from an unrelated earlier session.
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
