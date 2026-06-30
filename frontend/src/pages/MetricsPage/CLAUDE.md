# Metrics page

Master-detail view over the six `claude_code.*` counters. A KPI strip across the top
lets users pick a metric; below it, the selected metric's header stats, a windowed
`AreaTrendChart`, and a breakdown card are shown. A "Split by" toggle stacks the
chart by attribute (model, type, change, …) when the selected metric supports it.
Backend counterpart: `MetricsController` → `MetricSeriesService`
(`backend/.../controller/MetricsController.java`, `GET /api/metrics/series`).

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
│   ├── MetricKpiStrip/       responsive 6-card grid; each card = StatCard with
│   │   ├── MetricKpiStrip.tsx  shared LineSparkline + Δ chip; selected card gets
│   │   └── index.ts            accent ring via PaperProps sx override
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
│ ┌─ MetricKpiStrip: 6-column grid (2 xs / 3 sm / 6 lg) ─────────────────┐ │
│ │ [token.usage] [cost.usage] [session.count] [active_time] [loc] [decision] │ │
│ │  sparkline · sum · Δ chip;  selected = accent ring + action.selected  │ │
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
- **Chart series derivation.** When `split === 'None'` the chart renders one series (the metric's
  raw `trend` array). When a split is active, `MetricTrendCard` maps each `MetricSplitRow` to a
  scaled series: `data[i] = trend[i] * row.pct / 100`. Colors come from
  `colorForIndex(row.colorIndex)` so all split series stay on the shared dashboard palette.
- **x-axis is wall-clock anchored.** `axisDates` is derived inside `MetricTrendCard` from
  `Date.now()` at render time and the length of `metric.trend` — 24 points spaced evenly over
  24 h. It is NOT tied to the `from`/`to` params, so the axis is an approximation of the window
  rather than an exact label.
- **`SegmentedToggle` appears only when `splitKeys.length > 1`** (i.e. the selected metric
  has at least one attribute split). Metrics without splits (`session.count`, `active_time.total`)
  show no toggle.
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
- `VITE_METRICS_SAMPLE=1` returns static data for all six metrics immediately; the `isLoading`
  and `error` paths are therefore untested without a live backend. The chart renders a
  `Loading…` placeholder only when `isLoading === true`.
- The `'metrics/series'` query key uses a forward-slash, unlike the `'kebab-feature'` convention
  used by other pages. If you refactor, also update `MetricsPage`'s manual `refetch()` call —
  it uses the hook directly, not `queryClient.invalidateQueries`, so the key mismatch would only
  matter if a section-level reload predicate is added later.
