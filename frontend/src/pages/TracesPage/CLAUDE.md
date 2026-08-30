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
# Traces page

Distributed-trace explorer: throughput histogram with p95 overlay and bar-click zoom, faceted
filtering, full-text search, and a Stream (cursor-paged, live-tailable) or Table (offset-paged)
body. Each row expands to an inline span summary. Backend counterpart: `TracesController` →
`TraceExplorerService` (`backend/.../controller/TracesController.java`).

## Architecture (read this first)

This page deliberately deviates from the container/presentational rule in
[frontend/CLAUDE.md](../../../CLAUDE.md) ("the view is pure props in, JSX out — no `useQuery`, no
fetch, no context"). The page is data-dense enough that prop-drilling the whole explorer state
through the view produced a ~47-prop view. Instead it uses a **page-scoped context**:

```
TracesPage.tsx              container — just <TracesExplorerProvider><TracesPageView/></Provider>
TracesExplorerContext.tsx   provider: resolves the window, owns the auto-refresh polling,
                            calls useTracesExplorer() ONCE, exposes the result + window chrome
                            via useTracesExplorerContext()
useTracesExplorer.ts        ALL page behavior: filters, the 3 queries, stream cursor-paging,
                            live tail, table paging, every handler, and derived flags
TracesPageView.tsx          pure layout — reads context, takes ZERO props
```

**The rule for this folder:** behavior lives in `useTracesExplorer`; the provider wires it to
the global window context; consumers *read*, they don't drill.

**Each component is itself split container/view** (`X.tsx` + `XView.tsx`, `index.ts` re-exports the
container as default). The container owns the behavior — context reads, local state, queries,
derivation — and renders nothing but `<XView ... />`; the view is pure props in, JSX out (the same
Page/PageView rule from [frontend/CLAUDE.md](../../../CLAUDE.md), applied per component). View-prop
types live in the `XView.tsx` file that owns them (e.g. `HistogramSeries`, `TraceFacetSelections`,
`TraceSummaryModel`/`OpGroup`); `index.ts` re-exports those.

- **Page-scoped components — container reads context, view takes props** — they are inherently
  TracesPage-specific (they know about `TraceRow`, facets, the histogram shape): `TraceHistogram`,
  `TraceFacetRail`, `TraceStream`, `TraceTable`, `TraceFilterChips`. The container calls
  `useTracesExplorerContext()` and aliases each field to a named view prop; the view never touches
  the context. Pure rendering math (bar heights, the p95 polyline, latency log-scale, pagination
  bounds, chip building) and view-local UI state (histogram hover tooltip, the stream's
  load-on-scroll effect) live in the view; only the context wiring lives in the container.
- **Generic leaf widgets stay prop-driven** — small, reusable, no knowledge of the page.
  `TraceSortDropdown` is split (its container owns the open/select state, the view renders).
  `TraceViewToggle` and `TraceTailToggle` are pure presentational already — no behavior to extract,
  so they stay single-file views. (`TraceViewToggle` is now a thin wrapper over the shared
  `components/StreamTableToggle`, preserving its `view`/`onViewChange` prop signature so the
  context wiring is unchanged.) The page view feeds all three their props from context. Keep them
  decoupled; don't wire them to the context.
- **Row-scoped component — container owns the query, view renders** — `TraceSummaryInline` takes a
  single `trace` prop because each instance is bound to one expanded row, not to page state. The
  container runs the `['trace-inline-spans', traceId]` query and the span-derivation `useMemo`; the
  view renders the resulting model. It deliberately summarizes where the trace spent its time/tokens
  rather than re-drawing the detail page's waterfall.

When adding behavior: add state/handlers to `useTracesExplorer`, surface them in its return
object (the view's prop surface is `ReturnType<typeof useTracesExplorer>` plus window chrome, so
new fields flow through automatically), then consume via context where needed.

## Files

```
TracesPage/
├── TracesPage.tsx            container (provider wrapper only)
├── TracesPageView.tsx        view — context in, layout out, no props
├── TracesPageView.test.tsx   vitest coverage for the view — renders inside a
│                             TracesExplorerContext.Provider fixture value rather than props,
│                             since this view is the documented context-pattern exception
│                             (takes zero props, reads useTracesExplorerContext())
├── TracesExplorerContext.tsx provider + useTracesExplorerContext(); window resolve + reload poll
├── useTracesExplorer.ts      the behavior hook (returns TracesExplorer)
├── tracesApi.ts              the 5 fetch* functions + USE_SAMPLE_DATA; re-exports traceTypes +
│                             traceDerivations so `from './tracesApi'` stays the single import surface
├── traceTypes.ts             shared types (TracesFilters, TraceHistogram, TraceFacets, cursors, …) — no runtime
├── traceDerivations.ts       serviceOf/statusOf/durationMsOf/tokensOf/costOfTrace/promptOf/formatTokens/formatUsd/
│                             formatDuration/quantile/durationBucketOf/DURATION_BUCKETS/NANOS_PER_MILLI +
│                             buildTracesQuery (shared by fetchers + sample)
├── tokenBreakdown.ts         tokenBreakdownForSpan(span) → per-span input/output/cacheCreate/cacheRead
│                             + cacheHitRatePercent(breakdown) → input-side cache-hit share as a whole
│                             percent (null when there were no input-side tokens). The ratio itself is
│                             NOT computed here — it delegates to lib/cacheEfficiency, the dashboard's
│                             single definition; this only bridges the cacheCreate/cacheCreation field
│                             name and rounds. Shared with TraceDetailPage's token split, along with
│                             fullRateTokens(breakdown) (input + output + cacheCreate — the tokens
│                             priced at full rate, what the trace-detail token tracks and chips scale
│                             to) and two label formatters: cacheHitRateLabel (the display form —
│                             only an exact 100% prints "100%", anything above 99 prints ">99%") and
│                             tokenShareLabel(value, total) (clamped "<0.1%" / ">99.9%").
├── tracesSampleData.ts       VITE_TRACES_SAMPLE synthetic store + in-memory query engine (sampleHistogram/
│                             Facets/Cursor/Page/Spans); split out so it can't cycle with the network layer.
│                             RNG + latency helpers come from lib/sampleData (shared with LogsPage)
├── components/            each folder = X.tsx (container) + XView.tsx (view) + index.ts, except
│   │                      the two pure leaves which are single-file views
│   ├── TraceHistogram/       throughput bars + p95 polyline + legend + bar-zoom (context, no fetch)
│   │   └── TraceHistogramView.test.tsx  vitest coverage for the view (renderWithProviders,
│   │                         prop fixtures)
│   ├── TraceFacetRail/       search box + status/operation/service/duration/session facets (context)
│   ├── TraceStream/          cursor-paged infinite scroll-back (context)
│   │   └── TraceStreamView.test.tsx     vitest coverage for the view (renderWithProviders,
│   │                         prop fixtures)
│   ├── TraceTable/           offset-paged table + shared TablePager footer (context)
│   │   └── TraceTableView.test.tsx      vitest coverage for the view (renderWithProviders,
│   │                         prop fixtures)
│   ├── TraceFilterChips/     active zoom + filter chips + "Clear all" (context)
│   │   └── TraceFilterChipsView.test.tsx  vitest coverage for the view (renderWithProviders,
│   │                         prop fixtures)
│   ├── TraceViewToggle/      Stream|Table toggle — thin wrapper over shared StreamTableToggle
│   ├── TraceTailToggle/      live-tail pill (pure prop-driven leaf, single file)
│   ├── TraceSortDropdown/    sort menu (container owns open state; prop-driven leaf)
│   │   └── TraceSortDropdownView.test.tsx  vitest coverage for the view (renderWithProviders,
│   │                         prop fixtures)
│   ├── TraceSummaryInline/   expanded-row span summary; container owns query (prop: trace)
│   │   └── TraceSummaryInlineView.test.tsx  vitest coverage for the view (renderWithProviders,
│   │                         prop fixtures)
│   └── traceColors.ts        service → color (also imported by TraceDetailPage)
└── index.ts
```

## Visual layout

```
┌─ PageLayout ────────────────────────────────────────────────────────────┐
│ eyebrow/title/subtitle          [PageActions: WindowSelector ⟳ auto]    │
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─ Paper: TraceHistogram ─────────────────────────────────────────────┐ │
│ │ stacked ok/error bars per bucket · p95 polyline · legend toggles    │ │
│ │ series · clicking a bar zooms the page window into that bucket       │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│ [Stream|Table] [● Live tail]                    [ 1,234 traces counter ] │
│ [zoom chip] [filter chips…] [Clear all]   ← TraceFilterChips, when active │
│ ┌─ fixed-height body (calc(100vh - 480px)) ───────────────────────────┐ │
│ │ Stream view:                          Table view:                   │ │
│ │ ┌ TraceFacetRail ┐ ┌ TraceStream ───┐ ┌ TraceTable ───────────────┐ │ │
│ │ │ search box     │ │ expandable rows│ │ paged rows; expand →       │ │ │
│ │ │ ☐ Status       │ │ → TraceSummary │ │ TraceSummaryInline         │ │ │
│ │ │ ☐ Operation    │ │   Inline       │ │ page-size 25/50/100        │ │ │
│ │ │ ☐ Service      │ │ scroll = more  │ │                            │ │ │
│ │ │ ☐ Duration     │ └────────────────┘ └────────────────────────────┘ │ │
│ │ │ ☐ Session      │                                                    │ │
│ │ └──── 236px ─────┘                                                    │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

Both bodies carry a **Prompt** column — the trace's initiating user prompt, truncated to one
ellipsized line with the full text on hover. Stream: between Operation and Latency
(`minmax(180px,1fr)` in `GRID_TEMPLATE_COLUMNS`). Table: between Root span and Service
(`maxWidth: 260`).

Both also carry a **Cost** column between Duration and Spans — the trace's total model spend
(`costOfTrace` → `TraceRow.totalCostUsd`, rendered through `formatUsd`), bold when non-zero and a
dimmed "—" when the trace made no model calls. It pairs with the **"Highest cost"** sort
(`sort=cost`), which the backend serves from the same `total_cost_usd` aggregate the column
displays.

## Who calls which API

All fetchers live in `tracesApi.ts` (not the shared `api/index.ts`) and share `buildTracesQuery(filters)` for the
query string. `filtersKey = JSON.stringify(filters)`, where `filters` already has the zoom range
substituted for the window range when a zoom is active.

| Source                                          | Query key                                       | Fetcher → endpoint |
|-------------------------------------------------|-------------------------------------------------|--------------------|
| `useTracesExplorer` (`useQuery`)                | `['trace-histogram', filtersKey]`               | `fetchTraceHistogram(filters, 48)` → `GET /api/traces/histogram?…&buckets=48` |
| `useTracesExplorer` (`useQuery`)                | `['trace-facets', filtersKey]`                  | `fetchTraceFacets` → `GET /api/traces/facets?…` |
| `useTracesExplorer` (`useQuery`, Table only)    | `['trace-table', filtersKey, sort, page, size]` | `fetchTracesPage` → `GET /api/traces?…&page=N&size=M` (`enabled: view === 'table'`) |
| `useTracesExplorer` (manual state, NOT cached)  | — (lives in hook `useState`)                    | `fetchTracesCursor(filters, {sort, cursor, limit:60})` for reset/`loadMore`; `{after, limit:20}` every 1.5 s for tail → `GET /api/traces?…` |
| `TraceSummaryInline`                            | `['trace-inline-spans', traceId]`               | `fetchSpansForTrace` → per-trace spans |

`TraceHistogram`, `TraceFacetRail`, and `TraceFilterChips` never fetch — they read
`histogramData` / `facetsData` (and filter state) from context.

## Data flow and semantics

- **Single source of state**: `useTracesExplorer` owns filter state (search, facet selections,
  zoom), the three queries, stream cursor-paging, table offset state, histogram-series visibility,
  row expansion, sort, and the Stream/Table view. It returns a flat object; the context spreads it
  alongside the window chrome (`selection`, `windows`, `windowLabel`, `error`, `onReload`,
  `autoRefresh`, `onAutoRefreshChange`, `isPolling`, and `onSelectionChange`).
- **Window resolution**: the provider memoizes the shared `resolveWindow(selection)`
  (`../../lib/resolveWindow.ts`, also used by LogsPage) on `[selection]` — for a preset this
  stamps `start = end - clampedSpan`, `end = now + 1 min` at selection time, where the span is
  `min(minutes * 60_000, MAX_WINDOW_SPAN_MS)` (`../../lib/constants.ts`, 30 days — mirrors the
  backend's `@ValidDateRange(maxDays = 30)` cap). Without that clamp the "30 days" preset would
  resolve to a 30-day-plus-1-minute span and the backend would 400 every request. Like LogsPage,
  this resolution happens once per selection change, not per fetch or per tail tick — the memo
  only re-runs when the `selection` object itself changes, so a long-lived preset session has the
  same frozen-`endTimestamp` staleness characteristic LogsPage has (see
  `../LogsPage/CLAUDE.md`'s "Data flow" section for the full explanation; this page hasn't been
  reconciled to a fetch-time resolution model either). `zoom`, when set, overrides start/end
  inside the `filters` memo, so a zoom changes `filtersKey` and re-keys every query.
- **The stream is hook-local, not TanStack-cached**: `streamRows`/`streamCursor`/`streamHasMore`
  live in `useState`. `resetStream` re-pulls from the top whenever `[filtersKey, view, sort]`
  changes; `loadMore` appends on scroll. Stale-guarded by a `requestSequence` ref so a slow
  in-flight page can't clobber a newer reset, and — because this fetch bypasses TanStack Query's
  own request coalescing — the triggering effect also opens an `AbortController` and aborts it in
  cleanup, so React StrictMode's mount→cleanup→mount on initial load cancels the superseded
  request instead of firing `GET /api/traces` twice for real (`getJson` takes an optional
  `signal` for this). `loadMore` and the live-tail poll don't carry a signal — they're not
  effect-mount-triggered, so they don't double-fire the same way. **Manual reload does NOT refresh the stream** — it
  invalidates `trace-histogram`/`trace-facets`/`trace-table` only; the live tail keeps the stream
  current.
- **Live tail** = `autoRefresh && view === 'stream' && zoom == null && sort === 'new'`. When
  active, the hook polls `fetchTracesCursor({after: newest, limit: 20})` every 1.5 s and
  *prepends* genuinely new rows (deduped by `traceId`), bumps `streamTotal`, and refetches the
  histogram. `tailLocked` is true in Table view, while zoomed, or under any sort but "Newest
  first"; `tailTip` explains why. The toolbar pill and the PageActions auto-refresh toggle are
  the same flag (`autoRefreshDisabled={tailLocked}`).
  - **The sort gate is load-bearing.** The tail cursor is `streamRows[0]`, which is only the
    newest trace under `sort='new'`. Under "Slowest first" the head row is the slowest trace, so
    polling `after=<that row>` would prepend unrelated fast traces above the slowest ones and
    silently break the ordering. Widening the gate means deriving a per-sort tail cursor first.
  - **The tail effect's only dep is `[tail]` and must stay that way.** Everything the tick reads
    goes through a ref (`streamRowsRef`, `filtersRef`, `sortRef`, and `refetchHistogramRef` —
    which exists because TanStack Query returns a new result object on every render). Listing
    `streamRows`/`histogramQuery` would clear and recreate the 1.5 s interval on every render, so
    the tick would never fire while the user is typing in the search box or toggling facets.
- **Preset polling**: separately, the provider runs a 60 s `reloadTraces` interval when
  `autoRefresh && selection.kind === 'preset'` — this refreshes histogram/facets (and table when
  mounted). So Stream view gets 1.5 s tail; Table view gets 60 s refetch.
- **Zoom**: clicking a histogram bar calls `zoomToBucket(bucket)` → sets a local `{t0,t1,label}`
  and force-disables auto-refresh. It never touches the global window context. Changing the window
  clears it two ways: `onSelectionChange` calls `clearZoom()` synchronously (no stale-range flash),
  and a `[startTimestamp, endTimestamp]` effect clears it as a backstop. The `TraceFilterChips`
  zoom chip clears it too.
- **Refs-in-effect**: stable `resetStream`/`loadMore`/tail callbacks read fresh `filters`/`sort`/
  cursor via refs that are synced in a `useEffect` (NOT assigned during render — that form is a
  lint error and was removed). The two `setState`-in-effect spots (reset-on-input, zoom-reset) are
  intentional and carry targeted suppressions.

## Gotchas

- **The Prompt column is legitimately "—" for most rows.** `promptOf(trace)` reads
  `TraceRow.firstUserPrompt` — a real field on both list endpoints now (`TraceSummary` in
  `TraceExplorerService`, batch-filled from the `user_prompt` log correlated to the trace by
  `trace_id`, mirroring `SessionSummaryRow.firstUserPrompt`). It is `null` — italic muted "—" —
  for the two cases the backend can't fill: traces rooted in a tool/model/mcp/compaction span
  have no prompt of their own, and prompt-body capture may have been off when the trace was
  recorded. So an all-"—" column on a tool-heavy window is correct, not a wiring bug.
  `promptOf` needs no widening cast, and `tracesSampleData.ts` synthesizes the field
  (prompt-bearing roots only).
- **Cost is reported, not estimated — and is shared with the Sessions page.** `TraceRow.totalCostUsd` is the
  summed `cost_usd` of the `api_request` logs Claude Code stamped with that trace id (`trace_costs` view,
  `V14`). The Sessions prompt timeline reads the same view for a turn that has a trace, so a prompt's cost and
  its trace's cost are the same number by construction — but only for the one turn the backend bills it to:
  several turns in a row can share a trace (e.g. a bare slash command immediately followed by its real
  prompt), and the backend attributes the trace's cost to the earliest of them only, not to every turn that
  carries the trace id. An earlier revision priced span tokens at published rates instead; it ran 2-3x off and
  disagreed with Sessions, so don't reintroduce a client- or server-side rate table.
- **A "—" in the Cost column is not always "no model calls".** It also means the trace's requests were logged
  without a trace id, which older Claude Code builds did (locally: none in the last eight weeks, ~19% across
  all history). Those requests belong to no trace and are deliberately not back-filled by a time-window guess —
  overlapping traces in one session would each claim the same request and inflate the total (measured at +20%
  on one local session).
- **The "N% cached" chips share the dashboard's one cache-efficiency definition, but not its
  token totals.** `cacheHitRatePercent` delegates to `lib/cacheEfficiency`, so the ratio here,
  the Sessions grid's Cache eff. column, and the Tokens page gauge can't drift apart — change
  the definition in that module and all three move (plus the three backend expressions it
  names). The absolute token counts are a different matter: these come from
  `claude_code.llm_request` span attributes — exact per-request figures, equal to the
  `api_request` logs wherever both exist — while the Sessions/Tokens totals come from cumulative
  counters, and the two disagree by tens of percent on cache-heavy sessions. Comparing the
  *ratio* across pages is valid; comparing the *totals* is not (see backend/CLAUDE.md's
  two-pipelines note).
- **`TraceTableView`'s `COLUMN_COUNT` must match the header cells** — it's the `colSpan` on the
  expanded `TraceSummaryInline` row. Adding or removing a column means bumping it (currently 11)
  and rechecking the table's `minWidth: 1230`.
- **Don't "fix" the context back to props.** The 47-prop view was the problem this solves; see the
  Architecture section. If you split the context value for re-render perf, keep slices stable.
- The context file exports a hook beside its provider, so it trips
  `react-refresh/only-export-components` (a warning, same as `windowContext.tsx`). Accepted.
- The view prop surface is derived: `TracesPageViewProps extends ReturnType<typeof useTracesExplorer>`.
  Add a field to the hook's return and the view/consumers see it without a second edit.
- The body box is `calc(100vh - BODY_CHROME_PX px)` (`BODY_CHROME_PX` = 480, top of
  `TracesPageView.tsx`) — header + histogram + toolbar chrome. Retune that constant if you change
  the chrome height or the page scrolls.
- `VITE_TRACES_SAMPLE=1` swaps all fetchers to an in-memory synthetic store (offline UI work);
  shapes match the live endpoints.
- `traceColors.ts` is also imported by `TraceDetailPage`; it's kept flat (a util, not a component).
```
