# Logs page

Structured-event log explorer: severity histogram with bar-click zoom, faceted filtering,
full-text search, and a Stream (cursor-paged, live-tailable) or Table (offset-paged) body.
Backend counterpart: `LogsController` → `LogService` (`backend/.../controller/LogsController.java`).

## Files

```
LogsPage/
├── LogsPage.tsx          container — resolves the window (`resolveWindow`), manual reload
│                         invalidation, the preset auto-refresh interval
├── LogsPageView.tsx      view + filter state + all page-level queries/fetches (see deviation
│                         note) — histogram/facets/table `useQuery`, plus the stream cursor-page
│                         and live-tail fetches (plain state, not TanStack Query)
├── logsApi.ts            the four fetchers; re-exports logsTypes + logsDerivations so
│                         components import everything from './logsApi'
├── logsTypes.ts          DTO types + Severity/FacetKey enums + LogsFilters
├── logsDerivations.ts    buildLogsQuery + severityOf/eventNameOf/toolNameOf row helpers
├── logsSampleData.ts     synthetic store + in-memory query engine (VITE_LOGS_SAMPLE=1)
├── components/
│   ├── LogHistogramChart/   stacked severity bars + legend (pure derivation, no fetch)
│   ├── LogFacetRail/        search box + severity/event/tool checkbox facets (no fetch)
│   ├── LogStream/           infinite scroll-back + live-tail row rendering (pure presentational
│   │                        leaf — no fetch; `LogsPageView` owns the paging/tail fetches and
│   │                        passes `rows`/`onLoadMore`/etc as props); exports SeverityChip
│   ├── LogTable/            offset-paged table (also a pure presentational leaf — no fetch;
│   │                        `LogsPageView`'s `tableQuery` supplies its rows)
│   └── severity.ts          severity → theme color (single source; histogram/stream/facets share it)
└── index.ts
```

## Visual layout

```
┌─ PageLayout ────────────────────────────────────────────────────────────┐
│ eyebrow/title/subtitle          [PageActions: WindowSelector ⟳ auto]    │
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─ Paper: LogHistogramChart ──────────────────────────────────────────┐ │
│ │ stacked bars per severity · legend chips toggle page-level filter · │ │
│ │ clicking a bar zooms the page window into that bucket (30-min floor) │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│ [Stream|Table toggle] [● Live tail]            [ 1,234 events counter ] │
│ ┌─ fixed-height body (calc(100vh - 430px)) ───────────────────────────┐ │
│ │ [zoom chip] [filter chips…] [Clear all]   ← only when filters active│ │
│ │                                                                     │ │
│ │ Stream view:                          Table view:                   │ │
│ │ ┌ LogFacetRail ┐ ┌ LogStream ───────┐ ┌ LogTable ─────────────────┐ │ │
│ │ │ search box   │ │ expandable rows  │ │ paged rows; detail panel  │ │ │
│ │ │ ☐ Severity   │ │ (AttributeList   │ │ uses AttributeList +      │ │ │
│ │ │ ☐ Event type │ │  when expanded)  │ │ ExpandedValueDialog       │ │ │
│ │ │ ☐ Tool/server│ │ "Load more" ↓    │ │ page-size 25/50/100       │ │ │
│ │ └──── 236px ───┘ └──────────────────┘ └───────────────────────────┘ │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

All fetchers live in `logsApi.ts` (not `api.ts`) and share `buildLogsQuery(filters)` for the
query string.

`filters` (a `LogsFilters`) bundles the resolved `startTimestamp`/`endTimestamp` (or the local
zoom range, if set) together with the facet selections, search text, and hidden-severity set;
`filtersKey = JSON.stringify(filters)` is the single combined cache key — there is no separate
`windowKey`/`filterPartsKey` split. Because `startTimestamp`/`endTimestamp` only change when
`resolveWindow` re-resolves (see "Data flow" below), `filtersKey` stays stable between
auto-refresh ticks the same way a hand-built window key would.

| Component / hook                                | Query key                                | Fetcher → endpoint |
|---------------------------------------------------|-------------------------------------------|--------------------|
| `LogsPageView` (`useQuery`, `histogramQuery`)      | `['log-histogram', filtersKey]`           | `fetchLogHistogram(facetFilters, 50)` → `GET /api/logs/histogram?…&buckets=50` |
| `LogsPageView` (`useQuery`, `facetsQuery`)         | `['log-facets', filtersKey]`               | `fetchLogFacets(facetFilters)` → `GET /api/logs/facets?…` |
| `LogsPageView` (`useQuery`, `tableQuery`)          | `['log-table', filtersKey, page, pageSize]` | `fetchLogsPage(filters, page, pageSize)` → `GET /api/logs?…&page=N&size=M` (`enabled: view === 'table'`) |
| `LogsPageView` (`resetStream`/`loadMore`, plain `useState`, NOT a TanStack query) | — | `fetchLogsCursor(filters, { cursor, limit: 60 })` → `GET /api/logs?…&limit=60[&before=ts,id]` |
| `LogsPageView` (tail `setInterval`, 1.5 s, plain state prepend) | — | `fetchLogsCursor(filters, { after, limit: 20 })` → `GET /api/logs?…&after=ts,id&limit=20` |

`histogramQuery`/`facetsQuery` call `fetchLogHistogram`/`fetchLogFacets` with `facetFilters`
(`filters` with `hiddenSeverity` forced to `[]`, since the legend mute is client-side only), but
their query *key* is still `filtersKey` (built from `filters`, not `facetFilters`) — toggling a
legend/facet severity therefore still busts and refetches both, even though the response
wouldn't have changed. The stream (`resetStream`/`loadMore`) and the live tail are plain
`useState`/`useCallback`/`useEffect` in `LogsPageView`, not `useQuery`/`useInfiniteQuery` — there
is no `['log-stream', …]` TanStack cache entry; `LogStream` (the component) is a pure
presentational leaf that receives `rows`/`total`/`loading`/`hasMore` as props and calls
`onLoadMore()`.

`LogHistogramChart` and `LogFacetRail` never fetch — they receive `histogramQuery.data` /
`facetsQuery.data` as props. The attribute-autocomplete endpoints in `api.ts`
(`/api/logs/attributes`, `/attribute-keys`, `/attribute-values`) are not used by this page.

## Data flow and filter semantics

- `LogsPage.tsx` resolves the global `WindowSelection` via the shared
  `resolveWindow(selection)` (`../../lib/resolveWindow.ts`, also used by TracesPage) inside a
  `useMemo` keyed on `[selection]`, and passes the resulting `startTimestamp`/`endTimestamp`/
  `windowLabel` down to `LogsPageView` as props. **This resolution happens once per selection
  change, not per fetch or per tail tick**: the memo only re-runs when the `selection` object
  itself changes (a new preset/custom range picked), so for a preset the resolved
  `endTimestamp` is frozen at "`Date.now()` + 1 minute" from the moment the selection was set,
  not the moment of each request. Manual reload invalidates the four query keys so TanStack
  Query performs a background refetch of that same frozen window; the cache data stays on
  screen during the fetch (no empty-state flash).
  - **Known staleness limitation**: because the resolved end timestamp doesn't advance on its
    own, a long-lived preset session (auto-refresh left on for a long time without touching the
    window control) re-fetches the *same* `[start, end]` range rather than sliding it forward —
    events that arrive after the frozen `endTimestamp` won't appear until the user reselects a
    window (which recomputes `resolved`). Live tail (below) works around this for the Stream
    view specifically by fetching with `after=<cursor>` rather than relying on `endTimestamp`
    to bound "new" rows. See the deferred TODO to reconcile this with TracesPage's context
    pattern (tracked outside this file).
  - **30-day preset clamp**: `resolveWindow` clamps a preset's span to `MAX_WINDOW_SPAN_MS`
    (`../../lib/constants.ts`, 30 days) before computing `startTimestamp`, so the "30 days"
    preset's `[start, end]` span is exactly 30 days even with the `+1` minute end lookahead —
    never 30 days + 1 minute. Without this clamp the backend's `@ValidDateRange(maxDays = 30)`
    rejects every request for that preset with a 400 and the page renders blank.
- `LogsPageView.tsx` owns the filter state: facet selections, search text, Stream/Table toggle,
  and the local zoom range.
- **Stable query key**: because `startTimestamp`/`endTimestamp` come from `LogsPage.tsx`'s
  memoized `resolved` (not recomputed on every render), `filtersKey = JSON.stringify(filters)`
  does NOT change between auto-refresh ticks — only when the selection, zoom, facets, search,
  or hidden-severity set actually change. So the cache is never invalidated by the passage of
  time alone; `['log-histogram', filtersKey]` / `['log-facets', filtersKey]` /
  `['log-table', filtersKey, page, pageSize]` are the three TanStack keys (see the API table
  above — the stream/tail fetches aren't TanStack-cached at all).
- **Auto-refresh**: there is no `refetchInterval` on `histogramQuery`/`facetsQuery`/`tableQuery`.
  Instead, `LogsPage.tsx` (the container) runs a `window.setInterval(reloadLogs, 60_000)` while
  `autoRefresh && selection.kind === 'preset'`; `reloadLogs` calls
  `queryClient.invalidateQueries` for `['log-histogram']`, `['log-facets']`, and `['log-table']`
  by key prefix, which triggers a background refetch of whatever `filtersKey` is currently
  active. Cached data stays on screen during that refetch (TanStack's default behavior — no
  `placeholderData` override is needed or present). The table also refetches on every
  filter/window/page change; it is not otherwise excluded from the 60 s interval, since the
  invalidate call above hits `['log-table', …]` regardless of `view`.
- **Unified severity selection**: the histogram legend chips and the facet rail severity
  checkboxes are two surfaces over a single `sel.severity` set in `LogsPageView`. Clicking
  either calls `toggleFacet('severity', s)` — there is no separate mute state.
- **Histogram severity**: the histogram never sends `severity` to the server — all four series
  are always present in the response. Client-side, `LogsPageView` derives a `hiddenSeverities`
  set as the complement of `sel.severity` (empty when no severities are selected, meaning all
  series are visible) and passes it to `LogHistogramChart` as `hidden`. Unselected severities
  appear dimmed.
- **Facet counts stay stable**: the `count('severity', …)` call in the sample engine (and the
  server's `/api/logs/facets` endpoint) excludes the severity filter for the severity facet
  itself — so the rail shows total counts per severity regardless of which severities are
  currently selected. Both histogram and facets receive the same `filters` object (severity
  selection included); the histogram strips `severity` from its URL params internally.
- **stream/table** apply every filter from `filters`, including the severity selection.
- **Zoom**: clicking a histogram bar sets a local `{t0, t1}` window (chip in the filter band).
  It never touches the global window context, and it force-disables live tail (a fixed past
  slice can't tail). Changing the global window selection clears the zoom; the zoom-clear
  render guard keys on the selection-derived `selectionKey` (not on resolved timestamps), so
  it only fires on real window changes and not on auto-refresh ticks.
  - **30-minute span floor**: `MINIMUM_ZOOM_SPAN_MS = 30 * 60_000` is declared in
    `LogsPageView` (the only file that uses it). The histogram always requests
    `HISTOGRAM_DEFAULT_TARGET` (50) buckets regardless of zoom depth.
  - **Click-gating**: `onBarClick` is `undefined` when the current window span is at or
    below 30 minutes. Current span is: the active zoom span if zoomed (which by
    construction never falls below the floor), `minutes * 60_000` for preset selections,
    or `end - start` for custom ranges. Concretely: 5/15/30-minute presets are
    non-clickable; 1-hour and longer presets are clickable. When the zoom itself reaches
    30 minutes the bars become non-clickable and zoom bottoms out there.
  - **Centering and sliding**: if the clicked bucket's span is already ≥ 30 minutes, the
    zoom range is exactly the bucket range. Otherwise a 30-minute window is centered on the
    bucket's midpoint, then slid (without shrinking) to fit inside the containing window
    (active zoom range, or `resolveWindow(selection)` called at click time). The containing
    window is always wider than 30 minutes at the moment of the click (enforced by the
    gate above), so the slide always has room.
- **Live tail** = the global auto-refresh flag, and is **Stream-only**: in Table view both
  the pill and the top-right PageActions auto-refresh toggle are disabled — the pill's
  tooltip explains why (the offset-paged table can't tail) — and nothing on
  the page polls; the flag's state is preserved and polling resumes when switching back
  to Stream. In Stream view, a plain `useEffect` in `LogsPageView` (not `LogStream` itself —
  that component is a presentational leaf, see the API table above) runs a
  `window.setInterval` every `TAIL_INTERVAL_MS` (1.5 s, `../../lib/constants.ts`) while
  `autoRefresh && view === 'stream'`. Each tick reads `filtersRef.current` (a ref kept in sync
  with `filters` via an effect, so async callbacks see fresh values without re-binding) and
  calls `fetchLogsCursor(filtersRef.current, { after: newestRef.current, limit: 20 })`
  directly — **not** through TanStack Query — then prepends any new rows into the
  `loaded` `useState` array, bumps `streamTotal`, and calls `histogramQuery.refetch()`.
  - **`endTimestamp` is NOT refreshed per tick.** `filtersRef.current.endTimestamp` is whatever
    `LogsPage.tsx`'s `resolveWindow` memo last produced for the current selection (see "Data
    flow" above) — the tail does not call `resolveWindow` itself. The tail still surfaces
    genuinely new rows because it filters by `after=<newest ts,id>`, a cursor comparison, not by
    re-deriving `endTimestamp`; but a sufficiently long-lived preset session (endTimestamp frozen
    at selection time + 1 minute) will eventually have its frozen `endTimestamp` fall behind
    `Date.now()`, and `fetchLogsCursor` still bounds results to `[startTimestamp, endTimestamp]`
    server-side — so very old sessions can silently stop tailing new rows even though the poll
    keeps running. This is the same staleness limitation noted in "Data flow" above; there is no
    per-tick re-resolution in the current code.
  - Scroll-back pages, expanded rows, and flash state persist across the whole tail session
    (the `loaded` array is only reset by the `[filtersKey, view]` effect on a real filter/window
    change) — the tail prepend is a targeted `setLoaded((prev) => [...res.items, ...prev])`, not
    a full re-fetch.
- The toolbar "events" counter is whichever of Stream/Table is mounted reporting
  `totalCount` up through `onTotalChange`.

## Gotchas

- **`eventNameOf`/`toolNameOf` runtime-guard `event.name`/`tool_name`.** Telemetry attributes
  are attacker-influenceable (unauthenticated OTLP ingest) and the backend preserves OTLP
  kvlist/array structure into jsonb, so these attributes can arrive in the browser as an
  object or array instead of a string. `logsDerivations.ts` checks `typeof value === 'string'`
  before returning (returning `null` otherwise) rather than doing a compile-time-only
  `as string` cast — a cast would let a poisoned value reach `<Tag>{event}</Tag>` in
  `LogStream` and crash the render (React throws "Objects are not valid as a React child").
  `TraceDetailPage/components/SpanDetailDock/LogEntry.tsx` uses the same guard for the
  analogous `event.name`/`tool` attributes on the trace-detail dock — keep both in lockstep
  if you add another attribute-derived string helper anywhere in the app. The app-level
  `ErrorBoundary` (`../../components/ErrorBoundary`, wired around `<Outlet />` in
  `App/AppShell.tsx`) is defense-in-depth for this same class of bug, not a substitute for
  the guard.
- **Deviation from the container/view convention**: the three page-level `useQuery` calls
  (histogram, facets, table) — plus the stream cursor-paging and live-tail fetches, which are
  plain `useState`/`useEffect`, not `useQuery` — live in `LogsPageView.tsx`, not the container,
  because they depend on filter state that belongs to the view (facets, search, zoom, legend).
  Push state up before moving the queries.
- Severity on real rows is **derived**, not stored: `severityOf` in `logsDerivations.ts` mirrors
  the SQL function `derive_log_severity` (`V6__log_severity_function.sql`). Change them in lockstep.
- `VITE_LOGS_SAMPLE=1` swaps all fetchers to an in-memory synthetic store (offline UI work);
  shapes are identical to the live endpoints.
- The body box height is `calc(100vh - Npx)`, reserving space for the header + histogram +
  toolbar chrome. Stream and Table view reserve slightly different amounts — `STREAM_BODY_CHROME_PX`
  (430) vs `TABLE_BODY_CHROME_PX` (420), the 10px being Stream's extra facet-rail header row. If
  you change the chrome height, retune those constants (top of `LogsPageView.tsx`) or the page scrolls.
