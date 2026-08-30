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
every other top-level page. Backend counterpart: `GET /api/trends` (see
`design_handoff_trend_report/BACKEND_API.md` for the original proposal this page was built
against — the live contract uses camelCase field names, see `trendReportApi.ts`).

## Files

```
TrendReportPage/
├── TrendReportPage.tsx        container — window context, single useQuery, selectionKey/
│                              refetchInterval wiring (mirrors MetricsPage.tsx)
├── TrendReportPageView.tsx    view — period bar, section headers + metric rows, summary strip;
│                              no queries, no context
├── trendReportApi.ts          TrendPeriod/TrendMetric/TrendReport types + fetchTrendReport();
│                              page-local getJSON helper (mirrors MetricsPage/metricsApi.ts)
├── trendReportDerivations.ts  pure derivations: TREND_SECTIONS, METRIC_LABELS,
│                              formatMetricValue, computeDelta, computeBeforeSharePct,
│                              formatPeriodRange/formatComparingFromDate, describeWindowSpan,
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

| Container hook | Query key | Fetcher → endpoint |
|---|---|---|
| `useQuery` | `['trend-report', selectionKey]` | `fetchTrendReport(selection)` → `GET /api/trends?…` |

`selectionKey` follows the same `preset:<minutes>` / `custom:<start>:<end>` pattern as every other
page. `MetricRow` and `SectionHeader` never fetch — the view derives every row's props from the
single `report.metrics` bundle.

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
- **Summary strip is derived, not hardcoded.** `buildSummaryCallouts` reads `total_cost`,
  `tool_errors`, and `sessions` off the fetched response and generates three short sentences
  (Cost / Reliability / Volume) from their actual before/after figures and `computeDelta`
  classification — never the reference design's example copy ("driven by cache efficiency and
  model-mix changes" etc., which describes data this page doesn't have). A metric missing from a
  degraded response drops its callout instead of throwing; the strip section renders nothing at
  all when `summaryCallouts` is empty (e.g. before the first successful fetch).
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
- **The "Comparing from" pill and both period-bar date ranges only render once `report` has
  loaded** — before the first successful fetch they show `null`/`—` rather than a guessed date,
  since the page has no window-derived date math of its own; `formatComparingFromDate`/
  `formatPeriodRange` both read off the server's own `current`/`previous` timestamps.
- **The initial load (`isLoading && !report`) renders full skeleton content, not a spinner/text
  placeholder.** `TrendReportPageView`'s `showSkeleton` flag drives: a pill-shaped `Skeleton` for
  the "Comparing from" chip, `Skeleton` text in place of both period-bar date labels, the *real*
  `SectionHeader`s (label/icon/accent are static, not data-dependent) each followed by one
  `MetricRowSkeleton` per `TREND_SECTIONS` metric key, and three skeleton blocks where the
  summary strip's callouts land. This mirrors `PurgeDryRunCard`'s `PurgeDryRunSkeleton`
  pattern (Settings page) — match real content's structure/sizing so the card doesn't reflow
  once data arrives, rather than a generic spinner. On a refetch with existing `report` data
  (auto-refresh, manual reload), `showSkeleton` is false and the old content stays put — only the
  very first load shows skeletons.
