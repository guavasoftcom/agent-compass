# Sessions page

Per-session cost and token explorer: four KPI stat cards plus a sortable, server-side-paged
sessions table. Two independent queries feed the page — a lightweight window-summary for the
cards and a paged row query for the table — so paging and re-sorting do not re-run the heavy
percentile aggregation. Each row also carries a truncated first-prompt preview; clicking a row
opens a right-side detail drawer holding that session's Aurora-styled prompt timeline (per-turn
model/cost/tokens/tool chips) fetched on demand. Backend counterpart: `SessionController`
(`backend/.../controller/SessionController.java`); full response-shape contract in
[SESSIONS-BACKEND.md](SESSIONS-BACKEND.md).

## Files

```
SessionsPage/
├── SessionsPage.tsx        container — window context, three queries (summary, table page, and
│                           the on-demand prompt timeline), sort/pagination/open-drawer state
│                           (seeded from the ?sessionId= deep link), reload and
│                           selection-change handlers
├── SessionsPageView.tsx    view — composes SessionsKpiStrip, SessionsTable, the shared
│                           TablePager, and SessionDetailDrawer; resolves openSessionId to the
│                           row object the drawer header needs; derives windowStartMs/windowEndMs
│                           (lib/resolveWindow) for the timeline's out-of-window dimming; exports
│                           SessionsKpis, PaginationModel, SessionsPageViewProps
├── index.ts                re-exports container default
└── components/
    ├── sessionsFormat.ts        shared formatters: USD_PER_MINUTE_FORMATTER, formatDuration
    │                            (seconds), formatTokens (K/M compact — see the boundary-rounding
    │                            gotcha below), and formatShortTimestamp ("Aug 9,
    │                            10:36 AM" — the drawer header's metadata line, where the full
    │                            locale string is too long). USD_FORMATTER, formatTimestamp, and
    │                            formatRelativeTime (Last-activity column's "Nm/Nh/Nd ago") are
    │                            re-exported from here but defined in lib/format.ts — the Tokens
    │                            page's cache-efficiency rank card and detail dialog need the
    │                            identical formatters. Cache efficiency deliberately
    │                            does NOT live here either — the Tokens page needs the same ratio,
    │                            so it lives in lib/cacheEfficiency.ts
    ├── SessionsKpiStrip/        4-card StatCard grid; renders the shared LineSparkline
    │   ├── SessionsKpiStrip.tsx   (components/LineSparkline) and the P95 caption math
    │   └── index.ts
    ├── SessionsTable/           hand-built sortable table; contains DenialChip,
    │   ├── SessionsTable.tsx      PromptCell, PromptCountPill, SortArrow leaf components, the
    │   │                          Last-activity relative-time cell (Tooltip shows the absolute
    │   │                          timestamp), the Tokens-cell TokenBreakdownTooltip hover, and
    │   │                          the handleSort toggle + row-click logic. It knows only
    │   │                          openSessionId (for the row highlight) — the drawer itself is
    │   │                          the view's child, not the table's
    │   └── index.ts
    ├── SessionDetailDrawer/     right-side MUI Drawer (560px, max 92vw) over a scrim, opened by
    │   ├── SessionDetailDrawer.tsx  a row click: header names the session (id, start,
    │   │                          whole-session cost, "active Nh ago", token total + cache-eff.
    │   │                          badge, close ×) and the body is the scroll container for
    │   │                          PromptTimelinePanel. Closes on ×, backdrop, or Escape (all
    │   │                          three are MUI's onClose). Keeps the last session rendered
    │   │                          through the slide-out (guarded render-phase setState compared
    │   │                          by session id, dropped on the transition's onExited) and
    │   │                          auto-scrolls to the most recent turn on open
    │   └── index.ts
    └── PromptTimelinePanel/     Aurora glass per-turn timeline rendered inside the drawer —
        ├── PromptTimelinePanel.tsx  genuinely new UI (not an extraction): a gradient rail with a
        │                          card per turn (timestamp, model chip, per-turn cost, TokenUsage
        │                          w/ breakdown tooltip, tool-call chips, optional "View trace"
        │                          link); header shows the prompt count (session identity lives in
        │                          the drawer header); dims turns outside the active window with a boundary
        │                          divider; each prompt renders through the shared
        │                          components/PromptSummaryText (a subagent-notification turn
        │                          summarizes instead of showing raw XML), whose ordinary-prompt
        │                          case falls through to the shared AttributeValue/
        │                          ExpandedValueDialog truncate-and-expand machinery. Exports
        │                          TokenBreakdownTitle / TokenBreakdownTooltip / TokenUsage,
        │                          reused by SessionsTable's Tokens-column hover, and CostValue
        │                          (cost-outlier tiering), reused by SessionsTable's Cost column and
        │                          SessionDetailDrawer's header. Also renders
        │                          TurnAttributionMarker — a muted "approx" marker on turns whose
        │                          figures were bucketed from cumulative counters; renders nothing
        │                          on exact (api_request-derived) turns.
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
│ │ sticky thead: Started · Last activity · Prompt · Cost · Tokens ·  │   │
│ │       Cache eff. · Tool calls · Denials · Active time · $/active   │   │
│ │       min (Last activity is the default sort)                      │   │
│ │ tbody rows: PromptCell (+N pill) · DenialChip;                     │   │
│ │             Tokens cell hover → TokenBreakdownTooltip; click a    │   │
│ │             row → SessionDetailDrawer (row stays highlighted)     │   │
│ │ ── <TablePager> (shared) ───────────────────────────────────────── │   │
│ │ Rows per page [25 | 50 | 100]      N–M of total   [◀] [▶]         │   │
│ └───────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

The detail drawer slides in over the right edge of the viewport (scrim behind it, table
untouched underneath):

```
                              ┌─ SessionDetailDrawer (560px, max 92vw) ───┐
                              │ Session 690cb902-…-9ddc290d9200      [✕] │
                              │ Aug 9, 8:26 PM · $4.38 · active 1h ago ·  │
                              │ 5.3M tok · 98% cache                      │
                              ├───────────────────────────────────────────┤
                              │ ⏱ PROMPT TIMELINE  12 prompts             │
                              │ ● Aug 9, 8:26:55 PM ·Opus· $0.41 · 240K   │
                              │ │  apply the design handoff…  [View trace]│
                              │ │  [Bash 25] [Edit 24] [Read 8]           │
                              │ ● …                                       │
                              │ ── selected window starts ──              │
                              │ ● (scrolls; opens at the newest turn)     │
                              └───────────────────────────────────────────┘
```

## Who calls which API

Fetchers live in the shared `api/endpoints.ts` (not a page-local module) and use
`windowQueryParams(selection)` to produce the query string.

| Source                       | Query key                                                              | Fetcher → endpoint |
|------------------------------|------------------------------------------------------------------------|--------------------|
| `SessionsPage` (`useQuery`)  | `['sessions-summary', selectionKey]`                                   | `fetchSessionsSummary(selection)` → `GET /api/sessions/summary?…` |
| `SessionsPage` (`useQuery`)  | `['sessions', selectionKey, page, pageSize, sortField, sortDirection]` | `fetchSessions(selection, { page, pageSize, sort })` → `GET /api/sessions?…&page=N&size=M&sort=field&direction=asc\|desc` |
| `SessionsPage` (`useQuery`, `sessionPromptsQuery`) | `['session-prompts', openSessionId]`, `enabled: openSessionId !== null && rows.some((row) => row.sessionId === openSessionId)` | `fetchSessionPrompts(sessionId)` → `GET /api/sessions/{sessionId}/prompts` |

`fetchSessions` uses `listWithTotalCount<SessionSummaryRow>` from `api/http.ts`, which reads the
`X-Total-Count` response header and returns `{ items: SessionSummaryRow[], totalCount: number }`.

`fetchSessionsSummary` returns `SessionKpis` (alias `SessionsKpis` on the view), which includes
`totalSessions`, `medianCostUsd`, `p95CostUsd`, `medianCostPerActiveMinuteUsd`, and
`sessionsTrend` (new-session counts per window bucket for the sparkline).

`fetchSessionPrompts` returns `SessionPromptRow[]` — full untruncated text, ascending by time,
max 500 rows, **not window-scoped**, no query params beyond the path segment. The base three
fields are `{ timestamp, prompt, traceId }`; four more are additive/optional and drive the
timeline's richer per-turn cards — `model`, `costUsd`, `tokens` (a `SessionTokenBreakdown`), and
`tools` (`{ name, count }[]`) — see [SESSIONS-BACKEND.md](SESSIONS-BACKEND.md) for the exact
per-field semantics. `SessionPromptRow` and `SessionTokenBreakdown` are the single canonical
types (in `api/types.ts`) — `PromptTimelinePanel` imports them directly rather than declaring its
own widening copies, so there is no cast anywhere in the data path from `fetchSessionPrompts` to
the panel. It only fires while a session's drawer is open (`enabled` gate) and has no
`refetchInterval` (not polled). It is **not** static, though: past the global 30s `staleTime`
(`main.tsx`), re-opening the same session triggers a real refetch rather than only serving the
TanStack cache — this matters because live sessions keep gaining prompts, so the timeline for an
in-progress session can legitimately grow between openings. It also isn't invalidated by
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
- **Sort**: `DEFAULT_SORT = { field: 'endTimestamp', direction: 'desc' }` — sessions land sorted
  by most-recent activity (the Last-activity column). Recency is the operational default; cost
  remains one click away on its own sortable column. When the user clicks a sortable header,
  `SessionsTable`'s `handleSort` toggles direction on the active field or defaults to `'desc'`
  for a new field, then calls `onSortModelChange`. The container resets the page to 0 and updates
  `sortModel`. Sortable fields are: `startTimestamp`, `endTimestamp`, `costUsd`, `tokens`,
  `cacheEfficiency`, `activeTimeSeconds`, `costPerActiveMinuteUsd`. Non-sortable:
  `toolCallCount`, `denialCount`, `firstUserPrompt`. The `terminalType` and `sessionId`
  columns were dropped in the Aurora sessions/traces sync — both fields are still on
  `SessionSummaryRow`, and `sessionId` now shows in the detail drawer's header instead of its
  own column.
- **Pagination**: offset-based, zero-indexed (`page: 0`). `DEFAULT_PAGE_SIZE = PAGE_SIZE_OPTIONS[0]`
  (`lib/constants`, currently 25). The pager footer uses a `SegmentedToggle` for rows-per-page
  (25/50/100) and prev/next chevron buttons. Changing page size resets to page 0.
  `onSelectionChange` and `onReload` both reset the page to 0 so the user never lands on a
  non-existent page after a window change.
- **Row cost, active time, Started and Last activity are whole-session, not window-scoped.** The
  window decides *which* sessions the table lists; `costUsd`, `activeTimeSeconds`,
  `startTimestamp` and `endTimestamp` then describe the session's entire lifetime, so a session
  that began before the window shows its full spend and its real start instead of the sliver
  inside the range. `tokens`/`tokenBreakdown` (and therefore the Cache eff. column),
  `toolCallCount`, `denialCount` and `userPromptCount` are still window-scoped, as are all four
  KPI cards — so the **Median cost/session card will not equal the median of the visible Cost
  column**. Backend seam: `MetricPointRepository.aggregateSessionSummaries` builds a
  window-bounded `session_window` CTE and joins `cost_per_session` / `active_per_session` /
  `session_span` back to that id set with no timestamp predicate.
- **A session is listed only if it posted a counter *increment* in the window.** Claude Code's
  exporter never retires a metric stream: a finished session keeps re-emitting its cumulative
  cost/active-time counters every minute forever at an unchanged value (98.9% of all such points
  on live data). Membership therefore tests `value_delta`, not mere presence. Before that filter
  existed the grid listed every session ever recorded, each showing `$0.00`, each claiming to
  have started at the window edge and to have been active seconds ago — which under the default
  `endTimestamp desc` sort pushed the one session actually being worked on off the top of the
  table. If sessions you know are finished reappear here, that filter is what regressed. A live
  session that sits idle longer than the whole window legitimately drops off the list: "active in
  this window" is the definition, and it is the same one the KPI cards use.
- **`burn` ($/active min per row)**: computed client-side in `SessionsTable` as
  `(row.costUsd / row.activeTimeSeconds) * 60`; displayed as `—` when `activeTimeSeconds` is 0.
  Cost and active time are deliberately whole-session *together* (see the bullet above) — if you
  ever re-window one of them, re-window the other or this cell silently divides a full-session
  numerator by a partial denominator. The summary card's `medianCostPerActiveMinuteUsd` is the
  backend's window-scoped percentile across all sessions, not the median of the per-row burns.
- **`sessionsTrend` sparkline**: the shared `LineSparkline` (`components/LineSparkline`) renders
  if `values.length >= 2`. It draws a filled area + stroke line over the bucket counts returned by
  the summary endpoint. Renders nothing for a single-bucket window (the `< 2` guard lives in
  `LineSparkline`, shared with `MetricKpiStrip`). Its buckets count sessions that *opened* in the
  window and so **do not sum to the Total-sessions figure above them**, which counts sessions that
  were *active* in it — an all-zero line under a card reading `2` just means both sessions are
  older than the window. Making them agree would require bucketing on each session's earliest
  in-window emission, which is exactly the clipping that made every long-running session look
  brand new.
- **Session detail drawer**: `SessionsPage` holds a single `openSessionId: string | null` — one
  session's prompt timeline at a time, not a per-row `Set`. Clicking a row calls
  `onToggleSessionDetail(sessionId)`, which closes the drawer if that session is already open and
  otherwise switches to it; the drawer's own affordances (×, backdrop click, Escape — all three
  arrive as MUI's single `onClose`) go through `onCloseSessionDetail`. The initial value is
  **seeded from a `?sessionId=` deep link** (`DEEP_LINK_SESSION_PARAM`,
  built by the exported `sessionsDeepLink(sessionId)` helper — the Tokens page's
  cache-efficiency detail dialog is the current caller) so arriving from another page lands
  with that session's timeline already open; a `useEffect` then strips the param from the URL
  via `setSearchParams`'s functional-updater form (`replace: true`), not a manually cloned
  `URLSearchParams`. The param is consumed once, at mount, and the open session is plain page
  state afterwards — leaving it bound would mean a reload silently re-opening a drawer the user
  had closed. One known limit: the drawer auto-opens only if that session is on the first page
  under the default sort (there is no "which page holds this session" lookup on the server) —
  when it isn't, `openSessionId` is set but no row matches it, so `SessionsPageView`'s
  `rows.find(...)` resolves to null and the drawer stays shut. `sessionPromptsQuery`'s `enabled`
  gate checks the same `rows.some((row) => row.sessionId === openSessionId)` condition, so that
  case also fails closed rather than firing a wasted whole-session fetch.
  `openSessionId` is reset to `null` on window-selection change, sort change, and
  page/pageSize change (`handlePaginationModelChange` wraps `setPaginationModel`) — the open
  session's row usually won't survive any of those, so the drawer is closed defensively rather
  than left describing a row that is no longer on screen. It is **not** reset by
  `onReload`/auto-refresh — those revalidate the same page and shouldn't yank the drawer out from
  under the user. There is no expand caret or expander column: the whole `<tr>` is the affordance
  (`cursor: pointer`), and the open row keeps the hover highlight for as long as its drawer is up.
  Note that the scrim makes rows unclickable while the drawer is open, so in practice a row click
  never *closes* the drawer — it hits the backdrop first, which closes it. That's the mockup's
  behavior too; the toggle branch stays because the state model is "which session is open", not
  "was the last click on the open row".
- **Prompt-timeline window dimming**: `SessionsPageView` derives `windowStartMs`/`windowEndMs` by
  calling the shared `resolveWindow(selection)` (`lib/resolveWindow`, the same helper
  LogsPage/TracesPage use) and `Date.parse`-ing its `startTimestamp`/`endTimestamp`, then passes
  them to `SessionDetailDrawer`, which forwards them to `PromptTimelinePanel`. There is no page-local
  window-bounds calculation — using the shared resolver means the dimming boundary can never
  contradict what the summary/table queries actually counted (`resolveWindow` applies the same
  ingest-slack + `MAX_WINDOW_SPAN_MS` clamp those queries' window params get). LogsPage itself
  hasn't been migrated onto `resolveWindow` yet (separate tracked debt) — this page doesn't touch
  that.

## Gotchas

- **`SessionKpis` vs `SessionsKpis`**: the backend DTO type in `api/types.ts` is `SessionKpis`;
  the view re-names it `SessionsKpis` in its own exported interface. They share the same shape —
  the container passes `summaryQuery.data ?? EMPTY_KPIS` directly (no conversion needed because
  the shapes are identical fields).
- **`tokens` is reset-aware**: `SessionSummaryRow.tokens` is a backend-aggregated reset-aware
  total (MAX per stream then SUM across streams), not a plain SUM. The view formats it with
  `formatTokens` from `components/sessionsFormat.ts` (M/K compact) rather than the global
  formatter used on other pages. `SessionSummaryRow.tokenBreakdown` (a `SessionTokenBreakdown`,
  required, never null — missing kinds are `0`) is the window-scoped four-way split that backs
  the Tokens-cell hover (`TokenBreakdownTooltip`); the backend guarantees it sums to `tokens`.
- **`formatTokens`'s K→M boundary and zero-case are shared**, not duplicated: values in
  `[999_500, 999_999]` round to `"1M"` (not `"1000K"` — the naive `Math.round(n/1e3)` approach)
  because the function promotes into the M bucket whenever the K-rounded value would hit 1000.
  `<= 0` (and non-finite) values render `"—"`. Both `SessionsTable`'s Tokens column and
  `PromptTimelinePanel`'s per-turn `TokenUsage` call the *same* `sessionsFormat.formatTokens` —
  there's no second copy to drift out of sync on either the boundary or the zero-case.
- **Table box height** is `calc(100vh - BODY_CHROME_PX px)` (`BODY_CHROME_PX` = 320, top of
  `SessionsPageView.tsx`) with `minHeight: 420`. If you add or remove chrome above the table card,
  retune that constant or the table will over/under-fill the viewport.
- **Per-turn figures come from two different pipelines, and the timeline says which.** Each turn
  carries `attribution`: `REQUEST` means its model/cost/tokens are the exact per-call figures
  summed over that turn's own `api_request` logs (joined on `prompt.id`); `INTERVAL` means no such
  logs exist and the values were bucketed from cumulative counters by timestamp. The two are
  **different measurements, not two views of one number** — measured against live data they
  disagree by tens of percent in both directions, dominated by cache-read tokens. That is why
  `TurnAttributionMarker` labels `INTERVAL` turns "approx" (and labels `REQUEST` turns nothing —
  exact is the expectation, so only the exception earns ink) and why **a session row's
  `tokenBreakdown` no longer equals the sum of its turns' `tokens`**: the row is a windowed
  counter roll-up, the turns are whole-session per-request sums. Don't "reconcile" them.
- **There is no per-request drill-down here, deliberately.** An earlier revision put a clickable
  "N req" pill on each turn that opened a `TurnRequestTable` (time · model · effort · tokens ·
  cache read · cost · duration) inline, backed by a `sessionRequestsQuery` against
  `GET /api/sessions/{id}/requests`. It was removed: per-call model detail reads as trace-level
  information, and the trace detail page's span inspector is where it belongs (the turn's
  "View trace" link goes straight there). The backend endpoint still exists and is still
  documented in SESSIONS-BACKEND.md — it simply has no frontend consumer. `fetchSessionRequests`
  and the `SessionApiRequestRow` type were removed from `api/` along with it; re-adding the
  drill-down means restoring both, not flipping a prop.
- **Cache-efficiency column is derived, not a DTO field.** `cacheEfficiencyRatio` /
  `formatCacheEfficiency` (now in the shared `lib/cacheEfficiency.ts`, not `sessionsFormat.ts` —
  the Tokens page needs the identical ratio and bands) compute `cacheRead / (input +
  cacheCreation + cacheRead)` from the row's `tokenBreakdown` — output tokens are excluded (they're
  generated, never cached). There is no `cacheEfficiency` field on `SessionSummaryRow`; the column
  is derived client-side exactly like the `$/active min` burn cell. It is nonetheless **sortable
  server-side**: the header maps to the backend's whitelisted `cacheEfficiency` sort token
  (`MetricService.SORT_COLUMNS_BY_FIELD`), whose `ORDER BY` uses the identical ratio and sorts
  sessions with no input-side tokens `NULLS LAST` — the same rows `CacheEfficiencyCell` renders as
  "—". If you change the ratio's definition, change **all four** places in lockstep or the visible
  order stops matching the visible values: `lib/cacheEfficiency.ts`, this column's `ORDER BY`,
  `MetricService.aggregateTokenUsage[InRange]`'s `cacheReadRatio` (the Tokens page gauge), and
  `MetricPointRepository.aggregateWorstCacheEfficiencySessions` (the Tokens page ranking). Bands
  the shared `CACHE_EFFICIENCY_STRONG` / `_WEAK` thresholds (≥85% strong, ≥60% mixed). Colors are
  the shared `cacheEfficiencyBandColor(band, theme)` (a cross-page import from the Tokens page's
  `components/cacheEfficiencyBandColors.ts` — see that page's CLAUDE.md): strong → `success.main`,
  mixed → `text.primary`, weak → `warning.main`, unknown → `text.disabled`. `CacheEfficiencyCell`
  calls it rather than hand-rolling its own ternary, so this column and the Tokens page's ranking
  table/detail dialog can never disagree about which band a given ratio is in.
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
- **Per-turn `prompt` (in the timeline, not the grid's `firstUserPrompt`) can also be `null`** —
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
- **Zebra striping is index-driven, not `nth-of-type`-driven.** It's computed inline per row from
  the `.map` `index` (`index % 2 ? alpha(...) : 'transparent'`), matching `TraceTableView`'s
  pattern, and hover stays scoped to `tr.data-row`. The rule predates the drawer (rows used to be
  wrapped in a `Fragment` alongside an expansion `<tr>`, which shifted the parity of every row
  below an open one); keep it index-driven anyway, so re-introducing any interleaved row can't
  silently re-stripe the table.
- **`PromptTimelinePanel` is the Aurora glass timeline, not a plain recessed list.** Its panel
  background is a `radial-gradient` glow over an `alpha(text.primary, …)` wash (not the flat
  `alpha(neutralColors.white/inkLight, …)` surface older revisions used). It has **no height cap
  and no scroll of its own** — `minHeight: '100%'` inside the drawer body, which is the scroll
  container (before the drawer it was capped at `maxHeight: 340` with `overflowY: 'auto'`; don't
  put that back or the panel gets a second, nested scrollbar). Each turn renders as its own bordered card with a
  glowing rail dot, a model chip (`opus` → `primary.main`, `sonnet` → a mode-aware pink —
  `auroraColors.pink` light / `auroraColors.pinkBright` dark, matching the theme's other pink
  figures — `haiku`/unknown → `text.disabled`, keyed on the leading token so `"claude-sonnet-4-5"`
  matches), per-turn cost, `TokenUsage` (one combined `input + output + cacheCreation + cacheRead` total
  since the Aurora sync — the old "· N cached" secondary is gone, and the four-way split is one
  hover away via `TokenBreakdownTooltip`, advertised by a dotted underline matching the grid's
  own hover-affordance cells rather than `cursor: help` alone), tool-call chips (`ToolChips`, first 5 + `+N`
  overflow), and an optional "View trace" pill-link. Turns outside the active window render at
  `opacity: 0.45` with a "selected window starts/ends" hairline divider at each boundary crossing
  (see the window-dimming bullet above for where `windowStartMs`/`windowEndMs` come from).
- **Color pass (Aurora sessions-color handoff):** Opus and Sonnet model chips get a background
  tinted to their own accent (`alpha(accent, 0.16)`) rather than a flat gray pill — Haiku/unknown
  stay neutral so the "plain/cheap" tier doesn't visually compete with the two colored ones. The
  turn card's rail dot (`::before`) picks up the same per-turn accent via `modelAccentColor(modelKey,
  theme)` (exported logic lives inline in `PromptTimelinePanel.tsx`, shared by `ModelChip` and the
  rail dot so they can never disagree) — the dot's outer ring stays primary-tinted regardless of
  model. `ToolChips` renders every tool in one flat accent (`auroraColors.cyanBright`, same literal
  hue as the "Tools" span color on the Trace Detail page — see `traceColors.ts`'s
  `SERVICE_HUE['claude_code.tools']`) rather than a per-category palette; there is no `TOOL_CAT`
  mapping in the React code — the design mockup keeps one for a possible future per-category split,
  but the current UI has no behavior difference across categories, so building that mapping here
  would be unused code. The grid's Cost column and the drawer header's cost figure both tier
  through the exported `CostValue` component (`costTier`/`COST_WARM_THRESHOLD_USD` in
  `sessionsFormat.ts`): plain below $8, `auroraColors.gold` from $8, and the same violet→pink
  `gradients.auroraActionSoft` text the "Median cost" stat card uses once cost reaches the **live**
  P95 cost/session figure (threaded down as `hotCostThresholdUsd` from `SessionsPageView`'s
  `kpis.p95CostUsd` — never a hardcoded second copy of that number).
- **A non-null turn prompt goes through `components/PromptSummaryText` first, before the
  `AttributeList` machinery below ever sees it.** A prompt that's really a `<task-notification>`
  envelope (the harness delivered it when a background subagent finished — see
  `lib/promptSummary.ts`) renders as a muted, italic "SUBAGENT · summary" line instead; an
  ordinary prompt falls through to `PromptSummaryText`'s `renderOrdinary` render-prop, which this
  panel wires to the same `AttributeValue` it always used. The outer `Box`'s `color`/`fontStyle`
  only toggle on `turn.prompt == null` (the "not captured" placeholder case, described above) —
  the subagent-vs-ordinary distinction is handled entirely inside `PromptSummaryText`, not here.
  This panel also passes `onViewFullPrompt`, so a subagent-notification turn always gets a "View
  more" trigger next to its summary, opening the same `ExpandedValueDialog`
  (`setExpandedValue({ key: formatTimestamp(turn.timestamp), value: prompt })`, `prompt` here
  being the raw envelope text passed back through the callback) that an ordinary prompt's own
  "View more" button opens — unlike `SwitchTraceModalRow` on the Trace Detail page, which
  deliberately omits `onViewFullPrompt` (see that page's CLAUDE.md gotcha).
- **Long per-turn prompt text truncates through the shared `AttributeList` "View more" dialog
  machinery**, the same one `LogTable` uses, rather than rendering full text pre-wrapped inline:
  each ordinary prompt renders through `AttributeValue` (`truncate`, `inlineExpand={false}`) so
  anything over the shared 200-char threshold collapses to a whitespace-collapsed preview + "View
  more" button; `attrKey` is set to that turn's `formatTimestamp(turn.timestamp)` (the generic
  `sessionsFormat` formatter, not the panel's own compact `formatPromptTimestamp` used in the
  card header) so the dialog title identifies which prompt is open. `PromptTimelinePanel` owns
  its own `expandedValue: ValueDialogState | null` state and renders a single
  `ExpandedValueDialog` — this mirrors exactly how `AttributeList.tsx` wires itself, so it stays
  local to the panel and nothing new threads through the container/view. Import
  `AttributeValue`/`ValueDialogState` from `components/AttributeList/AttributeValue` and
  `ExpandedValueDialog` from `components/AttributeList/ExpandedValueDialog` directly (not the
  barrel, which only re-exports `AttributeList`) — same import shape `LogTable.tsx` uses.
- **Prompts are plain text, not JSON, and that's fine.** `ExpandedValueDialog` runs every value
  through `tryParseJson`, which bails out (returns `undefined`, no repair, no warning banner)
  unless the trimmed text starts with `{` or `[` — an ordinary prompt just renders as the raw
  pre-wrapped string in the dialog body. If a prompt happens to start with `{`/`[` and parses as
  valid (or repairable) JSON, the dialog will pretty-print it as if it were a structured
  attribute — an accepted, harmless quirk of reusing this component for plain text, not a bug to
  special-case around.
- **Per-turn "View trace" link**: each turn card's header renders a small pill-style
  `Box component={RouterLink} to={`/traces/${turn.traceId}`}` (react-router `Link`, so
  middle-click/cmd-click open in a new tab) — a filled, rounded chip with an `ArrowForwardIcon`,
  styled between the subtle inline-text-link idiom used for "+N more" buttons and the heavier
  gradient "Open full trace" button `TraceSummaryInlineView` uses. Rendered **only when
  `turn.traceId` is non-null** — no disabled placeholder for the ~35% of prompts that predate
  tracing.
- **The turn-card header wraps.** Its left group is `flexWrap: 'wrap'` with `columnGap`/`rowGap`,
  because at the drawer's 560px a long model name plus cost, tokens and the "approx" marker no
  longer fit on one line — unwrapped, the marker overprinted the View-trace pill. Don't switch it
  back to a single nowrap row.
- **The drawer's auto-scroll needs both of its triggers.** `SessionDetailDrawer` scrolls its body
  to the newest turn from a `useEffect` on `[open, prompts]` *and* from the slide transition's
  `onEntered`. Either alone misses a case: the timeline usually resolves after the drawer is
  already open (the effect catches that), but a session opened a second time has its prompts
  cached and renders them during the entering slide, when the panel isn't laid out yet and
  `scrollTop` silently clamps back to 0 (`onEntered` catches that one). Verified both ways in the
  browser — if you collapse them into one, re-check the cached-reopen path.
- **MUI v9 drawer styling goes through `slotProps`, not `PaperProps`.** Paper (560px / `92vw`
  cap / `background.default` + the shared `backdropGradient(mode)` from `theme/theme.ts`, since a
  panel stacked over the fixed body glow would otherwise read as a flat slab), backdrop tint, and
  the 260ms `cubic-bezier(.22,.8,.24,1)` slide + `onEntered`/`onExited` all live in
  `slotProps.paper` / `.backdrop` / `.transition`. `PaperProps` was removed in v9 (see
  [DESIGN-CONSTRAINTS.md](../../../../DESIGN-CONSTRAINTS.md)).
- **The drawer keeps the last session rendered while it slides out.** `session` going null would
  otherwise blank the header and timeline the instant the row is deselected, mid-animation. Same
  guarded render-phase-setState pattern as `SpanInspectorDrawer` (compared by session id, since
  the view resolves a fresh row object out of the query result every render), cleared on
  `onExited`.
