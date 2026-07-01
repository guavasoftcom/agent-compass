# Trace detail page

Single-trace span waterfall: renders every span in a trace as a horizontally-scaled waterfall,
with a minimap zoom brush, per-span detail dock (timing, tokens, attributes, span events, and
correlated log entries), and error navigation. Reached from `TracesPage` by clicking a trace row;
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
SpanDetailDock     ──  bottom dock for the selected span (single-file, multi-section)
```

**Cross-page utilities.** The page imports from `../TracesPage/` rather than duplicating:

- `fetchSpansForTrace` from `../TracesPage/tracesApi` — wraps `fetchTraceSpans` from the shared
  `api/` with sample-data support.
- `NANOS_PER_MILLI`, `formatDuration`, `formatTokens` from `../TracesPage/tracesApi`.
- `tokenBreakdownForSpan` from `../TracesPage/tokenBreakdown` — extracts `input / output /
  cacheCreate / cacheRead` counts from any span's attribute bag.
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
│                               and MUI chip color; used by LogEntry and SpanDetailDock
├── attrFormat.ts               attrValueAsString(v) — objects → JSON, null/undefined →
│                               literal string, primitives → String()
├── index.ts                    re-exports TraceDetailPage as default
└── components/
    ├── TraceDetailHeader/
    │   ├── TraceDetailHeader.tsx      container — derives serviceLabels + totalTokens, renders view
    │   ├── TraceDetailHeaderView.tsx  view — "Observability › Trace detail" breadcrumb + IdChip row
    │   │                             + SummaryStrip (Duration/Spans/Errors/Services/Tokens/Root span/Started)
    │   ├── SummaryStrip.tsx           bordered flex row of labeled metric cells with dividers;
    │   │                             tooltip fires only when the value element overflows
    │   ├── IdChip.tsx                 click-to-copy chip for traceId and sessionId (uses useCopyToClipboard)
    │   ├── useCopyToClipboard.ts      transient "copied" flag with auto-reset after 1200 ms
    │   └── index.ts
    ├── WaterfallToolbar/
    │   ├── WaterfallToolbar.tsx       toolbar row: "Span waterfall" label + ok/error/tokens legend
    │   │                             + GhostButton "Expand all / Collapse all" + "Next error" (when errors > 0)
    │   └── index.ts
    ├── TraceMinimap/
    │   ├── TraceMinimap.tsx           full-trace overview bar-per-span (height staggered by depth ≤ 4)
    │   │                             + draggable zoom brush; drag body to pan, drag left/right edge to resize;
    │   │                             double-click resets to full trace; exports ZoomView { s, e }
    │   └── index.ts
    ├── SpanWaterfallRow/
    │   ├── SpanWaterfallRow.tsx       single row: index badge + kind badge + span name + SpanTokenBadges
    │   │                             (billable-token pill + cache-read pill) + error/descendant-error/log-count
    │   │                             pills + timeline bar + duration label; pure component (no view split)
    │   └── index.ts
    └── SpanDetailDock/
        ├── SpanDetailDock.tsx         resizable bottom dock: grip + title + close; three columns —
        │                             col 1: meta grid + self-time bar + status message + TokensSection
        │                             col 2: SpanAttributesColumn (Tool group + Attributes group)
        │                             col 3: SpanEventsList + log entries (LogEntry)
        ├── TokensSection.tsx          input/output/cache_creation table + cache_read dashed row
        ├── SpanAttributesColumn.tsx   filters redundant keys; splits tool-related attrs into a
        │                             "Tool" group (tinted blue) vs plain "Attributes" group
        ├── SpanEventsList.tsx         timestamped span event cards (T+offset from span start)
        ├── LogEntry.tsx               per-log row: offset + severity + event.name + tool badges + body;
        │                             click to expand attributes; long values (> 240 chars) open a
        │                             modal with tryParseJson repair + copy-to-clipboard
        ├── dockParts.tsx              shared dock primitives: clock(ms) wall-clock formatter,
        │                             SectionTitle (with count badge and token/tool tone variants),
        │                             AttrRows (key/value grid with tinted borders per tone)
        ├── useResizableHeight.ts      drag-to-resize hook; clamps 150px–72% viewport; grip mouse-down
        │                             registers global mousemove/mouseup and cleans up on mouseup
        └── index.ts
```

## Visual layout

```
┌─ TraceDetailHeaderView ──────────────────────────────────────────────────────────┐
│ Observability                                                                    │
│ Traces › Trace detail   [trace: 0102…] [session: abc…]                          │
│ ┌──────┬───────┬────────┬──────────┬────────┬──────────────┬─────────┐          │
│ │Durat.│ Spans │ Errors │ Services │ Tokens │  Root span   │ Started │  ← SummaryStrip
│ └──────┴───────┴────────┴──────────┴────────┴──────────────┴─────────┘          │
└──────────────────────────────────────────────────────────────────────────────────┘

┌─ Waterfall card (flex column, rounded border, bgcolor paper) ────────────────────┐
│ WaterfallToolbar: ≡ Span waterfall  [ok ■] [error ■] [tokens ■]  [Expand all]  │
│                                                          [▲ Next error]         │
├─ TraceMinimap ───────────────────────────────────────────────────────────────────┤
│  drag to zoom · dbl-click resets                                                │
│  ░░░░░░░░░░░░░░░▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░  ← span bars staggered by depth       │
│              [←│  brush  │→]              ← draggable zoom window               │
├─ Axis (time tick labels at 0 / 25 / 50 / 75 / 100%) ────────────────────────────┤
│ Span                    │  0ms    125ms   250ms   375ms   500ms                 │
├─ Body (overflowY auto, overflowX hidden) ────────────────────────────────────────┤
│ ▶ [1] INTERNAL  claude_code.session  ●tokens  ●●●●●         [████████] 500ms   │
│   · [2] CLIENT  claude_code.llm_request  ●2.4k      [████] 310ms              │
│   · [3] INTERNAL  claude_code.tool.execution         [██] 45ms  error          │
│   · [4] INTERNAL  claude_code.tool.execution    [█] 12ms  +1 below  2 logs     │
│   · [5] INTERNAL  claude_code.tool.execution        [██] 38ms                  │
│   (scroll continues …)                                                           │
└──────────────────────────────────────────────────────────────────────────────────┘

┌─ SpanDetailDock (resizable, default 320px, mt 2) ────────────────────────────────┐
│ ═══════════ grip (drag up/down to resize) ═══════════                           │
│ claude_code.llm_request                                          [✕]            │
│ ┌── col 1 ──────────────────┐ ┌── col 2 ──────────────────┐ ┌── col 3 ──────┐ │
│ │ span id   <hex>           │ │ TOOL (n)                   │ │ EVENTS (n)    │ │
│ │ kind      CLIENT          │ │  [tinted blue attr rows]   │ │  T+0ms event  │ │
│ │ scope     …               │ │ ATTRIBUTES (n)             │ │ LOGS (n)      │ │
│ │ status    ok/error        │ │  [plain attr rows]         │ │  T+12ms INFO  │ │
│ │ started   HH:MM:SS.mmm    │ │                            │ │  tool_result  │ │
│ │ ended     HH:MM:SS.mmm    │ └────────────────────────────┘ │  (expand …)   │ │
│ │ duration  310ms           │                                └───────────────┘ │
│ │ self time [████░░░] 87%   │                                                  │
│ │ status message (error)    │                                                  │
│ │ TOKENS (2,400)            │                                                  │
│ │  input          1,800     │                                                  │
│ │  output           600     │                                                  │
│ │  cache_creation     0     │                                                  │
│ │  cache_read     1,200 ~1/10 rate                                             │
│ └───────────────────────────┘                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

Both fetchers live in the shared `api/` barrel (`from '../../api'`); spans are additionally
wrapped by `tracesApi.fetchSpansForTrace` for sample-data compatibility.

| Source                                      | Query key                        | Fetcher → endpoint                                          |
|---------------------------------------------|----------------------------------|------------------------------------------------------------|
| `TraceDetailPage` (`useQuery`)              | `['trace-spans', traceId]`       | `fetchSpansForTrace(traceId)` → `GET /api/traces/{traceId}` |
| `TraceDetailPage` (`useQuery`, eager)       | `['trace-logs', traceId]`        | `fetchTraceLogs(traceId)` → `GET /api/traces/{traceId}/logs` |

Both queries are enabled only when `traceId` is truthy (`enabled: Boolean(traceId)`).
Neither query polls — this page has no `WindowSelection`, no auto-refresh, and no
`refetchInterval`. The logs query is intentionally eager (not gated on a span being selected)
so the dock's Logs section has data the moment the user first selects a span.

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
  minimap stagger, used directly for the waterfall indent (`pl: 10 + depth * 15`).
- `descendantErrorCounts` — recursive count of `statusCode === 'error'` spans below each span;
  shown as "+N below" in warning color on collapsed parent rows.

`selfTimeNanosBySpanId` subtracts the union of children's wall-clock intervals from the span's
`durationNanos`. Children are sorted by start and merged (standard sweep-line union), so
overlapping children count only once. Leaf spans retain their full duration as self time. The
result drives the self-time progress bar in the dock's left column.

`sessionId` is extracted from the root span's `attributes['session.id']` or
`resourceAttributes['session.id']`, surfaced in the header's `IdChip` row.

### Log bucketing

`bucketLogsBySpan(logs, tree, rootSpanId)` in `logBuckets.ts` attaches each `LogRow` to its
OTLP `spanId` if that span exists in the tree; otherwise the log falls back to `rootSpanId`.
Within each bucket logs are sorted by `event.sequence` (when present on both sides) then by
`event.timestamp` attribute (the authoritative SDK wall-clock time) then by
`log.timestamp` (the OTLP record time, which can lag by seconds when the exporter batches).

The `logCount` badge on each waterfall row is `logsBySpanId.get(spanId)?.length ?? 0`.
The dock's Logs section receives the pre-bucketed array for the selected span.

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
(a `useRef` so it doesn't trigger re-renders). `scrollToSpan` reads the waterfall container ref
and queries `[data-span="${spanId}"]` to locate the row element, scrolling to `offsetTop - 70`.

### Span selection and the detail dock

`selected` is a `string | null` span ID. `selectSpan` toggles: clicking the already-selected
span deselects it (closes the dock). When a span is selected the dock finds the full `SpanRow`
by ID and renders `SpanDetailDock` with the pre-computed `selfNanos`, `tokens` (from
`tokenBreakdownForSpan`), and `logs` bucket.

`SpanDetailDock` is resizable via `useResizableHeight(320)`: dragging the top grip registers
`mousemove`/`mouseup` on `document`, computes `startH + (startY - currentY)` (drag upward
increases height), and clamps to `[150px, 72% viewport]`.

## Gotchas

- **No window context.** This page does not use `useWindowContext()` or `useSectionContext()`.
  It has no `WindowSelector`, no auto-refresh, and no polling. The two query keys are keyed only
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
- **Self-time union sweep.** The sweep-line in `TraceDetailPage` uses millisecond timestamps
  (`Date.parse(child.startTimestamp)`) for union arithmetic, then converts the result back to
  nanoseconds (`unionMs * NANOS_PER_MILLI`) before subtracting from `durationNanos`. Getting
  the unit conversion wrong silently produces negative or wildly inflated self times.
- **Log sort priority.** `compareLogs` prefers `event.sequence` (integer index on the SDK's
  emitted event stream) over `event.timestamp` over `log.timestamp`. If only one of two logs
  carries `event.sequence`, the sequence-less log sorts after the sequenced one. This mirrors
  the intent — Claude Code >= 2.1.152 stamps `event.sequence` on all events in a span, so
  mixed-sequence batches are unusual.
- **Attribute deduplication in the dock.** `SpanAttributesColumn` drops keys that are already
  shown in the left meta column (normalized: strip unit suffixes like `_ms`/`_ns`, take the
  last dot-segment, lowercase) and any key matching `tokens?$`. Removing or widening `LEFT_KEYS`
  will re-surface those fields in the attribute grid.
- **Long attribute value modal.** `LogEntry` truncates inline display at 240 chars and shows a
  "view formatted" button. The modal runs the raw text through `tryParseJson` (jsonrepair) so
  truncated OTLP payloads display as best-effort formatted JSON with a repair warning banner.
- **`traceColors.spanColor` (not `serviceColor`).** The minimap uses `spanColor(s.name)` to
  assign hues from the span *name*, not the OTel scope, because all real Claude Code spans share
  one scope. See `../TracesPage/components/traceColors.ts` for the name-to-hue mapping.
- **Minimap minimum zoom.** `minimumZoomMs = Math.max(1, totalMs * 0.02)` — the brush cannot
  be narrowed below 2% of the total trace duration, preventing a degenerate zero-width view.
