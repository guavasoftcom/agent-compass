# Sessions page

Per-session cost and token explorer: four KPI stat cards plus a sortable, server-side-paged
sessions table. Two independent queries feed the page — a lightweight window-summary for the
cards and a paged row query for the table — so paging and re-sorting do not re-run the heavy
percentile aggregation. Backend counterpart: `SessionController`
(`backend/.../controller/SessionController.java`).

## Files

```
SessionsPage/
├── SessionsPage.tsx        container — window context, two queries, sort/pagination state,
│                           reload and selection-change handlers
├── SessionsPageView.tsx    view — composes SessionsKpiStrip, SessionsTable, and the shared
│                           TablePager; exports SessionsKpis, PaginationModel, SessionsPageViewProps
├── index.ts                re-exports container default
└── components/
    ├── sessionsFormat.ts       shared formatters: USD_FORMATTER, USD_PER_MINUTE_FORMATTER,
    │                           formatDuration (seconds), formatTokens, formatTimestamp
    ├── SessionsKpiStrip/       4-card StatCard grid; renders the shared LineSparkline
    │   ├── SessionsKpiStrip.tsx  (components/LineSparkline) and the P95 caption math
    │   └── index.ts
    └── SessionsTable/          hand-built sortable table; contains DenialChip, TerminalBadge,
        ├── SessionsTable.tsx     SortArrow leaf components and the handleSort toggle logic
        └── index.ts
```

The footer pager is the shared `components/TablePager` (also used by Logs and Traces), not a
page-local component.

The page follows the standard container/presentational split from
[frontend/CLAUDE.md](../../../CLAUDE.md). All data fetching and state live in the container;
the view takes typed props and contains no `useQuery` or context reads.

## Visual layout

```
┌─ PageLayout ────────────────────────────────────────────────────────────┐
│ eyebrow: Activity / title: Sessions / subtitle                          │
│                              [PageActions: WindowSelector ⟳ auto]      │
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─ SessionsKpiStrip: 4-col StatCard grid ───────────────────────────┐   │
│ │ Total sessions    │ Median cost/session │ P95 cost   │ Median $/  │   │
│ │ + sparkline       │ (accent)            │ + tooltip  │ active min │   │
│ └───────────────────────────────────────────────────────────────────┘   │
│ ┌─ Paper: sessions table (calc(100vh - 320px), min 420px) ──────────┐   │
│ │ <SessionsTable>                                                    │   │
│ │ sticky thead: Started · Cost · Tokens · Tool calls · Denials ·    │   │
│ │               Active time · $/active min · Terminal · Session     │   │
│ │ tbody rows: DenialChip · TerminalBadge                            │   │
│ │ ── <TablePager> (shared) ───────────────────────────────────────── │   │
│ │ Rows per page [25 | 50 | 100]      N–M of total   [◀] [▶]         │   │
│ └───────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

Fetchers live in the shared `api/endpoints.ts` (not a page-local module) and use
`windowQueryParams(selection)` to produce the query string.

| Source                       | Query key                                                              | Fetcher → endpoint |
|------------------------------|------------------------------------------------------------------------|--------------------|
| `SessionsPage` (`useQuery`)  | `['sessions-summary', selectionKey]`                                   | `fetchSessionsSummary(selection)` → `GET /api/sessions/summary?…` |
| `SessionsPage` (`useQuery`)  | `['sessions', selectionKey, page, pageSize, sortField, sortDirection]` | `fetchSessions(selection, { page, pageSize, sort })` → `GET /api/sessions?…&page=N&size=M&sort=field&direction=asc\|desc` |

`fetchSessions` uses `listWithTotalCount<SessionSummaryRow>` from `api/http.ts`, which reads the
`X-Total-Count` response header and returns `{ items: SessionSummaryRow[], totalCount: number }`.

`fetchSessionsSummary` returns `SessionKpis` (alias `SessionsKpis` on the view), which includes
`totalSessions`, `medianCostUsd`, `p95CostUsd`, `medianCostPerActiveMinuteUsd`, and
`sessionsTrend` (new-session counts per window bucket for the sparkline).

## Data flow and semantics

- **Two-query design**: `summaryQuery` is keyed on the window only (`['sessions-summary',
  selectionKey]`). Because it excludes `page`/`sort` from its key, it is fetched once per window
  change and reused while the user pages and re-sorts the table. `sessionsQuery` is re-keyed on
  every `page`, `pageSize`, `sortField`, and `sortDirection` change, triggering a new server-side
  sorted/offset page.
- **`rowCount` fallback**: the container passes `rowCount` to the view as
  `sessionsQuery.data?.totalCount ?? summaryQuery.data?.totalSessions ?? 0`. This means the pager
  total is available immediately from the summary even before the first table page resolves.
- **`keepPreviousData`**: `sessionsQuery` uses `placeholderData: keepPreviousData` so the table
  repaints in place during page flips rather than going blank.
- **Sort**: `DEFAULT_SORT = { field: 'costUsd', direction: 'desc' }`. When the user clicks a
  sortable header, `SessionsTable`'s `handleSort` toggles direction on the active field or
  defaults to `'desc'` for a new field, then calls `onSortModelChange`. The container resets
  the page to 0 and updates `sortModel`. Sortable fields are: `startTimestamp`, `costUsd`,
  `tokens`, `activeTimeSeconds`, `costPerActiveMinuteUsd`. Non-sortable: `toolCallCount`,
  `denialCount`, `terminalType`, `sessionId`.
- **Pagination**: offset-based, zero-indexed (`page: 0`). `DEFAULT_PAGE_SIZE = 25`. The pager
  footer uses a `SegmentedToggle` for rows-per-page (25/50/100) and prev/next chevron buttons.
  Changing page size resets to page 0. `onSelectionChange` and `onReload` both reset the page to 0
  so the user never lands on a non-existent page after a window change.
- **Polling**: `refetchInterval = autoRefresh && selection.kind === 'preset' ? 60_000 : false`.
  Both queries share this interval. Custom ranges have a fixed end; polling them is suppressed.
  `isPolling` is true only when `autoRefresh`, `selection.kind === 'preset'`, and either query is
  actively fetching.
- **`burn` ($/active min per row)**: computed client-side in `SessionsTable` as
  `(row.costUsd / row.activeTimeSeconds) * 60`; displayed as `—` when `activeTimeSeconds` is 0.
  The summary card's `medianCostPerActiveMinuteUsd` is the backend's percentile across all
  sessions, not the median of the per-row burns.
- **`sessionsTrend` sparkline**: the shared `LineSparkline` (`components/LineSparkline`) renders
  if `values.length >= 2`. It draws a filled area + stroke line over the bucket counts returned by
  the summary endpoint. Renders nothing for a single-bucket window (the `< 2` guard lives in
  `LineSparkline`, shared with `MetricKpiStrip`).

## Gotchas

- **`SessionKpis` vs `SessionsKpis`**: the backend DTO type in `api/types.ts` is `SessionKpis`;
  the view re-names it `SessionsKpis` in its own exported interface. They share the same shape —
  the container passes `summaryQuery.data ?? EMPTY_KPIS` directly (no conversion needed because
  the shapes are identical fields).
- **`tokens` is reset-aware**: `SessionSummaryRow.tokens` is a backend-aggregated reset-aware
  total (MAX per stream then SUM across streams), not a plain SUM. The view formats it with
  `formatTokens` from `components/sessionsFormat.ts` (M/K compact) rather than the global
  formatter used on other pages.
- **Table box height** is `calc(100vh - BODY_CHROME_PX px)` (`BODY_CHROME_PX` = 320, top of
  `SessionsPageView.tsx`) with `minHeight: 420`. If you add or remove chrome above the table card,
  retune that constant or the table will over/under-fill the viewport.
- **`DenialChip` threshold**: 0 → dimmed text, 1–3 → amber, 4+ → red. Colors come from
  `theme.palette.warning.main` / `theme.palette.error.main` — never hard-coded hex.
- **`startType` field** is present on `SessionSummaryRow` but not rendered in the table. Resume
  sessions (heartbeat hosts) are included in the row data; the backend aggregation is responsible
  for not double-counting them in KPIs. Don't filter them out client-side unless the backend
  contract changes.
- **No page-local API module**: unlike `LogsPage` and `TracesPage`, the session fetchers live in
  the shared `api/endpoints.ts`. Import them from `'../../api'`, not a local file.
- **`sessionsFormat.ts` is a plain module**, not a component — it lives directly in `components/`
  without its own subfolder and has no `index.ts`. Import it with a direct path:
  `'../sessionsFormat'` (from inside a component subfolder) or `'./components/sessionsFormat'`
  (from the view).
- **Circular-import note**: `SessionsKpiStrip` imports the `SessionsKpis` type from
  `../../SessionsPageView`. This is intentional — the view is the canonical owner of those prop
  interfaces. The import direction is always view → components (for rendering) and components →
  view (for types only); there is no runtime cycle because TypeScript erases type-only imports.
  (The shared `TablePager` takes only primitive props — `page`/`pageSize`/`rowCount` — so it does
  not import any view types.)
