# Sessions page

Per-session cost and token explorer: four KPI stat cards plus a sortable, server-side-paged
sessions table. Two independent queries feed the page — a lightweight window-summary for the
cards and a paged row query for the table — so paging and re-sorting do not re-run the heavy
percentile aggregation. Each row also carries a truncated first-prompt preview; clicking a row
expands it into a full prompt-timeline panel fetched on demand. Backend counterpart:
`SessionController` (`backend/.../controller/SessionController.java`).

## Files

```
SessionsPage/
├── SessionsPage.tsx        container — window context, three queries (summary, table page, and
│                           the on-demand prompt timeline), sort/pagination/expansion state,
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
        ├── SessionsTable.tsx     PromptCell, PromptCountPill, PromptTimelinePanel, SortArrow leaf
        │                         components and the handleSort toggle + row-expand-click logic
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
│ │ sticky thead: Started · Prompt · Cost · Tokens · Tool calls ·     │   │
│ │       Denials · Active time · $/active min · Terminal · Session   │   │
│ │ tbody rows: PromptCell (+N pill) · DenialChip · TerminalBadge;    │   │
│ │             click a row → inline expansion <tr> with the full     │   │
│ │             prompt timeline (recessed panel, max-height 320px)    │   │
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
| `SessionsPage` (`useQuery`, `sessionPromptsQuery`) | `['session-prompts', expandedSessionId]`, `enabled: expandedSessionId !== null` | `fetchSessionPrompts(sessionId)` → `GET /api/sessions/{sessionId}/prompts` |

`fetchSessions` uses `listWithTotalCount<SessionSummaryRow>` from `api/http.ts`, which reads the
`X-Total-Count` response header and returns `{ items: SessionSummaryRow[], totalCount: number }`.

`fetchSessionsSummary` returns `SessionKpis` (alias `SessionsKpis` on the view), which includes
`totalSessions`, `medianCostUsd`, `p95CostUsd`, `medianCostPerActiveMinuteUsd`, and
`sessionsTrend` (new-session counts per window bucket for the sparkline).

`fetchSessionPrompts` returns `SessionPromptRow[]` (`{ timestamp, prompt, traceId }`, full
untruncated text, ascending by time, max 500 rows) — **not window-scoped**, no query params
beyond the path segment. It only fires while a row is expanded (`enabled` gate) and has no
`refetchInterval` (not polled). It is **not** static, though: past the global 30s `staleTime`
(`main.tsx`), re-expanding the same row triggers a real refetch rather than only serving the
TanStack cache — this matters because live sessions keep gaining prompts, so the timeline for an
in-progress session can legitimately grow between expansions. It also isn't invalidated by
`onReload`/auto-refresh, since those target the summary/table queries, not this one. `prompt` is
null for pre-capture events (prompt_text wasn't recorded) — those rows are kept, not filtered, and
render a placeholder client-side (see the `PromptTimelinePanel` gotcha below). `traceId` is null
for prompts from sessions that predate tracing (~35% of existing data) — the timeline renders no
trace link for those rows, not a disabled placeholder.

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
- **Row expansion (prompt timeline)**: `SessionsPage` holds a single `expandedSessionId: string |
  null` — only one row's prompt timeline is shown at a time, not a per-row `Set`. Clicking a row
  calls `onToggleExpand(sessionId)`, which collapses it if it's already the expanded row,
  otherwise switches the expansion to it (switching rows does **not** require collapsing first).
  `expandedSessionId` is reset to `null` on window-selection change, sort change, and
  page/pageSize change (`handlePaginationModelChange` wraps `setPaginationModel`) — an expanded
  session's row usually won't exist at the same table position after any of those, so the panel
  is dropped defensively rather than pointing at a stale/mismatched row. It is **not** reset by
  `onReload`/auto-refresh — those revalidate the same page and shouldn't collapse the user's open
  panel out from under them.

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
- **`firstUserPrompt` can be `null` while `userPromptCount` is nonzero.** Prompt-body capture can
  be disabled server-side via `OTEL_LOG_USER_PROMPTS`; when it is, the backend still counts
  prompts but never emits the truncated text. `PromptCell` (in `SessionsTable.tsx`) renders the
  em-dash placeholder and the `+N` count pill from one shared wrapper so the pill is gated only on
  `count > 1`, never on `prompt !== null` — a null-check that early-returns before the pill logic
  will silently drop the count for exactly the sessions where it's the only signal left. (This
  was a real regression once — the null branch returned before the pill JSX ran — so keep the
  em-dash and the pill as siblings in one `Box`, not two separate early-return branches.)
- **Per-prompt `prompt` (in the timeline, not the grid's `firstUserPrompt`) can also be `null`** —
  pre-capture events where `prompt_text` wasn't recorded. `PromptTimelinePanel` keeps these rows
  (doesn't filter them out — the session's `userPromptCount` includes them) and renders a dimmed
  italic `"(prompt text not captured)"` placeholder instead of handing `null` to `AttributeValue`,
  which would otherwise stringify it via `formatAttrValue`'s `String(value)` fallback into the
  literal text `"null"`. The "View trace" link still renders for these rows whenever `traceId` is
  present (it's an independent field); the View-more/dialog affordance is simply skipped since
  there's no text to expand.
- **The "+N" pill is `userPromptCount - 1`**, not the raw count — it reads as "and N more prompts
  beyond the one shown," matching the `+N more` idiom used elsewhere (e.g. `LogTableRow`'s
  attribute overflow toggle), not a raw total.
- **Row `<tr>`s are wrapped in a `Fragment`, not rendered as siblings directly under `tbody`**, so
  the optional prompt-timeline `<tr>` can sit right after its owning row. This is the same shape
  `TraceTableView` uses for its inline span-summary expansion. Because of the Fragment, the table
  no longer uses a CSS `tr:nth-of-type(even)` zebra rule (an expanded row's timeline `<tr>` would
  shift the even/odd parity of every row below it) — zebra striping is computed inline per row
  from the `.map` `index` instead (`index % 2 ? alpha(...) : 'transparent'`), matching
  `TraceTableView`'s pattern. If you touch the table's zebra/hover styling, keep it index-driven,
  not `nth-of-type`-driven, and keep hover scoped to `tr.data-row` so it doesn't paint the
  recessed expansion panel.
- **Expansion panel styling**: the panel background is
  `alpha(neutralColors.white, 0.03)` (dark) / `alpha(neutralColors.inkLight, 0.025)` (light) —
  the same recessed-surface tokens `TraceSummaryInlineView` uses — capped at `maxHeight: 320` with
  `overflowY: 'auto'` so a 500-row prompt timeline can't blow out the table's layout.
- **Timeline prompts reuse the `components/AttributeList` "View more" dialog machinery**, the same
  one `LogTable` uses, rather than rendering full prompt text inline: each prompt renders through
  `AttributeValue` (`truncate`, `inlineExpand={false}`) so anything over the shared 200-char
  threshold collapses to a whitespace-collapsed preview + "View more" button; `attrKey` is set to
  that prompt's `formatTimestamp(promptRow.timestamp)` so the dialog title identifies which prompt
  is open. `PromptTimelinePanel` owns its own `expandedValue: ValueDialogState | null` state and
  renders a single `ExpandedValueDialog` — this mirrors exactly how `AttributeList.tsx` wires
  itself, so it stays local to the panel and nothing new threads through the container/view.
  Import `AttributeValue`/`ValueDialogState` from `components/AttributeList/AttributeValue` and
  `ExpandedValueDialog` from `components/AttributeList/ExpandedValueDialog` directly (not the
  barrel, which only re-exports `AttributeList`) — same import shape `LogTable.tsx` uses.
- **Prompts are plain text, not JSON, and that's fine.** `ExpandedValueDialog` runs every value
  through `tryParseJson`, which bails out (returns `undefined`, no repair, no warning banner)
  unless the trimmed text starts with `{` or `[` — an ordinary prompt just renders as the raw
  pre-wrapped string in the dialog body. If a prompt happens to start with `{`/`[` and parses as
  valid (or repairable) JSON, the dialog will pretty-print it as if it were a structured
  attribute — an accepted, harmless quirk of reusing this component for plain text, not a bug to
  special-case around.
- **Per-prompt "View trace" link**: each prompt row's timestamp line renders a compact
  `Box component={RouterLink} to={`/traces/${promptRow.traceId}`}` (react-router `Link`, so
  middle-click/cmd-click open in a new tab) immediately to the right of the muted mono
  timestamp, styled as a small primary-colored text link with an `ArrowForwardIcon` — matching
  the subtle inline-link idiom already used for "+N more" buttons, not the larger filled-chip
  "Open in trace" affordance `LogRowDetail` uses or the gradient "Open full trace" button
  `TraceSummaryInlineView` uses (those are heavier, page-level actions; this is a small trailing
  action inside an already-dense per-prompt row). Rendered **only when `promptRow.traceId` is
  non-null** — no disabled placeholder for the ~35% of prompts that predate tracing. No
  `stopPropagation` needed: the link sits inside the expansion `<tr>`, which has no `onClick` at
  all (only the data row does), so there's no ancestor click handler for a navigation click to
  conflict with.
