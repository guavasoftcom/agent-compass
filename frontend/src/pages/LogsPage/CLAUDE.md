# Logs page

Structured-event log explorer: severity histogram with bar-click zoom, faceted filtering,
full-text search, and a Stream (cursor-paged, live-tailable) or Table (offset-paged) body.
Backend counterpart: `LogsController` → `LogService` (`backend/.../controller/LogsController.java`).

## Files

```
LogsPage/
├── LogsPage.tsx          container — window context, manual reload invalidation
├── LogsPageView.tsx      view + filter state + the two page-level queries (see deviation note)
├── logsApi.ts            the four fetchers; re-exports logsTypes + logsDerivations so
│                         components import everything from './logsApi'
├── logsTypes.ts          DTO types + Severity/FacetKey enums + LogsFilters
├── logsDerivations.ts    buildLogsQuery + severityOf/eventNameOf/toolNameOf row helpers
├── logsSampleData.ts     synthetic store + in-memory query engine (VITE_LOGS_SAMPLE=1)
├── components/
│   ├── LogHistogramChart/   stacked severity bars + legend (pure derivation, no fetch)
│   ├── LogFacetRail/        search box + severity/event/tool checkbox facets (no fetch)
│   ├── LogStream/           infinite scroll-back + live-tail poll (fetches); exports SeverityChip
│   ├── LogTable/            offset-paged DataGrid-style table (fetches)
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

`filtersKey` is a combined stable key: `<filterPartsKey>|<windowKey>` where
`filterPartsKey = JSON.stringify({ severity, event, tool, query })` and
`windowKey` is one of `preset:<minutes>`, `custom:<start>:<end>`, or `zoom:<t0>:<t1>`.

| Component (hook)                        | Query key                                       | Fetcher → endpoint |
|-----------------------------------------|-------------------------------------------------|--------------------|
| `LogsPageView` (`useQuery`)             | `['log-histogram', filterPartsKey, windowKey]`  | `fetchLogHistogram` → `GET /api/logs/histogram?…&buckets=50` |
| `LogsPageView` (`useQuery`)             | `['log-facets', filterPartsKey, windowKey]`     | `fetchLogFacets` → `GET /api/logs/facets?…` |
| `LogStream` (`useInfiniteQuery`)        | `['log-stream', filtersKey]`                    | `fetchLogsCursor` → `GET /api/logs?…&limit=60[&before=ts,id]` |
| `LogStream` (tail `setInterval`, 1.5 s) | writes into `['log-stream', …]` cache           | `fetchLogsCursor` → `GET /api/logs?…&after=ts,id&limit=20` |
| `LogTable` (`useQuery`)                 | `['log-table', filtersKey, page, size]`         | `fetchLogsPage` → `GET /api/logs?…&page=N&size=M` |

`LogHistogramChart` and `LogFacetRail` never fetch — they receive `histogramQuery.data` /
`facetsQuery.data` as props. The attribute-autocomplete endpoints in `api.ts`
(`/api/logs/attributes`, `/attribute-keys`, `/attribute-values`) are not used by this page.

## Data flow and filter semantics

- `LogsPage.tsx` passes `selection` (the global `WindowSelection`) and `windowLabel` down to
  `LogsPageView`. It does NOT resolve timestamps or run a sliding-window interval. Manual
  reload invalidates the four query keys so TanStack Query performs a background refetch;
  the cache data stays on screen during the fetch (no empty-state flash).
- `LogsPageView.tsx` owns the filter state: facet selections, search text, Stream/Table toggle,
  and the local zoom range.
- **Stable window key**: `LogsPageView` builds a `windowKey` string — `preset:<minutes>`,
  `custom:<start>:<end>`, or `zoom:<t0>:<t1>` — that does NOT change between auto-refresh
  ticks. This key is part of every TanStack Query key, so the cache is never invalidated
  by the passage of time alone. All four queries use `['<prefix>', filterPartsKey, windowKey]`
  (or `filtersKey = filterPartsKey + '|' + windowKey` for stream/table).
- **Fetch-time window resolution**: `LogsPageView` defines a plain `resolveFilters()` function
  (not a memo) that calls `resolveWindow(selection)` at the moment it is invoked. This
  function is passed as a prop to `LogStream` and `LogTable`; each component's `queryFn`
  calls it fresh per fetch. Histogram and facet `queryFn`s call it directly. Preset windows
  therefore always resolve to a fresh end timestamp (1 minute ahead of `Date.now()`) without
  bumping a version counter or changing the query key.
- **Auto-refresh**: histogram and facets use `refetchInterval: 60_000` (Stream view +
  preset only, no zoom) and `placeholderData: keepPreviousData` so background refetches
  and page flips repaint in place rather than flashing empty state. The table never
  polls — it refetches on filter/window changes and manual reload only.
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
  to Stream. In Stream view, `LogStream` polls `after=<newest ts,id>` every
  1.5 s and *prepends* results into the first cached page via `setQueryData` (bumping
  `totalCount`), then fires `onTailRows` so the histogram refetches. No query invalidation —
  scroll-back position is preserved. Critically, each tail tick calls `resolveFilters()` fresh
  to get a new `endTimestamp`; this is what keeps the tail alive indefinitely — a frozen
  end timestamp would eventually fall outside the backend's 30-day ValidDateRange cap.
  With stable query keys, scroll-back pages, expanded rows, and flash state persist across
  the whole tail session; they reset only on real filter changes via `prevFiltersKey`.
- The toolbar "events" counter is whichever of Stream/Table is mounted reporting
  `totalCount` up through `onTotalChange`.

## Gotchas

- **Deviation from the container/view convention**: the two page-level `useQuery` calls live
  in `LogsPageView.tsx`, not the container, because they depend on filter state that belongs
  to the view (facets, search, zoom, legend). Push state up before moving the queries.
- Severity on real rows is **derived**, not stored: `severityOf` in `logsDerivations.ts` mirrors
  the SQL function `derive_log_severity` (`V6__log_severity_function.sql`). Change them in lockstep.
- `VITE_LOGS_SAMPLE=1` swaps all fetchers to an in-memory synthetic store (offline UI work);
  shapes are identical to the live endpoints.
- The body box height is `calc(100vh - Npx)`, reserving space for the header + histogram +
  toolbar chrome. Stream and Table view reserve slightly different amounts — `STREAM_BODY_CHROME_PX`
  (430) vs `TABLE_BODY_CHROME_PX` (420), the 10px being Stream's extra facet-rail header row. If
  you change the chrome height, retune those constants (top of `LogsPageView.tsx`) or the page scrolls.
