# Metrics Explorer — implementation handoff

Replaces the simplified `MetricsPage` (KPI strip + trend + breakdown) with the
original "Explorer" design: a searchable metric catalog, a facet bar
(filter / group-by / agg) that's **always visible**, a detail header with
series/cardinality/health, a stacked trend chart, a top-N breakdown, and a
per-request distribution **scatter plot** with click-to-trace exemplars. All
**eight** curated metrics are covered, including `commit.count` and
`pull_request.count`, which the original mockup was missing.

Mockup: `Metrics Explorer Mockup.html` (open directly, toggle light/dark,
click any catalog row, toggle Group by, add/remove a filter chip, click an
exemplar dot on the token.usage or cost.usage distribution plot).

**Revision note:** the distribution card was originally an hourly
heatmap+percentile-band overlay (matching the reference design). Real usage
is bursty and low-volume (a personal CLI tool, not continuous API traffic),
so most hourly buckets were empty and the percentile lines fragmented
between the few populated ones. It's been replaced with a per-request
scatter plot — each dot is one real request at its own time and value, so
idle gaps just look like gaps. See "Distribution card" under Backend changes
for what this means for the data contract.

## Why this is bigger than a frontend restyle

The shipped `MetricsPage` was deliberately descoped from this Explorer
concept (see `MetricsPage/CLAUDE.md`). Two fields this design needs —
**cardinality** and **health** — don't exist anywhere in the current
`MetricSeries` API response. The heatmap/exemplar drawer needs per-request
histogram + trace-sampling data that today's three aggregation queries
don't produce. This is a real frontend **and** backend project, not a
component swap.

## Frontend changes

**Header actions parity (already reflected in the mockup).** The page header's
action row must match `PageActions`' current order: window selector →
repository selector → reload → auto-refresh. The mockup's header row was
originally missing the `RepositorySelector` entirely (`MetricsPage` gained
`repositoryUrl`/`onRepositoryUrlChange` in the repository-attribution rollout
— see `MetricsPage/CLAUDE.md`'s note). It's now wired in: a folder-icon pill
labeled "All repositories" sits between the window pill and refresh. Also
note `PageLayout`'s real structure keeps the title+actions row and the
subtitle on separate lines — the actions row must stay pinned beside the
title, not wrap below a long subtitle.

New components (under `MetricsPage/components/`, following the existing
container/view split):

- **`MetricCatalogRail`** — search input + grouped list (today one group,
  "Claude Code"; the discovered-metrics group from `MetricKpiStrip` still
  applies here). Each row: type badge, name, health dot, sparkline
  (reuse `LineSparkline`'s sparse/dense branching), cardinality figure.
  Replaces `MetricKpiStrip`'s grid with a scrollable list — same selection
  contract (`selectedId` / `onSelect`).
- **`MetricFacetBar`** — filter chips + Group-by + Agg, rendered
  unconditionally at the top of the page for every metric (never hidden or
  collapsed — see "How the filter bar works" below for what changes
  per-metric inside it). Group-by should drive the same state
  `MetricTrendCard`'s `split` prop drives today (its options are just
  `['None', ...Object.keys(metric.splits)]`) — this control replaces
  `MetricTrendCard`'s inline `SegmentedToggle`, it doesn't duplicate it.
  Agg has no backend support yet (see below) — build the control disabled,
  as the mockup does. Filter chips DO need to be interactive now (see
  below), not illustrative.
- **`MetricDistributionCard`** — scatter plot (one dot per request, plotted
  at its real timestamp + value) + dashed p50/p95/p99 reference lines +
  clickable ringed exemplar dots, each opening the **exemplar trace
  drawer** (see its own section below — it's a real component, not a
  placeholder for a `TraceDetailPage` navigation). Render this card only
  when the selected metric has a natural per-request value — start with
  `['token', 'cost']`, not all eight. The mockup shows the gate explicitly
  (`hasDist` flag) with a "no distribution available" placeholder for the
  rest — keep that placeholder in the real build so users don't wonder why
  the card disappeared.

### How the filter bar works

The bar itself (`Filter` label, chip row, Group-by, Agg) is always in the
DOM and always visible, regardless of which metric is selected or whether
that metric has any filterable attributes — only the *contents* of the chip
row change:

1. **Adding a filter.** Click "+ Add filter" → a two-step picker opens: first
   the attribute keys available for the selected metric (e.g. `model`,
   `terminal.type` for `token.usage`), then that key's known values. Picking
   a value adds a chip (`key = value`) to the bar and closes the picker.
2. **Removing a filter.** Click the `×` on any chip to remove just that
   filter; the rest stay active.
3. **Multiple filters AND together** — picking `model = claude-opus-4` then
   `terminal.type = vscode` narrows to requests matching both, mirroring how
   `LogsPage`'s facet rail composes filters.
4. **No filterable attributes for this metric.** `session.count`,
   `active_time.total`, `commit.count`, and `pull_request.count` have no
   attribute dimensions today, so their "+ Add filter" renders disabled with
   a tooltip explaining why — it's still visible, just inert, rather than
   disappearing (consistent with point 0 above).
5. **Switching metrics clears active filters** (same reset `selectMetric`
   already does for `split`) — an attribute like `model` doesn't necessarily
   apply the same way to a different metric's series, and a stale chip that
   silently stops filtering is worse than one that's visibly gone.
6. **What's illustrative vs. real.** The mockup's attribute/value lists
   (`FILTERABLE_ATTRS`) are hardcoded placeholders standing in for
   `GET /api/metrics/attributes` (doesn't exist yet — see Backend changes).
   Adding/removing a chip in the mockup only updates the chip row; it does
   not refetch or recompute the sum/chart/breakdown, since there's no real
   query underneath it yet. In the real build, an active filter must be
   threaded into whichever query key drives `MetricHeader`/`MetricTrendCard`/
   `MetricBreakdown` so the whole detail pane reflects it, not just the bar.
- **`MetricHeader`** — extend with three new stats: Series (row count of
  the active split, or 1), Cardinality, Health (dot + label, `Tooltip`
  with the full `ok`/`warn`/`bad` copy from the mockup's `HEALTH_LABEL`).

Data model (`metricsSampleData.ts`):

```ts
export interface MetricSeries {
  // ...existing fields
  cardinality: number;       // distinct active label-sets in the window
  health: 'ok' | 'warn' | 'bad';
}
```

`commit.count` and `pull_request.count` need no special-casing beyond what
already exists — `isSparseCounter`'s bar rendering already covers them in
both the catalog sparkline and the trend chart. Give them a `cardinality`
of their own commit/PR count and `health: 'ok'` (low-volume, not
attribute-exploding).

## Exemplar trace drawer

Clicking a ringed dot on the distribution scatter opens a right-side drawer
(the mockup's `#tdrawer`/`openTrace()`) — a *quick peek* at that one
request's trace, not a rebuild of the full Trace Detail page. Keep it a
drawer: forcing a full page navigation for a one-glance check breaks the
"click a point, see its trace, close, click the next point" exploration
loop the distribution card exists for. It's a genuinely separate component
from `TraceDetailPage`, not a stand-in for it.

**Frontend component** — `MetricExemplarDrawer` (or reuse the visual shell
`SessionsPage`'s detail drawer already establishes, if one exists — check
before building a third drawer chrome in this codebase):
- Header: eyebrow ("Exemplar → Trace"), trace id, close button.
- Context line: which metric + window this exemplar was sampled from.
- Stat row: the 4-5 headline figures (tokens/cost, duration, span count,
  model, status badge) — mirrors `SummaryStrip`'s KPI-row pattern already
  used on `TraceDetailHeader`, at a smaller scale.
- **Span waterfall** — do not hand-roll bars again. Import the existing
  `SpanWaterfallRow` rendering (or extract its bar-geometry helper if the
  full row component carries too much chrome for a compact list) so a
  span's color-by-kind and duration-bar math stays in one place. The mockup
  hand-draws this because it has no real component to import; the real
  build does.
- Attribute list — reuse `AttributeList` (already shared by Logs, Sessions,
  Span Inspector) instead of the mockup's bespoke `.attr-row` markup.
- Footer CTA: **keep** "Open in Traces" — this is the deliberate hand-off
  point to the full page (`navigate('/traces/' + traceId)`), for when a
  quick peek isn't enough. It's additive to the drawer, not a replacement
  for it.

**Backend data** — don't build a new endpoint first. `TraceDetailPage`
already calls `GET /api/traces/{traceId}/summary` (query key
`['trace-summary', traceId]`, via `fetchTraceSummaryOrNull`) for a
lightweight trace summary — check whether its shape already covers the
drawer's stat row + waterfall. If it's missing span-level rows, `GET
/api/traces/{traceId}` (full spans, `['trace-spans', traceId]`) is the
fallback, trimmed client-side to the top-level spans the compact waterfall
needs. Either way this is **reusing an existing endpoint**, not new
backend work — the only real gap is upstream of the drawer: distribution
points need a `traceId` attached to the exemplars they mark (see
"Distribution card" §3 above).



`MetricsController` already declares four endpoints the current page never
calls: `GET /api/metrics`, `/api/metrics/catalog`, `/api/metrics/cost`,
`/api/metrics/distribution`, `/api/metrics/attributes`. **Read these first**
— they may already return some of what this design needs (catalog metadata,
distribution buckets, attribute facets) from an earlier, half-finished pass
at this same Explorer concept. Don't build a parallel endpoint before
confirming these are empty shells. The contracts below are what each one
needs to satisfy this design if it has to be built from scratch.

### 1. `GET /api/metrics/series` — add cardinality, health, filters

Extend the existing endpoint (backs `MetricCatalogRail` + `MetricHeader`).

Request — new optional param:
```
GET /api/metrics/series?from=...&to=...&repositoryUrl=...
    &filter=model:claude-opus-4&filter=terminal.type:vscode
```
Repeated `filter=key:value` params, ANDed together. Threaded into
`aggregateMetricTotals`, `aggregateMetricTrend`, and `aggregateMetricSplits`
as a JSONB containment predicate (`attributes @> :filterJson`), the same
`(:param IS NULL OR ...)`-style optional clause the repository-attribution
rollout used for `repositoryUrl`.

Response — two new fields per metric:
```jsonc
{
  "id": "token",
  "name": "claude_code.token.usage",
  // ...existing fields unchanged...
  "cardinality": 1234,       // distinct attribute label-sets in the window
  "health": "ok"             // "ok" | "warn" | "bad", server-computed
}
```
`cardinality`: `SELECT COUNT(DISTINCT attributes) FROM metric_points WHERE
name = :metric AND ts BETWEEN :from AND :to [AND repository_url = ...]` (or
a precomputed rollup if that's too slow live). `health`: a derived
classification, not stored — needs a product decision on thresholds (the
mockup's placeholder rule: flag `warn`/`bad` when a metric's attribute set
grows unbounded per request, e.g. `lines_of_code.count`'s file paths). Both
computed in `MetricSeriesService`, not the frontend — the frontend only
renders whatever enum/number it's given.

### 2. `GET /api/metrics/attributes` — new, powers the filter picker

```
GET /api/metrics/attributes?metric=claude_code.token.usage&from=...&to=...&repositoryUrl=...
```
Response:
```jsonc
{
  "attributes": [
    { "key": "model", "values": [
      { "value": "claude-sonnet-4", "count": 812 },
      { "value": "claude-opus-4", "count": 340 },
      { "value": "claude-haiku-3.5", "count": 90 }
    ]},
    { "key": "terminal.type", "values": [
      { "value": "vscode", "count": 1100 },
      { "value": "iterm2", "count": 142 }
    ]}
  ]
}
```
Distinct attribute keys + their distinct values (with counts, so the picker
can show frequency) scoped to one metric name + window. `MetricFacetBar`
calls this when "+ Add filter" opens, and again whenever the key step is
re-entered for a different metric. An empty `attributes` array is exactly
the "no filterable attributes" case `renderFacetChips` already handles —
the frontend doesn't need a separate signal for that.

### 3. Distribution card — per-request points, not histogram buckets

Because the card is now a scatter plot instead of a binned heatmap, the
backend contract is *simpler* than the original heatmap design would have
needed: no bucketing or server-side percentile math required.

```
GET /api/metrics/distribution?metric=claude_code.token.usage&from=...&to=...&repositoryUrl=...
```
Response:
```jsonc
{
  "points": [
    { "ts": "2026-09-19T08:12:40Z", "value": 13180, "traceId": null },
    { "ts": "2026-09-19T11:47:13Z", "value": 27340, "traceId": "7b22...1f019" }
  ]
}
```
One row per request in the window: its timestamp, its value (tokens or
cost), and a `traceId` **only** for the subset the backend has chosen as
exemplars (everything else: `null`). The frontend computes p50/p95/p99 and
plots every point client-side — this is the same shape either raw
`api_request` log rows or a sampled subset of them would already have, so
this may not need a *new* query at all, just a new endpoint over
`TokensPage`/`CostPage`'s existing per-request data. Cap the row count
(e.g. last 2,000 requests) rather than returning the whole window
unbounded. Scope to `token.usage` and `cost.usage` first — the other six
metrics aren't naturally per-request.

Exemplar selection: pick a handful of representative/outlier points per
window (e.g. the max, a few percentile-adjacent ones, and any request with
an error) and set their `traceId` — this is the OTel "exemplar" pattern. If
per-request rows don't already carry a `trace_id`, that has to come from a
join against the trace/span table on request timestamp, not new ingestion
work (unlike the original heatmap design, which needed ingestion to start
attaching trace IDs to `metric_points` directly).

### 4. Agg switching — lower priority

Sum is the only aggregation today. `avg`/`p95`/`count` need the SQL
aggregate to become a parameter across all three `MetricSeriesService`
queries. Ship Group-by and filters first; leave the Agg control disabled
(as the mockup does) until this lands.

## Suggested sequencing

1. Cardinality + health (backend + `MetricHeader` stats) — self-contained,
   no new endpoints.
2. Catalog rail + facet bar's Group-by (frontend-only, reuses existing
   `aggregateMetricSplits` data — just a new picker UI over the same split
   state `MetricTrendCard` already has).
3. `/api/metrics/attributes` + wiring real filter chips into the series
   queries — the filter *bar* ships from day one (always visible, per
   "How the filter bar works"), but it's illustrative-only until this API
   exists.
4. Distribution card for `token`/`cost` only — audit the unused
   `/api/metrics/distribution` endpoint first; per §3 above this is a
   per-request point list, not a histogram, so it may be cheaper than the
   original heatmap design implied.
5. Agg switching — last, needs a product call on which aggregates are
   meaningful per metric.
