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
# Trend Report page

Before/after diff view: compares the selected window (`current`) against the immediately
preceding window of equal length (`previous`) across eleven metrics grouped into four sections
(Cost, Token efficiency, Reliability, Activity). Answers "is usage trending more efficient" at a
glance — cost down, cache reuse up, tool errors down — rather than a single-window snapshot like
every other top-level page. Each of the four sections fetches independently from its own
endpoint (`GET /api/trends/cost`, `/api/trends/token-efficiency`, `/api/trends/reliability`,
`/api/trends/activity` — see `design_handoff_trend_report/BACKEND_API.md` for the original
single-endpoint proposal this page was built against — the live contract uses camelCase field
names, see `trendReportApi.ts`) so a slow or failed section doesn't block its siblings from
rendering.

## Files

```
TrendReportPage/
├── TrendReportPage.tsx        container — window context, one useQueries call (one query per
│                              TREND_SECTIONS entry), selectionKey/refetchInterval wiring
│                              (mirrors MetricsPage.tsx's window/auto-refresh pattern)
├── TrendReportPageView.tsx    view — period bar, section headers + metric rows, summary strip;
│                              no queries, no context; renders each section from its own
│                              independent sections[section.key] state
├── TrendReportPageView.test.tsx  vitest coverage for the view (renderWithProviders, prop fixtures)
├── trendReportApi.ts          TrendPeriod/TrendMetric/TrendReport types + fetchTrendSection()/
│                              resolveTrendReportSelection(); page-local getJSON helper
│                              (mirrors MetricsPage/metricsApi.ts)
├── trendReportApi.test.ts     vitest coverage for resolveTrendReportSelection's day-snapping
├── trendReportDerivations.ts  pure derivations: TREND_SECTIONS, METRIC_LABELS,
│                              formatMetricValue, computeDelta, computeBeforeSharePct,
│                              formatPeriod/formatComparingFromDate, describeWindowSpan,
│                              buildSummaryCallouts
├── trendReportDerivations.test.ts   vitest coverage for the above
├── components/
│   ├── MetricRow/             one before/spine/after row (value+sub+sparkline each side,
│   │                          DeltaBadge + ratio bar in the center)
│   ├── MetricRowSkeleton/     loading placeholder for one MetricRow — same grid/cell sizes,
│   │                          MUI Skeleton in place of every value/label/sparkline/badge
│   └── SectionHeader/         full-width section label row with a tinted icon chip
└── index.ts
```

## Visual layout

```
┌─ PageLayout: eyebrow "Reports" / "Trend Report" + "Comparing from <date>" pill ────────────┐
│ subtitle: "Side-by-side diff of the <window> before vs. after…"    [PageActions: ⟳ auto]  │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│ ┌─ Paper (diff card, column washes behind everything) ──────────────────────────────────┐ │
│ │ BEFORE Aug 15–21          vs →           AFTER Aug 22–29 (primary-colored)             │ │
│ │ ● COST                                                                                  │ │
│ │  $612.40 / total spend    Total cost  [↓34%]      $404.18 / total spend (green)         │ │
│ │  (sparkline, muted)        ▓▓▓▓░░░░                (sparkline, green)                   │ │
│ │  … 2 more Cost rows                                                                     │ │
│ │ ⚡ TOKEN EFFICIENCY … 3 rows                                                             │ │
│ │ 🛡 RELIABILITY … 3 rows                                                                  │ │
│ │ 📈 ACTIVITY … 2 rows                                                                     │ │
│ │ ┌─ summary strip (3 derived callouts: Cost / Reliability / Volume) ──────────────────┐  │ │
│ └───────────────────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

One `useQueries` call in the container builds one independent query per `TREND_SECTIONS` entry:

| Section | Query key | Fetcher → endpoint |
|---|---|---|
| Cost | `['trend-report', 'cost', selectionKey]` | `fetchTrendSection('cost', selection)` → `GET /api/trends/cost?…` |
| Token efficiency | `['trend-report', 'tokenEfficiency', selectionKey]` | `fetchTrendSection('tokenEfficiency', selection)` → `GET /api/trends/token-efficiency?…` |
| Reliability | `['trend-report', 'reliability', selectionKey]` | `fetchTrendSection('reliability', selection)` → `GET /api/trends/reliability?…` |
| Activity | `['trend-report', 'activity', selectionKey]` | `fetchTrendSection('activity', selection)` → `GET /api/trends/activity?…` |

`selectionKey` follows the same `preset:<minutes>` / `custom:<start>:<end>` pattern as every other
page (computed from the *raw* selection, not the day-snapped one below — see the gotcha), and is
shared across all four query keys — only the section-key segment differs. `TREND_SECTIONS` is a
compile-time constant of fixed length 4, so mapping it into `useQueries`' array doesn't violate
rules-of-hooks. Every response shares the same `TrendReport` shape (`current`/`previous`/`metrics`);
each endpoint just returns a subset of the eleven metric keys. `MetricRow` and `SectionHeader` never
fetch — the view derives each section's rows from that section's own resolved `metrics` bundle.

- **Windows over 24 hours are snapped to whole calendar days before the request is sent.**
  `trendReportApi.ts`'s `resolveTrendReportSelection` runs inside `fetchTrendSection`, ahead of
  `windowQueryParams`, identically for all four sections: a preset or custom selection spanning
  more than 24 hours is converted to an explicit `custom` selection whose
  `startTimestamp`/`endTimestamp` land on 00:00:00.000 / 23:59:59.999
  of their calendar day, in the **browser's local timezone** (this app runs on the operator's own
  workstation, not a server whose timezone the browser can't assume). "Last 7 days" therefore
  becomes the last 7 full calendar days ending today, not "7×24h before this exact instant" — a
  window like Aug 24 00:00–Aug 30 23:59:59.999 reads unambiguously as seven whole days, and the
  backend's 7-point sparkline buckets land on clean day boundaries instead of splitting a day
  mid-afternoon. A window of 24 hours or less is left as an exact rolling instant (day-snapping a
  1-hour comparison would blow it up into an all-day one) — this is also why `total_cost`'s "1 hour
  vs. the previous hour" case from earlier in this page's history still shows real hour-level
  before/after values rather than two overlapping all-day totals.

## Data flow and semantics

- **11 contract metrics map 1:1 onto 4 fixed sections** (`TREND_SECTIONS` in
  `trendReportDerivations.ts`): Cost (`total_cost`, `cost_per_session`, `blended_rate_per_1m`),
  Token efficiency (`cache_read_ratio_pct`, `tokens_total`, `tokens_per_session`), Reliability
  (`tool_errors`, `error_rate_pct`, `session_failures`), Activity (`sessions`,
  `avg_duration_min`). A test in `trendReportDerivations.test.ts` asserts every metric key
  appears exactly once across the four sections — if the backend adds a twelfth metric, that
  test starts failing until it's placed in a section (and `METRIC_LABELS`/`formatMetricValue`
  extended), rather than the row silently never rendering.
- **Good/bad/flat is computed entirely from `before`/`after`/`directionIsGoodWhen`** —
  `computeDelta` never hardcodes which direction is "good" for a metric name. `cache_read_ratio_pct`
  and `error_rate_pct` are the two metrics already expressed as percentages, so their delta is
  measured in percentage points (`after - before`, e.g. "9.2pt") rather than percent-change; every
  other metric uses ordinary percent-change. A move under `FLAT_DELTA_THRESHOLD` (2, picked to
  absorb ordinary week-to-week noise) classifies as `'flat'` regardless of `directionIsGoodWhen`.
- **`computeBeforeSharePct`** feeds the center ratio bar's two-tone split — `|before| /
  (|before| + |after|)`, both sides treated as magnitudes since every metric here is
  non-negative in practice. An all-zero row splits 50/50 rather than collapsing to zero width.
- **Column washes are ONE overlay behind the whole card, not per-row `bgcolor`.** `TrendReportPageView`
  renders a single absolutely-positioned `Box` (`aria-hidden`, `pointerEvents: 'none'`) using the
  same `1fr 160px 1fr` grid template as the period bar and every metric row, with the neutral
  wash (`theme.custom.rowStripe`) in the left column and a faint primary wash
  (`alpha(theme.palette.primary.main, 0.05)`) in the right column. The period bar, section
  headers, and `MetricRow` all render in normal flow above it (`zIndex: 1`) and paint no
  background of their own for the before/after cells — see `MetricRow`'s own comment. Don't add
  a per-row `bgcolor` back; two adjacent rows painting the same color independently is visually
  identical until a future redesign wants a different wash per section, at which point the
  single-overlay approach would need revisiting anyway.
- **Summary strip is derived, not hardcoded.** `buildSummaryCallouts` reads `total_cost` (from
  Cost), `tool_errors` (from Reliability), and `sessions` (from Activity) off a merged metrics
  object — `TrendReportPageView` spreads `sections[section.key].data?.metrics` for every section
  in `TREND_SECTIONS` order before calling it — and generates three short sentences (Cost /
  Reliability / Volume) from their actual before/after figures and `computeDelta` classification
  — never the reference design's example copy ("driven by cache efficiency and model-mix changes"
  etc., which describes data this page doesn't have). Because the merge only pulls in whatever
  sections have resolved so far, callouts appear incrementally as their owning section's request
  completes rather than waiting for all four; a metric missing from a degraded response just
  drops its callout instead of throwing, and the strip section renders nothing at all when
  `summaryCallouts` is empty (e.g. before any section's first successful fetch).
- **Section icon accent colors** map to `theme/colors.ts`'s `auroraColors` hues (cost = `gold`,
  activity = `cyan`) or existing theme tokens (token efficiency = `theme.palette.primary.main`,
  reliability = `theme.palette.success.main`) — chosen in `TrendReportPageView`, not hardcoded
  hex, per the repo-wide "no literal color in a component" rule.
- **`DeltaBadge`** (`components/DeltaBadge/`, not page-local) is a new shared 3-state pill —
  good/flat/bad, borrowing `StatCard`'s two-state arrow-SVG idiom (`TrendArrowUp`/
  `TrendArrowDown`) but adding the missing neutral/flat state (gray, "≈" glyph) that `StatCard`'s
  own `trend` prop doesn't support. Reach for it (not a page-local badge) if another page ever
  needs a genuinely three-way classification.

## Gotchas

- **`trendReportApi.ts` defines its own `getJSON` helper rather than importing `getJson` from
  `api/http.ts`** — mirrors `MetricsPage/metricsApi.ts`'s documented deviation for the same
  reason (this page's fetcher is the only caller; if the shared transport ever grows auth
  headers or a different error shape, both page-local copies need updating together). It does
  still use the shared `windowQueryParams(selection)` helper for the query-string shape, so the
  dual preset/custom-range convention can't drift from every other page.
- **`design_handoff_trend_report/BACKEND_API.md`'s example payload uses snake_case field names
  (`before_series`, `direction_is_good_when`) and different metric ids than the live contract**
  (camelCase `beforeSeries`/`afterSeries`/`directionIsGoodWhen`, metric ids like `total_cost`
  rather than the doc's `cost_total`). `trendReportApi.ts`'s `TrendReport`/`TrendMetric` types
  match the live contract, not the doc — don't "fix" the frontend to match the doc's casing.
- **No sample-data fallback (`VITE_*_SAMPLE`)** unlike `MetricsPage`/`LogsPage`/`TracesPage` —
  the backend endpoint may not exist yet in early development; the page just shows TanStack
  Query's loading/error state until it does. Add a fixture store here the same way those pages
  did if offline UI iteration becomes a recurring need.
- **The "Comparing from" pill and both period-bar date ranges render off whichever section
  resolved first, not a specific one.** `TrendReportPageView` computes `anyLoadedReport` by
  scanning `TREND_SECTIONS` in order for the first section whose `sections[section.key].data` is
  defined — safe because all four sections compute identical window boundaries from the same
  request. Before any section has loaded they show `null`/`—` rather than a guessed date, since
  the page has no window-derived date math of its own; `formatComparingFromDate`/`formatPeriod`
  both read off the server's own `current`/`previous` timestamps, which already reflect whatever
  `resolveTrendReportSelection` sent (day-snapped or exact, per the note above).
- **Each period-bar date renders as two lines, not one string.** `formatPeriod` returns
  `{ primary, secondary }` — `primary` is the date (`"Aug 30"`, or `"Aug 24 – Aug 30"` when the
  period spans more than one calendar day) and `secondary` is the time-of-day range
  (`"3:00 – 3:59 PM"`), rendered smaller and muted directly under `primary`, or omitted entirely
  when the period runs exactly start-of-day to end-of-day (a time range would just read
  "12:00 AM – 11:59 PM" on every row). This replaced an earlier single-string format that visibly
  broke on a multi-day period: formatting the end boundary alone and splicing off its date left a
  7-day comparison reading as `"Aug 24, 12:00 AM–11:59 PM"` — syntactically a single day, with the
  other six silently dropped. Don't collapse this back into one string without re-solving that
  case: a multi-day, non-day-aligned span needs `"Aug 29 – Aug 30"` as `primary`, not one end's
  date awkwardly fused to the other's time.
- **`TrendReportPage.tsx`'s `selectionKey` is built from the raw selection, not the day-snapped
  one `fetchTrendSection` actually requests.** A `preset:10080` key stays stable all day even though
  the day-snapped request window it resolves to shifts forward at local midnight — relying on
  `staleTime`/the 60s auto-refresh poll to eventually pick up the new day rather than an
  immediate cache-key change. If a "Last 7 days" comparison ever needs to flip to the new day the
  instant it turns, key on `resolveTrendReportSelection(selection)`'s resolved bounds instead of
  the raw preset. This applies identically to all four section query keys.
- **The page-wide top skeleton (period bar + "Comparing from" pill) only shows while every
  section is still loading with no data at all** — `showSkeleton` is
  `!anyLoadedReport && TREND_SECTIONS.every(s => sections[s.key].isLoading)`. Each section's own
  body (real `SectionHeader`, then either `MetricRowSkeleton` rows, an inline error message, or
  real `MetricRow`s) is driven independently by `renderSectionBody(section, sections[section.key])`
  in `TrendReportPageView.tsx` — see the "per-section state" gotcha below. This mirrors
  `PurgeDryRunCard`'s `PurgeDryRunSkeleton` pattern (Settings page): match real content's
  structure/sizing so the card doesn't reflow once data arrives, rather than a generic spinner. On
  a refetch with existing data (auto-refresh, manual reload), a section's own skeleton doesn't
  reappear — only its very first load shows one.
- **Loading/error/data state is per-section, not page-wide — don't re-couple the four sections
  back into one shared state.** The container's `sections: Record<TrendSectionKey,
  TrendSectionState>` (one `{ data, isLoading, error }` per `useQueries` result) is the whole
  point of the split: a slow `blended_rate_per_1m` sparkline query in Cost must not delay
  Reliability's or Activity's rows from painting, and one section erroring must not blank the
  other three. `TrendReportPageView` never derives a single page-wide `isLoading`/`error` from
  `sections` (aside from `showSkeleton`'s "every section still loading" check and the page-level
  error banner's "every section errored with nothing to show" check, both deliberately
  conjunctive across all four) — a future edit that collapses `sections` into one aggregate state,
  or that makes `onReload`/`isPolling` gate on all four resolving together instead of `some(...)
  isFetching`, would silently undo the independent-per-section behavior this page exists to
  provide.
