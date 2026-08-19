# Metrics page

Master-detail view over the `claude_code.*` counters. A KPI strip across the top
lets users pick a metric; below it, the selected metric's header stats, a windowed
`AreaTrendChart`, and a breakdown card are shown. A "Split by" toggle stacks the
chart by attribute (model, type, change, …) when the selected metric supports it.
Backend counterpart: `MetricsController` → `MetricSeriesService`
(`backend/.../controller/MetricsController.java`, `GET /api/metrics/series`).

**The card count is not fixed.** `MetricSeriesService` returns eight *curated*
metrics (token, cost, session, active, loc, decision, commit, pull_request) followed
by a generated card for every other metric name present in `metric_points` — so a
counter introduced by a newer Claude Code release appears here with no frontend
change. Discovered cards have a placeholder description and no splits; promoting
one means adding a `MetricSpec` to `curatedMetricSpecs()` in the backend. Nothing
in this folder may assume a fixed metric count — the strip is a single uniform grid
for exactly this reason (see the gotcha below).

## Files

```
MetricsPage/
├── MetricsPage.tsx           container — window context, resolves ISO timestamps,
│                             runs the single useQuery, passes props to view
├── MetricsPageView.tsx       view — selectedId / split state, composes the four
│                             sub-components; chart-series derivation lives in
│                             MetricTrendCard, not here
├── metricsApi.ts             MetricsQueryParams interface + fetchMetrics();
│                             also defines the page-local getJSON helper
│                             (does NOT use api/http.ts)
├── components/
│   ├── metricsSampleData.ts  MetricSeries + MetricSplitRow types; the METRICS
│   │                         fixture array; re-exported by metricsApi.ts as the
│   │                         default prop value and by all four components
│   ├── MetricKpiStrip/       uniform-grid picker: every metric a full StatCard
│   │   ├── MetricKpiStrip.tsx  (LineSparkline + Δ); selected card gets an accent
│   │   └── index.ts            ring; layout never depends on which/how many metrics
│   ├── MetricHeader/         selected-metric detail header: full name, type/unit
│   │   ├── MetricHeader.tsx    badges, description, sum/rate/peak/delta stats
│   │   └── index.ts
│   ├── MetricTrendCard/      left-hand trend chart card; derives axisDates + series
│   │   ├── MetricTrendCard.tsx internally; renders Split-by toggle, legend swatches,
│   │   └── index.ts            and AreaTrendChart (or Loading placeholder)
│   └── MetricBreakdown/      right-hand card; summary (sum/rate/peak) when split
│       ├── MetricBreakdown.tsx is "None", or a BreakdownList when a split is active
│       └── index.ts
└── index.ts
```

## Visual layout

```
┌─ PageLayout ──────────────────────────────────────────────────────────────┐
│ eyebrow "Observability" / title "Metrics" / subtitle          [PageActions] │
├───────────────────────────────────────────────────────────────────────────┤
│ ┌─ MetricKpiStrip ──────────────────────────────────────────────────────┐ │
│ │ one uniform grid (4 cols ≥1081px → 3 ≥901px → 2 ≥621px → 1), every    │ │
│ │ metric the same full card: name · sum · sparkline (bars if sparse) · Δ │ │
│ │  selected = accent ring + action.selected                             │ │
│ └───────────────────────────────────────────────────────────────────────┘ │
│ ┌─ MetricHeader (Paper) ─────────────────────────────────────────────────┐ │
│ │ claude_code.token.usage  [Counter]  [tokens]                           │ │
│ │ description one-liner                                                  │ │
│ │ Sum (24h) · Rate · Peak / h · vs. prev 24h                             │ │
│ └────────────────────────────────────────────────────────────────────────┘ │
│ ┌─ MetricTrendCard ───────────────────────┐ ┌─ MetricBreakdown ──────────┐ │
│ │ "<metric> over time"   [Split by: None  │ │ Summary                    │ │
│ │                         Model  Type]    │ │  sum / rate / peak         │ │
│ │ legend swatches                         │ │ (or BreakdownList when      │ │
│ │ AreaTrendChart height=290               │ │  a split is active)        │ │
│ └─────────────────────────────────────────┘ └────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

| Source | Query key | Fetcher → endpoint |
|---|---|---|
| `MetricsPage` (`useQuery`) | `['metrics/series', params]` where `params = { from, to }` | `fetchMetrics(params)` → `GET /api/metrics/series?from=…&to=…` |

`MetricKpiStrip`, `MetricHeader`, `MetricTrendCard`, and `MetricBreakdown` never
fetch — they receive `MetricSeries[]` (or a single `MetricSeries`) as props from
the view. The other `MetricsController` endpoints (`GET /api/metrics`,
`/api/metrics/catalog`, `/api/metrics/cost`, `/api/metrics/distribution`,
`/api/metrics/attributes`) are not called by this page in its current form.

## Data flow and semantics

- **Timestamp resolution happens in the container.** `MetricsPage` derives `{ from, to }` as
  ISO-8601 strings inside a `useMemo` keyed on `selection`: preset windows stamp `from = now -
  minutes * 60s` and `to = now` at render time; custom windows read `startTimestamp` /
  `endTimestamp` directly. These resolved params are the query key, so changing the window
  selection produces a new cache entry. Note: unlike LogsPage's fetch-time resolution, the
  timestamp is anchored at the moment the key is computed — auto-refresh ticks trigger a refetch
  but the end-of-window drifts only when `selection` itself changes or a 60-second auto-refresh
  refetch fires and the component re-renders.
- **Sample data fallback.** `VITE_METRICS_SAMPLE=1` makes `fetchMetrics` return the `METRICS`
  fixture immediately without a network call. The view also defaults `metrics` to `METRICS` when
  the prop is undefined, so the page renders meaningfully before the backend is available.
- **Split state lives in the view.** `MetricsPageView` owns `selectedId` (which metric card is
  active) and `split` (current breakdown key, or `'None'`). Selecting a different metric card
  via `selectMetric` resets the split to `'None'` so stale split labels never bleed across metrics.
- **The strip is one uniform grid, not tiered by importance.** `MetricKpiStrip` renders every
  metric — curated or discovered (including one whose id is its dotted name with dashes,
  `claude_code-commit-count`) — as the same full `StatCard` with its own sparkline. The layout
  therefore never requires a decision when the backend appends a new counter; it lands in the next
  grid cell. Breakpoints are the design's own raw `min-width` queries (621 / 901 / 1081px), not
  MUI's `sm`/`md`/`lg` keys.
- **Sparse whole-number counters draw as bars, in the card and the chart.** `isSparseCounter`
  (`lib/format.ts`) is true when a trend's peak is ≤ 5 and every value is an integer; both
  `MetricTrendCard` (switches `AreaTrendChart` from an interpolated area to per-bucket bars, steps
  the y-axis by whole numbers, and adds a bucket of headroom above the peak) and `LineSparkline`
  (switches the KPI-strip card's sparkline the same way) import the one predicate, so a card never
  shows bars while its chart shows an area. It's a threshold, not a per-metric flag, so a discovered
  counter gets it without a spec. `commit.count` and `pull_request.count` hit it; `session.count`
  does not (its bucket values are fractional), so it keeps the area/line form.
- **Unit-less metrics need two guards.** `pull_request.count` is the first curated metric with an
  empty `unit`. `MetricKpiStrip`'s `unitSuffix` returns `null` (not an empty `Box`) when
  `metric.unit` is falsy, and `MetricHeader`'s unit badge prints `metric.unit || '—'` so the badge
  keeps its pill shape instead of collapsing to 6px.
- **Chart series derivation.** When `split === 'None'` the chart renders one series (the metric's
  raw `trend` array). When a split is active, `MetricTrendCard` maps each `MetricSplitRow` to a
  scaled series: `data[i] = trend[i] * row.pct / 100`. Colors come from
  `colorForIndex(row.colorIndex)` so all split series stay on the shared dashboard palette.
- **x-axis is wall-clock anchored.** `axisDates` is derived inside `MetricTrendCard` from
  `Date.now()` at render time and the length of `metric.trend` — 24 points spaced evenly over
  24 h. It is NOT tied to the `from`/`to` params, so the axis is an approximation of the window
  rather than an exact label.
- **`SegmentedToggle` (and the title's ⓘ tooltip) appear only when `splitKeys.length > 1`**
  (i.e. the selected metric has at least one attribute split) — both gated by the same `hasSplits`
  flag, since the tooltip explains what the toggle does and has nothing to say without it. Metrics
  without splits (`session.count`, `active_time.total`, `commit.count`, `pull_request.count`, and
  every discovered metric) show neither.
- **`MetricBreakdown` doubles as two cards in one.** With `split === 'None'` it shows a compact
  summary panel. With a split active it passes `BreakdownRow[]` (derived from `MetricSplitRow`)
  to the shared `BreakdownList` component with `layout="stacked"`.

## Gotchas

- `metricsApi.ts` defines its own `getJSON` helper rather than importing from `api/http.ts`.
  If the shared transport changes (auth headers, error shape), this file must be updated
  separately.
- `MetricSeries` and `MetricSplitRow` are defined in `components/metricsSampleData.ts`, not in
  `metricsApi.ts`. All four sub-components import from `'../metricsSampleData'`; `metricsApi.ts`
  re-exports the type. Keep the type source in `metricsSampleData.ts` until the API is stable
  enough to own the shape.
- `VITE_METRICS_SAMPLE=1` returns static data for the eight curated metrics immediately; the
  `isLoading` and `error` paths are therefore untested without a live backend. The chart renders a
  `Loading…` placeholder only when `isLoading === true`. Sample mode has no fixture for a
  *discovered* metric, so that path only exercises against a real database.
- The `'metrics/series'` query key uses a forward-slash, unlike the `'kebab-feature'` convention
  used by other pages. If you refactor, also update `MetricsPage`'s manual `refetch()` call —
  it uses the hook directly, not `queryClient.invalidateQueries`, so the key mismatch would only
  matter if a section-level reload predicate is added later.
