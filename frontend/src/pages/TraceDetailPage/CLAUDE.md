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
WaterfallToolbar   ──  "Span waterfall" label + legend + Expand/Collapse all + Next error
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
  spans through the same rule the Traces page uses, so the two never disagree.
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
│                               falls back to root span for logs with no usable span_id;
│                               sorts by event.sequence then event.timestamp
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
├── index.ts                    re-exports TraceDetailPage as default
└── components/
    ├── TraceDetailHeader/
    │   ├── TraceDetailHeader.tsx      container — memoizes serviceLabels, the four-way token breakdown,
    │   │                             and model-call/tool-call counts in one pass over spans; max depth
    │   │                             comes from the page's depthBySpanId and cost from the trace-summary
    │   │                             query (traceCostUsd), both threaded down rather than recomputed here
    │   ├── TraceDetailHeaderView.tsx  view — "Observability › Trace detail" breadcrumb (no id chips)
    │   │                             + SummaryStrip, whose KPI tiles are Cost/Duration/Spans/
    │   │                             Tool calls/Depth/Errors (Cost leads, gradient-emphasized)
    │   ├── SummaryStrip.tsx           collapsible "Overview" panel: header (click to collapse) +
    │   │                             optional "Prompt" row + the KPI tile row + TokenCompositionCard
    │   │                             (two labelled TokenTrack bars — "Full rate" scaled to the
    │   │                             input/output/cache-create subtotal, "Cache read 0.1×" scaled to
    │   │                             the trace total — + a 4-item legend carrying each kind's share,
    │   │                             "N% cached" chip, model-call count, total cost) +
    │   │                             MetaFooter (full trace/session ids, root span, services, started).
    │   │                             Tooltip fires only when a value element overflows; the panel
    │   │                             always starts expanded on navigation (collapse is per-view only)
    │   └── index.ts
    ├── WaterfallToolbar/
    │   ├── WaterfallToolbar.tsx       toolbar row: "Span waterfall" label + ok/error/tokens/cost legend
    │   │                             + GhostButton "Expand all / Collapse all" + "Next error" (when errors > 0)
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
    │   │                             weight differ; pure component (no view split)
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
│ Traces › Trace detail                                                            │
│ ┌─ SummaryStrip: "Overview" ─────────────────────────────────────────┐          │
│ │ ▾ OVERVIEW                                                          │  ← click to
│ ├────────────────────────────────────────────────────────────────────┤    collapse
│ │ PROMPT   Refactor the Aurora theme overlay so it applies cleanly…  │  ← only when
│ ├──────┬────────┬───────┬────────────┬───────┬────────┬──────────────┤    firstUserPrompt
│ │ Cost │Duration│ Spans │ Tool calls │ Depth │ Errors │              │  ← KPI tiles
│ ├────────────────────────────────────────────────────────────────────┤
│ │ TOKEN COMPOSITION 1.2M [>99% cached] 3 model calls · $0.42         │
│ │ FULL RATE      ▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇      54K   │
│ │ CACHE READ 0.1× ▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇  1.1M · >99.9%│
│ │ ■ Cache read 1.1M >99.9%  ■ Input 42K 3.5%  ■ Cache creation 9K 0.8%│
│ │ ■ Output 3K 0.2%                                                    │
│ ├────────────────────────────────────────────────────────────────────┤
│ │ TRACE ID 0102…  SESSION abc…  ROOT SPAN ■ …  SERVICES 2  STARTED … │  ← MetaFooter
│ └────────────────────────────────────────────────────────────────────┘          │
└──────────────────────────────────────────────────────────────────────────────────┘

Collapsed, the whole panel is one line: `▸ OVERVIEW   $0.42 · 4.2s · 31 spans ·
12 tool calls · 1.2M tokens · 2 errors`.

Below the header, a flex row fills the remaining height: the waterfall card
(`flex: 1; min-width: 0`) and the inspector drawer as a flex *sibling* — no
scrim/backdrop, so opening or resizing the drawer just narrows the waterfall
while every row stays visible at full height.

┌─ Waterfall card (flex: 1, min-width: 0) ────────────────┐ ┌─ SpanInspectorDrawer ────────┐
│ WaterfallToolbar: ≡ Span waterfall  [ok ■] [error ■]   │▐│ claude_code.llm_request  [✕] │
│         [tokens ■] [cost ■] [Expand all] [▲ Next error] │▐├──────────────────────────────┤
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
feeds two things: the header's Prompt row (`firstUserPrompt`) and the header's Cost KPI
(`traceCostUsd`, from `TraceRow.totalCostUsd`) — see Gotchas and the Cost section below. Every
other header figure (tokens, span/tool counts, depth) stays derived from the spans query.

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

The buckets feed the drawer only — its Logs section receives the pre-bucketed array for the
selected span, and `costOfSpanRequests` reads the same array for the per-call cost (see Cost).
The waterfall row deliberately carries no log-count badge: a count of correlated log records is
a property of the telemetry, not of what the span did, and it competed for row width with the
token, cost, and error pills that answer questions someone actually scans the waterfall for.

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
membership; `toggleAll` switches between empty set (all expanded) and `new Set(parentSpanIds)`
(all collapsed), driven by `anyCollapsed = parentSpanIds.some(id => collapsed.has(id))`.
The `WaterfallToolbar` button label tracks `anyCollapsed` live.

### Error navigation

`errorSpans` is a filtered `useMemo` of spans with `statusCode === 'error'`. On first render
after data loads, a one-shot `useEffect` (guarded by `initRef`) selects the first error span and
`scrollToSpan`s to it. `nextError` cycles through `errorSpans` by index using `errorIndexRef`
(a `useRef` so it doesn't trigger re-renders).

`scrollToSpan` reads the waterfall container ref, queries `[data-span="${spanId}"]` for the row,
and scrolls **the minimum distance** that brings the row inside the container's visible band,
keeping `min(2 rows, a quarter of the container)` of margin at whichever edge the row entered
from. A row already comfortably in view doesn't move the list at all — so stepping through spans
slides the highlight instead of yanking the waterfall on every press. It's shared by span nav,
`nextError`, and the initial error auto-select.

### Span selection and the inspector drawer

`selected` is a `string | null` span ID. `selectSpan` toggles: clicking the already-selected
span deselects it (closes the drawer). The view resolves `selected` into a
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
  on the span the page auto-selects first, which is where a user reaches for it.
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
  The same query's `totalCostUsd` field feeds `traceCostUsd`, the Cost KPI's only source (see the
  Cost section) — don't re-derive it from the spans query. That endpoint exists because the prompt
  is a trace-level field and the spans endpoint returns a bare array that can't carry one; the
  fetcher swallows its 404 to `null` so an unknown trace
  id is the waterfall query's error to report, not this one's. Every other header tile — tokens,
  span/tool counts, depth — stays derived from the spans already in hand; don't migrate those onto
  this query, and don't migrate Cost back off it. Prompt's absent/null hides the row entirely (no
  "—" placeholder), which is the normal state for traces
  rooted in a tool/model/mcp/compaction span and for traces recorded with prompt-body capture
  off (see the same gotcha in [../TracesPage/CLAUDE.md](../TracesPage/CLAUDE.md)).
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
  `tokenComposition.cacheRead` + `primary.main`, matching the cache-read track right below
  it — not MUI's default `info` palette, which is an unrelated blue in this theme.
  **Both chips render `cacheHitRateLabel`, not the raw percent** — it rounds asymmetrically at the
  top of the range, so only an exact 100% prints "100%" and anything above 99 prints ">99%". On a
  real trace cache read is 99.x% of every input-side token, and "100% cached" reads as "nothing was
  billed", which is wrong by thousands of full-rate tokens. Use the label wherever a rate is shown;
  `cacheHitRatePercent` is for the tooltip figures and arithmetic.
- **The token figures split by rate, not by billed/free.** Cache read is billed too — at a tenth of
  the input rate — but it routinely runs 10-1000x the other three counts, so anything that scales
  all four together paints one solid bar and hides the numbers a reader is actually deciding on.
  Both surfaces therefore split: `SummaryStrip`'s `TokenCompositionCard` renders two `TokenTrack`
  bars (the full-rate three scaled to `fullRateTokens(breakdown)`, cache read scaled to the trace
  total), and `SpanWaterfallRow` renders `SpanFullRateBadge` beside a deliberately quiet
  `SpanCacheReadBadge`. Don't recombine them into one total. Legend and track values carry
  `tokenShareLabel`, which clamps to "<0.1%" / ">99.9%" so a 40-token output row never reads "0%".
- **Token chips are pink; amber means cost.** The row's token badges and the toolbar legend's
  "tokens" key resolve through `tokenFigureColor(mode)` in `theme/colors.ts` (deeper pink on light,
  the bright pink on dark). Amber is reserved for cost and the "+N below" warning, so one row never
  shows two amber numbers meaning different things — which is why the toolbar legend has four keys,
  not three. The drawer's Tokens *section* keeps its amber treatment: it reads as a panel, not as a
  figure.
- **The trace/session ids are plain text now, not copy chips.** The Aurora cost sync removed
  `IdChip.tsx` and its `useCopyToClipboard.ts` hook (nothing else imported them) and moved both
  ids, un-truncated, into the meta footer. Re-adding a copy affordance means bringing back a
  component, not flipping a prop.
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
  rendering an empty card.
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
