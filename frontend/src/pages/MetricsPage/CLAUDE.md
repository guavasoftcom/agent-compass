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
# Metrics page

Master-detail view over the `claude_code.*` counters. A searchable catalog rail on
the left lets users pick a metric; beside it, the selected metric's header stats, a
windowed `AreaTrendChart`, and a breakdown card are shown. A facet bar above both hosts
three controls scoped to the selected metric: **attribute filters** (any number of ANDed
`key = value` chips, then an always-present "+ Add filter" picker), a "Split by" toggle that
stacks the chart by attribute (model, type, change, …) when the metric supports it, and an
**Agg** toggle (sum / avg / p95 / count) that re-aggregates the trend chart. Below those, a
full-width per-request distribution card (a scatter of every request at its real time and value,
dashed p50/p95/p99 reference lines, and ringed exemplar dots that open a right-side quick-peek drawer
of the request's trace) appears for metrics the backend flags with `hasDistribution` (tokens, cost); every other
metric gets a persistent placeholder card in the same slot.
Backend counterpart: `MetricsController` → `MetricSeriesService`
(`backend/.../controller/MetricsController.java`, `GET /api/metrics/series`,
`GET /api/metrics/attributes` and `GET /api/metrics/distribution`).

**The metric count is not fixed.** `MetricSeriesService` returns eight *curated*
metrics (token, cost, session, active, loc, decision, commit, pull_request) followed
by a generated entry for every other metric name present in `metric_points` — so a
counter introduced by a newer Claude Code release appears here with no frontend
change. Discovered metrics have a placeholder description and no splits; promoting
one means adding a `MetricSpec` to `curatedMetricSpecs()` in the backend. Nothing
in this folder may assume a fixed metric count — the catalog rail is a scrolling
list of uniform rows for exactly this reason (see the gotcha below).

## Files

```
MetricsPage/
├── MetricsPage.tsx           container — window context, resolves ISO timestamps,
│                             owns selectedId + attributeFilters (list) + aggregation +
│                             facetPickerOpen, runs the series useQuery, the gated
│                             distribution useQuery, the lazy facets useQuery and the
│                             exemplar drawer's trace-summary + trace-spans queries, owns
│                             openTraceId, passes the window's from/to to the view,
│                             navigates on the drawer's "Open in Traces"
├── MetricsPageView.tsx       view — split / search state (selectedId, filters and
│                             aggregation are controlled props), composes the sub-
│                             components; chart-series derivation lives in
│                             MetricTrendCard, not here
├── MetricsPageView.test.tsx  vitest coverage for the view (renderWithProviders, prop
│                             fixtures, a stateful wrapper standing in for the container's
│                             selectedId / filters / aggregation)
├── metricsApi.ts             MetricsWindowParams / MetricsQueryParams, MetricAggregation,
│                             AttributeFilter, MetricFacet(Value), DistributionPoint /
│                             MetricDistribution + DISTRIBUTION_POINT_CAP (API-owned);
│                             fetchMetrics() + fetchMetricFacets() (unwraps
│                             `.attributes`) + fetchMetricDistribution(); also defines the
│                             page-local getJSON helper (does NOT use api/http.ts)
├── metricsApi.test.ts        stubs global fetch: asserts the exact query params each
│                             fetcher sends (repeated `filter=`, `metric=`, none-when-idle)
├── components/
│   ├── metricsSampleData.ts  MetricSeries + MetricSplitRow types; the METRICS
│   │                         fixture array; re-exported by metricsApi.ts as the
│   │                         default prop value and by all the components
│   ├── metricDistributionSampleData.ts (+ .test.ts)
│   │                         buildMetricDistributionSample(metricName, from, to) — the
│   │                         deterministic (createSampleRng, fixed seed per metric)
│   │                         VITE_METRICS_SAMPLE fixture for token/cost: bursty activity
│   │                         clusters with idle gaps, heavy-tailed values, six exemplars
│   │                         with fake trace ids; ascending by ts
│   ├── metricFacetsSampleData.ts
│   │                         buildMetricFacetsSample(metricName) — VITE_METRICS_SAMPLE
│   │                         facets fixture keyed by FULL metric name (token/cost: model +
│   │                         terminal.type; loc: type; decision: decision; else [])
│   ├── metricAggregation.ts  AGGREGATION_OPTIONS + describeAggregation(agg, unit) — no-React
│   │                         legend / y-label / title-suffix copy for a non-sum aggregate
│   ├── metricHealth.ts       HEALTH_LABEL + healthColor(health, theme) — shared,
│   │                         no-React health copy/color lookup for MetricSeries.health
│   ├── metricTypeColor.ts    metricTypeColor(type, theme) — no-React counter/gauge/
│   │                         histogram accent lookup, used by the rail's type badge
│   ├── MetricCatalogRail/    left-hand picker: search box + scrolling, grouped list of
│   │   ├── MetricCatalogRail.tsx  rows (type badge, name, health dot, LineSparkline,
│   │   └── index.ts               cardinality); filters by name substring itself;
│   │                              selected row gets an accent ring
│   ├── MetricFacetBar/       full-width bar above rail + detail: Filter (left), Split by
│   │   ├── MetricFacetBar.tsx  + Agg (right); Split by is omitted (not the bar) when the
│   │   ├── AttributeFilterControl.tsx  metric has no splits. The control is the filter
│   │   │                       chips + the always-present "+ Add filter" pill and its
│   │   │                       two-step Popover
│   │   ├── attributeFilters.ts  pure list ops: applyFilterSelection (append / replace by
│   │   │                       key), removeFilterAt, isFilterActive, findFilterForKey
│   │   ├── attributeFilters.test.ts
│   │   ├── MetricFacetBar.test.tsx
│   │   └── index.ts
│   ├── MetricHeader/         selected-metric detail header: full name, type/unit
│   │   ├── MetricHeader.tsx    badges, description, sum/rate/peak/series/
│   │   └── index.ts            cardinality/health/delta stats
│   ├── MetricTrendCard/      trend chart card; derives axisDates + series internally;
│   │   ├── MetricTrendCard.tsx renders the title, legend swatches, and AreaTrendChart
│   │   └── index.ts            (or Loading placeholder); reads `split` + `aggregation`,
│   │                           never sets them
│   ├── MetricBreakdown/      right-hand card; summary (sum/rate/peak) when split is
│   │   ├── MetricBreakdown.tsx "None" or the aggregation is non-sum, or a BreakdownList
│   │   └── index.ts            when a split is active
│   ├── MetricDistributionCard/  full-width per-request card: pixel-measured SVG scatter
│       ├── MetricDistributionCard.tsx  (one dot per request), dashed p50/p95/p99 lines,
│       │                        exemplar <button> dots, y/x axes, chips, honesty note;
│       │                        placeholder / loading / error / empty variants
│       ├── distributionScatter.ts  pure, no-React helpers: computePercentile /
│       │                        summarizeValues, buildValueScale (log | linear) +
│       │                        valueToFraction, timestampToFraction, buildTimeAxisLabels,
│       │                        value formatters, buildScatterPoints
│       ├── distributionScatter.test.ts
│       └── index.ts
│   └── MetricExemplarDrawer/  right-side quick peek at one exemplar's trace (MUI Drawer, 560px,
│       ├── MetricExemplarDrawer.tsx  max 94vw, scrim): header (eyebrow, trace id, close), context
│       │                        line, stat row (the request's tokens/cost, duration, spans, model,
│       │                        status), a compact waterfall of real SpanWaterfallRow rows, an
│       │                        AttributeList, and the "Open in Traces" footer CTA. Keeps the last
│       │                        exemplar rendered through the slide-out. Exports MetricExemplar
│       ├── exemplarWaterfall.ts   pure, no-React: buildExemplarWaterfall (depth-first rows down to
│       │                        depth 1, capped at 40, bars scaled to the DRAWN rows' extent),
│       │                        summarizeExemplarModels, exemplarStatusOf
│       ├── exemplarWaterfall.test.ts
│       ├── MetricExemplarDrawer.test.tsx
│       └── index.ts
└── index.ts
```

## Visual layout

```
┌─ PageLayout ─────────────────────────────────────────────────────────────────┐
│ eyebrow "Observability" / title "Metrics" / subtitle          [PageActions]  │
├──────────────────────────────────────────────────────────────────────────────┤
│ MetricFacetBar (full width, whenever a metric is selected)                   │
│ FILTER [model = sonnet x] [terminal.type = vscode x] [+ Add filter]          │
│                                      Split by [None|Model|Type] (i)          │
│                                      Agg [sum|avg|p95|count]                 │
│                                                                              │
│ grid: xs = one column, rail on top; md+ = 286px rail | 1fr detail column     │
│ ┌─ MetricCatalogRail ──┐  ┌─ detail column ───────────────────────────────┐  │
│ │ [Search metrics…]    │  │ MetricHeader (Paper)                          │  │
│ │ CLAUDE CODE        8 │  │  claude_code.token.usage  [Counter] [tokens]  │  │
│ │ coun token.usage   ● │  │  description one-liner                        │  │
│ │ ~sparkline~ 1.2K ser │  │  Sum · Rate · Peak/h · Series · Cardinality · │  │
│ │ coun cost.usage    ● │  │  Health · vs. prev                            │  │
│ │ ...rows scroll       │  │                                               │  │
│ │ (max height 780)     │  │ MetricTrendCard        │ MetricBreakdown      │  │
│ │ selected = accent    │  │  title, legend swatches│  summary, or         │  │
│ │ ring + selected bg   │  │  AreaTrendChart h=290  │  BreakdownList       │  │
│ │                      │  │ (trend | breakdown side by side at xl only)   │  │
│ │                      │  │                                               │  │
│ │                      │  │ MetricDistributionCard (full detail width)    │  │
│ │                      │  │  name  per request · distribution  N requests │  │
│ │                      │  │  y ticks | scatter: one dot per request,      │  │
│ │                      │  │          | dashed p50/p95/p99, ringed exemplars│  │
│ │                      │  │          | x-axis labels (real window time)   │  │
│ │                      │  │  (a note line under the title while a filter  │  │
│ │                      │  │   is active: "Distribution ignores ...")      │  │
│ │                      │  │  [p50][p95][p99][exemplar]   explanatory note │  │
│ └──────────────────────┘  └───────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

| Source | Query key | Fetcher → endpoint |
|---|---|---|
| `MetricsPage` (`useQuery`, `placeholderData: keepPreviousData`) | `['metrics/series', params]` where `params = { from, to, repositoryUrl }` **plus** `filterMetricId` + `filters` (the list) only while at least one filter is active **plus** `aggMetricId/agg` only while a non-sum aggregation is active | `fetchMetrics(params)` → `GET /api/metrics/series?from=…&to=…[&repositoryUrl=…][&filterMetricId=<id>&filter=<key>:<value>&filter=<key>:<value>…][&aggMetricId=<id>&agg=avg\|p95\|count]` |
| `MetricsPage` (second `useQuery`, `enabled: selectedMetric.hasDistribution`) | `['metrics/distribution', selectedMetric?.id, windowParams]` where `windowParams = { from, to, repositoryUrl }` | `fetchMetricDistribution({ ...windowParams, metricName })` → `GET /api/metrics/distribution?metric=<FULL name, e.g. claude_code.token.usage>&from=…&to=…[&repositoryUrl=…]` → `{ points: { ts, value, traceId \| null, spanId \| null }[] }` (ascending by `ts`, newest 2,000 requests, `traceId` only on a handful of server-chosen exemplars; `spanId` on those same exemplars when the request's `llm_request` span is ingested) |
| `MetricsPage` (third `useQuery`, `enabled: facetPickerOpen && Boolean(selectedMetric)`, no `placeholderData`) | `['metrics/facets', selectedMetric?.id, from, to, repositoryUrl]` | `fetchMetricFacets({ ...windowParams, metricName })` → `GET /api/metrics/attributes?metric=<FULL name>&from=…&to=…[&repositoryUrl=…]` → `{ attributes: { key, values: { value, count }[] }[] }`, unwrapped to `MetricFacet[]` (`[]` when nothing is filterable). The old `/api/metrics/series/facets` is gone |
| `MetricsPage` (fourth and fifth `useQuery`, `enabled: openTraceId !== null`, no polling) | `['trace-summary', openTraceId]` and `['trace-spans', openTraceId]` — deliberately the **same keys** `TraceDetailPage` uses, so "Open in Traces" afterwards is served from cache | `fetchTraceSummaryOrNull(traceId)` → `GET /api/traces/{traceId}/summary` (`null` on 404) and `fetchSpansForTrace(traceId)` → `GET /api/traces/{traceId}` (imported from `../TracesPage/tracesApi`, which carries the sample-data support). No new endpoint |

The filter group is **both or none** (`filterMetricId` plus one or more repeated `filter=key:value`
params, appended with `URLSearchParams.append` so values are percent-encoded), the aggregation group
**both or none**, and both are **metric-scoped**: the filters change only that metric's totals /
trend / splits / cardinality (its header stats, chart, breakdown and rail row all become filtered;
every other metric in the response is unchanged), and the aggregation changes only that metric's
`trend` array. `agg=sum` is never sent (omit both params for the default), and with no filters none
of `filterMetricId` / `filter` is sent. `fetchMetrics` adds each group to the query string only when
set. The distribution and facets endpoints address the metric by its full `name`, **not** its `id`
(the series endpoint's `filterMetricId` / `aggMetricId` still take the id).

`MetricCatalogRail`, `MetricFacetBar`, `MetricHeader`, `MetricTrendCard`,
`MetricBreakdown`, and `MetricDistributionCard` never fetch — they receive
`MetricSeries[]` (or a single `MetricSeries`, a `MetricDistribution`, a `MetricFacet[]`, or plain
split keys) as props from the view. The other `MetricsController` endpoints (`GET /api/metrics`,
`/api/metrics/catalog`, `/api/metrics/cost`) are not called by this page in its current form.

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
  fixture immediately without a network call, and `fetchMetricDistribution` return
  `buildMetricDistributionSample(metricName, from, to)` (deterministic; the points are placed
  inside the request's `from`/`to`, which the page also passes to the card for the x axis). The view
  also defaults `metrics` to `METRICS` when the prop is undefined, so the page renders meaningfully
  before the backend is available. **In sample mode the filter and Agg controls work but change
  nothing in the data:** `fetchMetrics` ignores `filters` / `agg*` and keeps returning the static
  fixture (so the chart keeps drawing the sum trend, just relabelled for a non-sum Agg), and the
  distribution is unfiltered as always. `fetchMetricFacets` returns
  `buildMetricFacetsSample(metricName)` (keyed by full name) so the picker is usable: token/cost
  `model` (3 values) + `terminal.type` (2), loc `type` (added/removed), decision `decision`
  (accepted/rejected), and `[]` for every other metric (their "+ Add filter" pill disables once the
  picker has loaded that empty answer). Clicking a sample exemplar opens the drawer for a fake trace
  id that does not exist: the stat row keeps the point's own value, and the waterfall shows the
  trace query's empty/error state (the drawer's loading, error and no-spans variants).
- **`selectedId`, the filters and the aggregation are lifted into the container; `split` and
  `search` stay in the view.** `MetricsPage` owns `selectedId` (`useState<string | undefined>`,
  starts undefined) because it needs the selected metric to decide whether to fetch a
  distribution / facets and which metric the filters or aggregation are scoped to:
  `selectedMetric = metrics?.find(id === selectedId) ?? metrics?.[0]`. `MetricsPageView` receives
  `selectedId` + `onSelectedIdChange` as a controlled prop and derives its own `selected` with the
  same first-metric fallback, so the two never disagree. It also owns `attributeFilters`
  (`AttributeFilter[]`, ANDed, at most one per key), `aggregation` (`MetricAggregation`, default
  `'sum'`) and `facetPickerOpen` (only there to gate the lazy facets fetch). The view still owns
  `split` (current breakdown key, or `'None'`) and `search` (the rail's query). Its `selectMetric`
  calls `onSelectedIdChange(id)` *and* resets the split to `'None'` so stale split labels never
  bleed across metrics; the **container's** `handleSelectedIdChange` (only when the id really
  changes — re-clicking the selected row is not a switch) clears **all** filters, resets the
  aggregation to `'sum'`, closes the picker and the exemplar drawer, so none of them ever leaks to
  another metric. The
  Split-by / Agg / filter UI lives in `MetricFacetBar`, but it only reports changes through
  callbacks — state ownership did not move with it. View tests render through a tiny stateful
  wrapper (`ControlledMetricsPageView`) that mimics the container's reset, since a bare view will
  not move its own selection.
- **The series `params` gain the metric-scoped groups only while they are active.** The container
  builds `windowParams` (from/to/repositoryUrl, memoized on the selection + repository) and derives
  `params` from it: `filterMetricId` + `filters` appear only while `attributeFilters` is non-empty,
  `aggMetricId/agg` only while the aggregation is not sum, both scoped to `selectedId`. The filters
  list is part of `params`, so it is part of the query key: adding, replacing or removing a chip is a
  new key and refetches. A plain metric switch with no filters/agg leaves the series query key
  byte-identical and does **not** refetch. The two `selectedId`-scoped groups must not read the
  series query's own data (that would be circular: data → selectedMetric → params → key → data), so
  `ensureMetricSelected` pins `selectedId` to the effective selection (the first-metric fallback)
  before a filter or non-sum aggregation is applied. The series query uses
  `placeholderData: keepPreviousData` (a filter/Agg change is a new key; without it the view would
  flash its sample-catalog default while the new series loads) and passes
  `isLoading || isPlaceholderData` down so the chart still says "Loading…". The distribution and
  facets queries deliberately use `windowParams`, not `params`: the distribution API has no
  filter/agg, so changing them must not refetch it.
- **Multiple ANDed filters, applied through a two-step picker; the bar is never hidden.**
  `MetricFacetBar` always renders while a metric is selected, and its left side
  (`AttributeFilterControl`) is a list: one chip per active filter (`key =` muted, value in
  primary, an x button with `aria-label` "Remove filter `<key>` = `<value>`" that removes just that
  chip), followed by the dashed "+ Add filter" pill, which is present with or without chips. The pill
  opens an MUI `Popover` holding a `MenuList`: step 1 lists the attribute keys, step 2 that key's
  values with their label-set counts (each item's `aria-label` is "`<value>, <n> active label-sets`")
  and a "Back to attributes" button. Picking a value appends a chip and closes the popover — via the
  pure `applyFilterSelection` (`attributeFilters.ts`), so picking a value for a key that **already has
  a chip replaces that chip in place** (two values of one key ANDed would match nothing; step 2 says
  "Choosing a value replaces `<key>` = `<value>`"), and the exact pairs already active are **not
  offered** (a key whose every value is active says "No other values in the window"). The container
  receives the whole next list through `onFiltersChange`. Escape and backdrop-click close the popover
  (`Popover`'s own handling), items are real menu items so arrow keys work, and loading / error
  states are small text inside it. Opening/closing calls `onFacetPickerOpenChange`, which is what
  makes the container fetch facets **lazily** (nothing is requested until the first open). Because
  facets are lazy, the pill cannot know a metric has none up front: it starts enabled, and once a
  load returns `[]` it becomes disabled (still visible, next to any chips) with the tooltip "No
  filterable attributes for this metric in the window" (an open popover shows the same sentence).
  The popover's anchor / step are local UI state; `MetricsPageView` `key`s the bar by metric id so a
  switch starts it closed. Selecting or removing a filter only changes state — the container
  refetches through the new key.
- **Agg semantics: non-sum aggregates only describe non-zero increments; headline stats stay
  sum-based.** `avg` / `p95` are the mean / 95th percentile of the individual non-zero data-point
  increments in each bucket and `count` is the number of them, computed by the backend into that
  metric's `trend` array only. The header stats (sum / rate / peak / delta), the splits and the
  cardinality are **unchanged and sum-based** whatever the Agg is. Consequently a non-sum Agg (a)
  forces the split to `'None'` **for rendering only** — `MetricsPageView` derives
  `split = aggregation === 'sum' ? storedSplit : 'None'`, so the stored split is never touched and
  switching back to sum restores the previous Model/Type stack — (b) disables the Group-by control
  (`role="group"` + `aria-disabled` + `inert`, dimmed; `SegmentedToggle` has no disabled state)
  with the tooltip "Averages and percentiles are not additive across groups - switch Agg back to
  sum to group", (c) makes `MetricTrendCard` draw one unstacked series named for the aggregate
  (legend `avg per data point` / `p95 per data point` / `data points per bucket`; y label
  `<unit>, avg per point` / `<unit>, p95 per point` / `points`, from `describeAggregation`; title
  suffix `(avg)`; never `(stacked)`) with an info tooltip saying the aggregate describes
  individual increments while the header stays sum-based, and (d) makes `MetricBreakdown` show its
  Summary with a note that it is sum-based. The card guards this too (`isSplitActive` requires
  sum), so it can never stack an average even if handed a split.
- **The distribution is not filtered.** While any filter is active `MetricsPageView` passes
  `ignoresAttributeFilter` to `MetricDistributionCard`, which adds one discreet line under its
  header ("Distribution ignores attribute filters.") in the populated, loading, error and empty
  variants (not the no-distribution placeholder). The rail sparklines and every other metric stay
  unfiltered/sum; only the selected metric's own rail row reflects its filtered trend/cardinality.
- **The distribution query is gated on the backend's `hasDistribution` flag.** `MetricSeries.hasDistribution`
  comes from `GET /api/metrics/series` and is backend-owned: the frontend never hardcodes which
  metric ids (`token`, `cost`) have a distribution. `MetricsPage`'s second `useQuery` has
  `enabled: Boolean(selectedMetric?.hasDistribution)`, the same `refetchInterval` rule as the
  series query (preset windows only), and **no `placeholderData`** — switching metrics must not
  show the previous metric's scatter under the new metric's name. Its `queryFn` sends the selected
  metric's full `name` (`metric=claude_code.token.usage`), not its id. The manual reload handler
  refetches the series query, the distribution query (guarded by the same flag) and the facets
  query (guarded by `facetPickerOpen && selectedMetric`), because `refetch()` ignores `enabled`
  and would otherwise fire a request for a metric that has no distribution / a picker nobody opened.
- **`MetricDistributionCard` never renders nothing.** Variants, in precedence order: no
  `hasDistribution` → a compact placeholder ("No per-request distribution is available for
  <name>. …"; the copy says "Distributions only make sense for metrics with a per-request value
  (tokens, cost)"); `errorMessage` → the message in the error color; `isLoading` or a
  still-undefined `distribution` → "Loading distribution…"; `points.length === 0` → "No requests in
  this window"; otherwise the populated card. The loading/error/empty variants keep the populated
  card's header and approximate height so the row does not jump. The header's right side reads "N
  requests" (singular for 1; "latest 2,000 requests" once N reaches `DISTRIBUTION_POINT_CAP`, the
  server's cap), plus " · log scale" for a token metric.
- **The scatter plots every request; the payload has no window, so the card is handed one.**
  `MetricDistribution` is just `{ points }`. `MetricsPage` passes `windowParams.from/to` to the view,
  which forwards them as `windowFrom`/`windowTo`; x is a point's real timestamp within that window
  (`timestampToFraction`, clamped to the edges), so idle time is a gap and a burst is a cluster. The
  SVG is measured in real pixels (a `ResizeObserver` on the container, 900px default — the same
  approach as `AreaTrendChart`; jsdom never resizes, so tests see 900px) rather than stretched with
  `preserveAspectRatio="none"`, which would squash the dots into ellipses. Plot padding is 58 left /
  18 right / 16 top / 30 bottom. Regular points are 3.2px `primary` dots at 0.38 opacity; a point with
  a non-finite value or an unparseable `ts` is dropped rather than drawn at a made-up position. All
  helpers are pure in `distributionScatter.ts`.
- **Percentiles are computed client-side.** `summarizeValues` sorts the points' values and takes the
  nearest-rank p50/p95/p99 (`computePercentile`); nothing percentile-shaped is in the payload. They
  draw as dashed horizontal lines (same colors as the chip swatches: `colorForIndex` 3 / 4 / 1) and
  as the chips' values (an em dash when there is no finite value).
- **The y axis is derived from the data, never hardcoded.** `buildValueScale(kind, values)`:
  **tokens use a log axis, cost a linear axis from 0** (`scaleKindFor(distributionUnitFor(metric.unit))`
  — `USD` is cost, everything else tokens). Linear: 0 to a 1/2/5 x 10^n step grid whose top tick is
  a nice round ceiling strictly above the largest value (at least 5% headroom, so the top point never
  sits on the edge). Log: the floor is the power of ten at or below the smallest **positive** value
  (clamped at 1e-12), the ceiling a 1/2/3/5/10 x 10^n figure at least 5% above the largest, ticks are
  each decade plus the ceiling; zero/negative values sit on the floor instead of at minus infinity.
  Empty or all-non-positive input yields a small well-formed default scale. `valueToFraction` clamps
  to [0, 1]. Tick labels: `formatCompact` for tokens; USD picks precision from the axis maximum
  (cents by default, three decimals below a $0.10 maximum, whole dollars from $100) so no float noise
  prints. The mockup's 256000 / 0.32 ceilings were sample-data artefacts and are not in the code.
- **x-axis labels come from the request window.** Five evenly spaced labels between `windowFrom` and
  `windowTo` — local `HH:mm` for windows of 48h or less, a short date (`Sep 12`) for longer ones. The
  last label reads "now" only when `to` is within 5 minutes of the current time, so a custom
  historical range shows its real end instead. (Unlike `MetricTrendCard`'s wall-clock-anchored axis,
  this one follows the request window exactly.)
- **Exemplars are real `<button>`s, and clicking one opens the trace peek drawer.** A point with
  a non-null `traceId` is drawn as a larger (14px) ringed dot — `background.paper` fill, `text.primary`
  border, a soft `primary` halo — absolutely positioned over the SVG in the same pixel space. Each has
  an `aria-label` ("Open trace for `<formatted value>` request") and a hover Tooltip (value, time,
  "click to open trace"); click and Enter/Space both call `onOpenTrace(traceId)`, which the
  container binds to `setOpenTraceId`. The payload does not say which exemplar is an error/worst
  request, so all exemplars share one style (the drawer's status badge is where an errored trace
  shows). The legend chip beside p50/p95/p99 is the same ringed swatch.
- **The exemplar drawer is a quick peek, not a second Trace Detail page.** It is a drawer on
  purpose: "click a point, glance at its trace, close, click the next" is the loop the scatter
  exists for, and a full navigation per glance breaks it. The container owns `openTraceId` and runs
  the two trace queries (see the API table); `MetricsPageView` builds one memoized `MetricExemplar`
  from them plus the **distribution point the dot was drawn for** (found by `traceId`), because the
  payload is only `{ ts, value, traceId }` — the request's own tokens/cost and its recorded time
  (the stat row's first figure and the context line) come from that point, while duration, span
  count, model and status come from the trace. Those are two different measurements (a request vs
  its whole turn), which is why the first stat is labelled "Tokens"/"Cost" beside a trace-level
  "Spans" and not summed into anything. The drawer keeps the last exemplar rendered through the
  slide-out by comparing the memoized object's identity in a guarded render-phase `setState`
  (cleared `onExited`) — un-memoizing that object in the view would make it loop. Its waterfall
  **reuses `SpanWaterfallRow`** with the token/cache/cost chip families hidden (`chipsOff`), the
  `call N` badge opted out (`showCallNumber={false}` — it is a trace-analysis citation, not a
  toggleable chip family, and nothing in the peek cites it) and no-op `onSelect`/`onToggleCollapse` (a CSS rule drops the pointer cursor; `hasChildren` is false so
  no chevron promises a fold), and shows only the root and its direct children — anything below,
  and anything past 40 rows, is summarized as "+N more spans" and left to the full page. Bars are
  scaled to the time extent of the **drawn** rows, not the whole trace: a turn can dispatch
  background work that runs for many minutes after it (a real 10.95 s turn sat inside a 1,462 s,
  793-span trace), and a window sized to cover spans the peek does not draw squashed every bar into
  a 3px sliver at the left edge. The
  attributes block is `AttributeList` over what is actually known (`trace.id`, the request's
  `request.tokens`/`request.cost_usd`, `request.timestamp`, and `session.id`/`root.span` once the
  summary loads) — the mockup's `model`/`terminal.type` rows were metric label-sets the payload
  does not carry. **"Open in Traces"** (the footer CTA) is the additive hand-off:
  `navigate('/traces/<traceId>?span=<spanId>')`, where the exemplar point's `spanId` is the request's own
  `llm_request` span, so Trace Detail lands with that span selected instead of at the top of the trace
  (see that page's CLAUDE.md for the `?span=` deep link). A null `spanId` (span not ingested yet, or an
  older backend that omits the field) drops the query and opens the plain trace. A metric switch closes the drawer
  (`handleSelectedIdChange`), since the exemplar belongs to the old metric's distribution.
- **Two pipelines, one honest note.** The distribution is built from per-request `api_request` log
  records; the header's Sum and the trend chart come from the cumulative metric counters. The two
  do not reconcile (see the repo AGENTS.md on spend), so the card's footer says so: "Built from
  per-request API logs, so totals here will not match the counter-based Sum above." Keep that
  sentence if you edit the footer copy. The rest of the footer is the mockup's copy: "Each dot is one
  real request, plotted at its own time and value — gaps are simply idle time, not missing data.
  Ringed dots are exemplars — click one to open that request's trace."
- **Search filters the rail only, never the selection.** `MetricCatalogRail` filters its own rows
  by a case-insensitive substring of `metric.name` (the fully-qualified name, so `claude_code`
  matches everything). If the selected metric's row is filtered out, it stays selected and the
  detail pane keeps showing it; clearing the search brings its row back highlighted. The rail also
  owns the grouping (`groupLabelOf` currently returns the one constant `'Claude Code'`, so there is
  a single group header whose count chip reflects the rows left after filtering); it hides the
  group header entirely when no row matches and shows "No metrics match" instead.
- **`MetricHeader`'s Series/Cardinality/Health stats are real fields off `MetricSeries`, not
  derived styling.** `cardinality` (a **number**, e.g. `1234`) and `health`
  (`'ok' | 'warn' | 'bad'`) come straight from `GET /api/metrics/series`; the number is formatted
  for display with `lib/format`'s `formatCompact` (`1234` -> `"1.2K"`) in both `MetricHeader` and
  the rail row (never pre-formatted server-side or in a fixture), and `MetricHeader` looks up
  `health`'s color/copy via `components/metricHealth.ts`
  (`healthColor(health, theme)` reads the theme's `success`/`warning`/`error` palette tokens,
  `HEALTH_LABEL[health]` is the full tooltip copy, truncated to the part before the `·`/`—`
  separator for the compact stat). `seriesCount`/`seriesUnitLabel`, by contrast, *are* derived —
  `MetricsPageView` computes them from `split` and `selected.splits[split]` (1 + `"series"` with
  no split active, else the active split's row count + its lowercased, pluralized key, e.g.
  `"3 models"`) and passes both as props so `MetricHeader` stays a pure formatter.
- **The rail is a scrolling list of uniform rows, not tiered by importance.** `MetricCatalogRail`
  renders every metric — curated or discovered (including one whose id is its dotted name with
  dashes, `claude_code-commit-count`) — as the same row: type badge, display name, health dot, a
  `LineSparkline`, and the cardinality figure. The layout therefore never requires a decision when
  the backend appends a new counter; it lands as the next row, and the card's `maxHeight` (780) with
  an inner scroll keeps a long list from stretching the page. Nothing assumes a fixed metric count.
  Display names strip the `claude_code.` prefix only when present (a discovered metric such as
  `some.other.metric` keeps its whole name). Selection handling is the same as the old strip's
  cards: `role="button"`, `tabIndex`, Enter/Space, `aria-pressed`, and an accent ring +
  `action.selected` background on the selected row. The type badge's color comes from
  `metricTypeColor`, the health dot's from `healthColor` (title/aria-label from `HEALTH_LABEL`);
  the cardinality text (`"<compact n> ser"`, e.g. `"1.2K ser"`) tints to the warning/error color when
  `health` is not `'ok'`.
  The explorer grid is `286px 1fr` from `md` up (single column below); because the rail takes a
  column, the trend + breakdown pair inside the detail column only goes two-up at `xl`.
- **Sparse whole-number counters draw as bars, in the card and the chart.** `isSparseCounter`
  (`lib/format.ts`) is true when a trend's peak is ≤ 5 and every value is an integer; both
  `MetricTrendCard` (switches `AreaTrendChart` from an interpolated area to per-bucket bars, steps
  the y-axis by whole numbers, and adds a bucket of headroom above the peak) and `LineSparkline`
  (switches the catalog rail row's sparkline the same way) import the one predicate, so a row never
  shows bars while its chart shows an area. It's a threshold, not a per-metric flag, so a discovered
  counter gets it without a spec. `commit.count` and `pull_request.count` hit it; `session.count`
  does not (its bucket values are fractional), so it keeps the area/line form.
- **Unit-less metrics need a guard.** `pull_request.count` is the first curated metric with an
  empty `unit`. `MetricHeader`'s unit badge prints `metric.unit || '—'` so the badge keeps its pill
  shape instead of collapsing to 6px. (The rail rows don't print the unit at all, so they need no
  guard; `MetricTrendCard`'s `yUnit`/legend strip `{}` from the unit and tolerate an empty string.)
- **Chart series derivation.** When `split === 'None'` (or the aggregation is non-sum) the chart
  renders one series (the metric's raw `trend` array — the per-bucket aggregate when Agg is not
  sum). When a split is active, `MetricTrendCard` maps each `MetricSplitRow` to a
  scaled series: `data[i] = trend[i] * row.pct / 100`. Colors come from
  `colorForIndex(row.colorIndex)` so all split series stay on the shared dashboard palette.
- **x-axis is wall-clock anchored.** `axisDates` is derived inside `MetricTrendCard` from
  `Date.now()` at render time and the length of `metric.trend` — 24 points spaced evenly over
  24 h. It is NOT tied to the `from`/`to` params, so the axis is an approximation of the window
  rather than an exact label.
- **The Split-by `SegmentedToggle` (and its ⓘ tooltip) live in `MetricFacetBar` and appear only
  when `splitKeys.length > 1`** (i.e. the selected metric has at least one attribute split) — both
  gated by the same `hasSplits` flag, since the tooltip explains what the toggle does and has
  nothing to say without it. Metrics without splits (`session.count`, `active_time.total`,
  `commit.count`, `pull_request.count`, and every discovered metric) omit that group, but the bar
  itself always renders while a metric is selected because the Filter and Agg controls apply to
  every metric (it used to return `null` for them, back when Split by was its only content; the
  bar is not rendered at all when there are no metrics). The Split-by tooltip copy describes both
  stacking modes (split set = stacked bands summing to the total; None = one total series) and
  pairs with `MetricTrendCard`'s derived `(stacked)` y-axis label — keep the two in step per
  frontend/CLAUDE.md's "Stacked chart labeling conventions". `MetricTrendCard` itself no longer
  receives `splitKeys`/`onSplitChange`; it just reads `split` and `aggregation`. The Agg control is
  the ordinary `SegmentedToggle` (sum / avg / p95 / count, default sum); it replaced the disabled,
  illustrative "Agg" pill from the design mockup — it is real now.
- **`MetricBreakdown` doubles as two cards in one.** With `split === 'None'` — or any non-sum
  aggregation, which the view already renders as `'None'` — it shows a compact summary panel (with
  a "this summary is sum-based" note under a non-sum Agg). With a split active it passes
  `BreakdownRow[]` (derived from `MetricSplitRow`) to the shared `BreakdownList` component with
  `layout="stacked"`.

## Gotchas

- `metricsApi.ts` defines its own `getJSON` helper rather than importing from `api/http.ts`.
  If the shared transport changes (auth headers, error shape), this file must be updated
  separately.
- `MetricSeries` and `MetricSplitRow` are defined in `components/metricsSampleData.ts`, not in
  `metricsApi.ts`. The sub-components import from `'../metricsSampleData'`; `metricsApi.ts`
  re-exports the type. Keep the type source in `metricsSampleData.ts` until the API is stable
  enough to own the shape.
- `VITE_METRICS_SAMPLE=1` returns static data for the eight curated metrics immediately, ignoring
  the filter / aggregation params (see "Sample data fallback"); the
  `isLoading` and `error` paths are therefore untested without a live backend. The chart renders a
  `Loading…` placeholder only when `isLoading === true`. Sample mode has no fixture for a
  *discovered* metric, so that path only exercises against a real database. The distribution
  card's loading/error/empty variants *are* covered by view tests, from props alone.
- `MetricsPageView.test.tsx`'s existing `getByText('claude_code.token.usage')`-style lookups had to
  become presence/absence helpers (`expectMetricNameShown`/`Hidden`): the fully-qualified name now
  appears in the header *and* in the distribution card's title (or its placeholder copy). Likewise
  the Agg control's `p95` button shares its text with the distribution card's `p95` chip, so the
  chip lookups pin `{ selector: 'span' }`. The scatter's own tick labels are looked up
  `within(screen.getByRole('img', { name: 'Per-request scatter plot' }))` because the trend chart
  above draws numeric ticks of its own.
- The filters and aggregation are scoped by the API to one metric id, and `selectedId` is what
  scopes them — never derive that id from the series query's own data (circular). See "The series
  `params` gain the metric-scoped groups only while they are active".
- The distribution and facets fetchers take `metricName`, the series filter/agg params take
  `metricId`. Passing the id to the former is a 400 or an empty answer, not a type error, since both
  are strings.
- A small point set is normal: a metric with few requests draws few dots, and the percentile lines
  still compute (a single point puts all three on it).
- The `'metrics/series'`, `'metrics/distribution'` and `'metrics/facets'` query keys use a
  forward-slash, unlike the
  `'kebab-feature'` convention used by other pages. If you refactor, also update `MetricsPage`'s
  manual reload handler — it calls each hook's `refetch()` directly, not
  `queryClient.invalidateQueries`, so the key mismatch would only matter if a section-level reload
  predicate is added later.
- **Repository attribution (2026-09).** This page fell through the cracks of the repository-attribution
  rollout: `GET /api/metrics/series` had no `repositoryUrl` parameter at all — a backend gap, not just
  a frontend one, unlike `TokensPage`'s (see that page's own note) — so `MetricsPage` never rendered the
  repository selector. Fixed by adding `repositoryUrl` to `MetricsController#metricSeries`,
  `MetricSeriesService#metricSeries`, and the three batched `MetricPointRepository` queries it drives
  (`aggregateMetricTotals`, `aggregateMetricTrend`, `aggregateMetricSplits`), each gaining the standard
  `(:repositoryUrl IS NULL OR repository_url = :repositoryUrl)` clause. `MetricsPage` now reads
  `repositoryUrl`/`setRepositoryUrl` from `useWindowContext()`, folds `repositoryUrl` into the
  `windowParams` `useMemo` (which `params` extends, so it rides every query key with no separate `:repository:` suffix needed, unlike
  pages with a hand-built `selectionKey`), and passes both through to `MetricsPageView`, which threads
  them into `PageActions`.
