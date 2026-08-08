# Tokens page

Token usage dashboard: spend KPIs, a stacked-area time-series chart of the four token types
over the selected window, a token-composition donut with cache-efficiency health gauge, a
worst-cache-efficiency session ranking, an estimated per-tool context footprint, and a
per-model token breakdown. Backend counterpart: `SessionController.tokenUsage` →
`MetricService.aggregateTokenUsage[InRange]` (`backend/.../controller/SessionController.java`),
served at `GET /api/sessions/token-usage`, plus `GET /api/sessions/cache-efficiency` and
`GET /api/tool-activity/context-footprint`.

## Files

```
TokensPage/
├── TokensPage.tsx              container — window context, three queries, emptySummary fallback
├── TokensPageView.tsx          view — derives KPI cards, chart series, donut slices, cache-ratio
│                               color/label; composes all six sub-cards and the AreaTrendChart
├── components/
│   ├── TokenSummaryCards/      four-tile KPI strip (Total cost · Total tokens · Models used · Top model)
│   │   ├── TokenSummaryCards.tsx
│   │   └── index.ts
│   ├── TokenByModelCard/       per-model token sums — name + colour dot, big total, share bar
│   │   ├── TokenByModelCard.tsx
│   │   └── index.ts
│   ├── TokenCompositionCard/   token-mix donut (SVG hand-built) + cache-efficiency gauge
│   │   ├── TokenCompositionCard.tsx
│   │   └── index.ts
│   ├── CacheEfficiencyRankCard/  worst-cache-efficiency sessions, bar length = the ratio itself
│   │   ├── CacheEfficiencyRankCard.tsx
│   │   └── index.ts
│   └── ContextFootprintCard/   per-tool context footprint (BreakdownList) + "estimated" chip
│       ├── ContextFootprintCard.tsx
│       └── index.ts
└── index.ts
```

## Visual layout

```
┌─ PageLayout ────────────────────────────────────────────────────────────────┐
│ "Token Usage" title/subtitle        [PageActions: WindowSelector ⟳ auto]    │
├─────────────────────────────────────────────────────────────────────────────┤
│ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────────────┐  │
│ │ Total cost   │ │ Total tokens │ │ Models used  │ │ Top model          │  │
│ │ (accent KPI) │ │              │ │              │ │ colour dot + name  │  │
│ └──────────────┘ └──────────────┘ └──────────────┘ └────────────────────┘  │
│  ← TokenSummaryCards: 4-column grid (xs:1, sm:2, lg:4) ──────────────────  │
│                                                                             │
│ ┌─ Paper: Token usage over time ────────────────────────────────────────┐  │
│ │ title + ⓘ tooltip      [AreaTrendLegend — Cache read/creation/Input/  │  │
│ │                          Output; click to toggle series visibility]   │  │
│ │ AreaTrendChart (stacked=false, yScale=log, height=320)                │  │
│ └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│ ┌─ TokenCompositionCard ────────────────────────────────────────────────┐  │
│ │ ┌──── donut (SVG) ────┐  │  Cache read ratio                         │  │
│ │ │  total-tokens label  │  │  big % + LinearProgress bar (0–100%)     │  │
│ │ └─────────────────────┘  │  health badge (healthy / mixed / poor)    │  │
│ │ descriptive legend rows  │  savings note (savedTokens)               │  │
│ └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│ ┌─ TokenByModelCard ────────────────────────────────────────────────────┐  │
│ │ "Token sum by model"                                                  │  │
│ │ BreakdownList (layout="column-card") — name/dot · big total · bar    │  │
│ └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

| Source                   | Query key                         | Fetcher → endpoint |
|--------------------------|-----------------------------------|--------------------|
| `TokensPage` (`useQuery`) | `['token-usage', selectionKey]`   | `fetchTokenUsage(selection)` → `GET /api/sessions/token-usage?…` |
| `TokensPage` (`useQuery`) | `['session-cache-efficiency', selectionKey, limit]` | `fetchSessionCacheEfficiency(selection, 8)` → `GET /api/sessions/cache-efficiency?…&limit=8` |
| `TokensPage` (`useQuery`) | `['tool-context-footprint', selectionKey]` | `fetchToolContextFootprint(selection)` → `GET /api/tool-activity/context-footprint?…` |

`selectionKey` is `'preset:<minutes>'` or `'custom:<start>:<end>'`. No sub-card fetches
independently — the container owns all three queries and passes plain props down.

The token summary is the page's spine, so its error wins `PageLayout`'s error slot; the two
ranked lists are supplementary, and when only one of them fails the page still renders with
that card showing its own empty state. `onReload` refetches all three.

## Data flow and semantics

- `TokensPage` reads `selection` / `setSelection` / `autoRefresh` / `setAutoRefresh` from
  `useWindowContext()`. It builds `selectionKey`, sets `refetchInterval` to
  `AUTO_REFRESH_INTERVAL_MS` (60 000 ms) only when `autoRefresh && selection.kind === 'preset'`
  (custom ranges have a fixed end, so polling would re-fetch the same slice), and passes the
  resolved `summaryQuery.data ?? emptySummary` as the `summary` prop to the view.
- `emptySummary` is a fully-typed zero-state constant declared in the container. It prevents the
  view and its sub-cards from having to guard against `undefined` on every field access.
- `TokensPageView` is the only place where data is derived. It computes:
  - `totalTokens` — the four scalar fields summed for the KPI tile and the donut center.
  - `mixSlices` — the four `CompositionSlice` entries (`colorForIndex(0–3)`), sorted largest-first,
    with `TYPE_DESCRIPTIONS` descriptions; passed to `TokenCompositionCard`.
  - `series` and `axisDates` — mapped from `summary.points` (each `TokenUsagePoint` has
    `timestamp`, `input`, `output`, `cacheCreation`, `cacheRead`); coloured via `colorForIndex(0–3)`.
  - `hasChartData` — true when `axisDates.length >= 2` (one bucket is not plottable on a log scale).
  - `ratioColor` / `ratioLabel` — threshold-band helpers that map `cacheReadRatio` (0–1) to
    `theme.palette.{success,warning,error}.main` or `text.disabled`.
  - `summaryCards` — four `TokenSummaryCard` objects for `TokenSummaryCards`; cost card uses the
    pre-formatted `summary.cost.spend24h` string (not a raw number), delta sign is read from
    `summary.cost.deltaPct` prefix (`-` → green down-arrow, otherwise red up-arrow).
  - `windowLabel` — derived from the current `selection` so cost captions read "vs. prev Last 24 h"
    (not a hardcoded "24h").
- `TokenByModelCard` receives `summary.byModel` (`TokenModelShare[]`) verbatim; it delegates
  rendering to `BreakdownList` with `layout="column-card"`.
- `TokenCompositionCard` contains the only hand-built SVG on this page: a donut built from
  `<circle>` arcs using `strokeDasharray` / `strokeDashoffset` math. The ratio gauge uses a
  standard MUI `LinearProgress`.
- The area chart uses the shared `AreaTrendChart` with `stacked={false}` and `yScale="log"` so
  that cache-read (large) and output (small) series are both visible without one drowning the
  other. Series visibility is managed by `useSeriesVisibility` from `AreaTrendChart`; the
  `AreaTrendLegend` in the card header provides the toggle UI.

## Gotchas

- **Cache efficiency has exactly one definition, and it lives in `lib/cacheEfficiency.ts`.**
  It is `cacheRead / (input + cacheCreation + cacheRead)` — the share of input-side tokens
  served from cache. Output is excluded (generated, never sent); cache-creation is deliberately
  kept in the denominator, because dropping it would let a session that constantly rebuilds its
  cache read as efficient. Four places compute this and must move together: this module,
  `MetricService.aggregateTokenUsage[InRange]`'s `cacheReadRatio` (the gauge),
  `MetricPointRepository.aggregateSessionSummaries`' whitelisted `cacheEfficiency` `ORDER BY`
  (the Sessions grid column's server-side sort), and
  `aggregateWorstCacheEfficiencySessions` (this page's ranking). The gauge's bands are the
  shared `cacheEfficiencyBand` bands (≥85% strong, ≥60% mixed) — **not** the old page-local
  0.7/0.4 pair, and **not** the old `cacheRead / (cacheCreation + cacheRead)` denominator that
  made this page's number disagree with the Sessions column.
- **`ContextFootprintCard`'s token figure is an estimate and must stay visually separate from
  the exact cards.** It is `bytes / 4`, carries its own "estimated" chip, and leads with byte
  values for that reason. Two things it is not: (1) billed spend — never add it to or compare
  it against `TokenUsageSummary`; (2) a full accounting of cost — a tool result is re-sent with
  every later request in its session, so the one-time size understates what it actually costs.
  Its `calls` also runs lower than the Tool Calls page's count, because calls that reported no
  `tool_result_size_bytes` are excluded rather than counted as zero.
- **The context footprint counts tools the tuning report deliberately skips.** Agent/WebFetch
  and image reads are excluded from the report's oversized-results list because nothing in
  AGENTS.md can tune them; they are included here because this card asks a different question
  — what is filling the window — and "delegate this to a subagent" is one of the levers the
  comparison exists to inform.
- **Rows past the top 8 collapse into "Other" rather than being dropped**, so the percentages
  still sum to 100. MCP tool names (`mcp__server__tool`) proliferate, and a silent top-N would
  make the visible shares look complete when they weren't.
- **An empty cache-efficiency ranking is a real answer, not a failure.** The server applies a
  noise floor (`tuning.cache-efficiency-minimum-input-tokens`, default 100k input-side tokens)
  before ranking, because a session that made two small calls can sit at 0% without anything
  being wrong. `CACHE_EFFICIENCY_FLOOR_LABEL` in the view mirrors that default for the card's
  copy only — the server owns the real floor.
- **`summary.cost.spend24h` is a pre-formatted string** (e.g. `"$4.23"`), not a raw number.
  The field is named `spend24h` for historical reasons but always reflects the selected window's
  spend — don't treat it as a 24-hour-only metric. The same applies to `deltaPct`, `burnRate`,
  `projected30d`, and `costPer1k`.
- **`summary.byModel[].tokens` is a pre-formatted string** (e.g. `"7.8M"`). `TokenByModelCard`
  renders it as-is; don't pass it through `formatCompact`.
- **Log scale requires at least two points.** The chart is hidden and `emptyMessage` is shown
  when `axisDates.length < 2`. If there is exactly one bucket, a targeted message says so rather
  than a generic "No data."
- **Cache-read-ratio denominator guard.** `denominatorEmpty` is `cacheCreationTokens + cacheReadTokens === 0`.
  When true, `ratioBandLabel` returns "No cache activity in this window" and the ratio display
  renders `—` rather than `0.0%`.
- **Color ordering in chart vs. donut differs.** The area chart series are ordered
  `[Cache read, Cache creation, Input, Output]` (index 0–3). The donut slices use the same
  `colorForIndex` mapping but are **sorted largest-first** after assignment so the arc order
  tracks data shape. The legend always shows the original label order.
- **`TokenCompositionCard` calls `useTheme` directly** for the SVG track color and alpha.
  It is a presentational component that deviates minimally from the pure-props rule; it does not
  call any hook that performs I/O or owns state.
