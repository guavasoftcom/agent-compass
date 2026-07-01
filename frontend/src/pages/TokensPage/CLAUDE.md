# Tokens page

Token usage dashboard: spend KPIs, a stacked-area time-series chart of the four token types
over the selected window, a token-composition donut with cache-read-ratio health gauge, and a
per-model token breakdown. Backend counterpart: `SessionController.tokenUsage` →
`MetricService.aggregateTokenUsage[InRange]` (`backend/.../controller/SessionController.java`),
served at `GET /api/sessions/token-usage`.

## Files

```
TokensPage/
├── TokensPage.tsx              container — window context, single query, emptySummary fallback
├── TokensPageView.tsx          view — derives KPI cards, chart series, donut slices, cache-ratio
│                               color/label; composes all four sub-cards and the AreaTrendChart
├── components/
│   ├── TokenSummaryCards/      four-tile KPI strip (Total cost · Total tokens · Models used · Top model)
│   │   ├── TokenSummaryCards.tsx
│   │   └── index.ts
│   ├── TokenByModelCard/       per-model token sums — name + colour dot, big total, share bar
│   │   ├── TokenByModelCard.tsx
│   │   └── index.ts
│   └── TokenCompositionCard/   token-mix donut (SVG hand-built) + cache-read-ratio gauge
│       ├── TokenCompositionCard.tsx
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

`selectionKey` is `'preset:<minutes>'` or `'custom:<start>:<end>'`. There is exactly one query
on this page; all four sub-cards and the chart are fed entirely from props derived from
`summaryQuery.data`. No sub-card fetches independently.

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
