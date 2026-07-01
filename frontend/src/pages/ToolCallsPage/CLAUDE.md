# Tool calls page

Dashboard tab showing aggregate tool invocation counts, call-mix breakdown, per-tool latency
percentiles, and call-volume over time. It is the default child tab of the Tool activity section
(`/tool-activity/calls`); the section wrapper is described in
[../ToolActivitySection/CLAUDE.md](../ToolActivitySection/CLAUDE.md).
Backend counterpart: `ToolActivityController` → `GET /api/tool-activity/calls`,
`/calls/timeseries`, `/calls/latency` (`backend/.../controller/ToolActivityController.java`).

## Files

```
ToolCallsPage/
├── ToolCallsPage.tsx           container — reads useSectionContext(), runs 3 queries,
│                               derives rowsWithShare + total, passes flat props to view
├── ToolCallsPageView.tsx       view — PageLayout + StatsRow + ToolLatencyCard + DonutCard
│                               + CallsOverTimeCard; exports ToolCallRowWithShare type
├── components/
│   ├── StatsRow/
│   │   ├── StatsRow.tsx        container — derives topRow, slowestRow, spark; no fetch
│   │   ├── StatsRowView.tsx    view — 4 StatCards (Total, Top tool, Distinct, Slowest p95)
│   │   │                       with inline Sparkline and Accent helper
│   │   └── index.ts
│   ├── CallsOverTimeCard/
│   │   ├── CallsOverTimeCard.tsx     container — builds LineSeries[] from ToolCallTimeseries;
│   │   │                             colors via colorForIndex, 'Other' gets action.disabled
│   │   ├── CallsOverTimeCardView.tsx view — ChartCard wrapping shared AreaTrendChart +
│   │   │                             AreaTrendLegend + useSeriesVisibility; empty states
│   │   └── index.ts
│   └── ToolLatencyCard/
│       ├── ToolLatencyCard.tsx      container — caps rows at 10, builds 2-series LatencyBarSeries
│       │                            (Typical = p50, Worst 5% = p95 − p50 in seconds);
│       │                            derives yAxisWidth from longest label
│       ├── ToolLatencyCardView.tsx  view — hand-built CSS segmented bars inside ChartCard;
│       │                            EllipsisLabel component shows tooltip when text truncates
│       └── index.ts
└── index.ts
```

## Visual layout

```
┌─ PageLayout (no title — section chrome supplies it) ──────────────────────┐
│ subtitle: "Aggregate tool invocations, mix, throughput, and latency."      │
├────────────────────────────────────────────────────────────────────────────┤
│ ┌─ StatsRow (4 StatCards) ──────────────────────────────────────────────┐  │
│ │ Total invocations (sparkline) │ Top tool │ Distinct tools │ Slowest p95│  │
│ └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│ ┌─ grid: 1.6fr ─────────────────────────────┐ ┌─ 1fr ──────────────────┐  │
│ │ ToolLatencyCard                            │ │ DonutCard "Call mix"   │  │
│ │ hand-built CSS bars                        │ │ donut slices per tool  │  │
│ │ Typical (p50) + Worst 5% (p95−p50)         │ │ total calls centered   │  │
│ │ top-10 tools, sorted by p95 desc           │ │ ranked legend          │  │
│ └────────────────────────────────────────────┘ └────────────────────────┘  │
│                                                                             │
│ ┌─ CallsOverTimeCard (full width) ──────────────────────────────────────┐  │
│ │ AreaTrendChart — stacked-area per tool · interactive legend toggles   │  │
│ │ crosshair tooltip ranks all visible tools at hovered bucket           │  │
│ └───────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

All three fetchers live in the shared `api/` barrel (`from '../../api'`).

| Source                      | Query key                             | Fetcher → endpoint |
|-----------------------------|---------------------------------------|--------------------|
| `ToolCallsPage` (`useQuery`) | `['tool-calls', selectionKey]`       | `fetchToolCalls(selection)` → `GET /api/tool-activity/calls?…` |
| `ToolCallsPage` (`useQuery`) | `['tool-calls-timeseries', selectionKey]` | `fetchToolCallsTimeseries(selection)` → `GET /api/tool-activity/calls/timeseries?…` |
| `ToolCallsPage` (`useQuery`) | `['tool-calls-latency', selectionKey]`  | `fetchToolCallLatency(selection)` → `GET /api/tool-activity/calls/latency?…` |

`selectionKey` is `'preset:<minutes>'` or `'custom:<start>:<end>'`. All three queries share
the same key suffix, so section-level reload (which invalidates by prefix) covers all of them.

Sub-components (`StatsRow`, `CallsOverTimeCard`, `ToolLatencyCard`, `DonutCard`) never fetch —
they receive already-fetched data as props from the container or the view.

## Data flow and semantics

- `ToolCallsPage` reads `selection` and `autoRefresh` from `useSectionContext()` (not
  `useWindowContext()`) because this page is a child tab of `ToolActivitySection`. Mixing
  them up compiles but ignores the section's shared selection state.
- `refetchInterval` is `AUTO_REFRESH_INTERVAL_MS` (60 s) only when `autoRefresh` is true
  and `selection.kind === 'preset'`. Custom ranges have a fixed end and must not poll.
- **`rowsWithShare` derivation**: the container `useMemo` computes `totalCalls` as the
  sum of all `ToolCallRow.calls`, then attaches a `share` percentage to each row.
  `ToolCallRowWithShare` (exported from `ToolCallsPageView.tsx`) extends `ToolCallRow`
  with this computed field and is used by both `StatsRow` and the `DonutCard` slices.
- **`StatsRow` derivation**: top tool is `rowsWithShare[0]` (backend sorts by calls desc).
  Slowest tool is `latencyRows[0]` (backend sorts by p95 desc). The sparkline is the
  per-bucket total across all tools: `timeseries.points.map(p => p.counts.reduce(...))`.
- **`ToolLatencyCard` derivation**: latency values from the backend are in milliseconds
  (`p50Ms`, `p95Ms`). The container converts to seconds for the CSS bar scale
  (`p50Ms / 1000`). The "Worst 5%" segment is `Math.max(0, (p95Ms - p50Ms) / 1000)` —
  clamped to zero so a p50 > p95 edge case never produces a negative bar width. Display
  reverts to ms notation when the value is below 1 second (`formatSeconds` in both
  container and view).
- **`CallsOverTimeCard` series coloring**: `'Other'` buckets (backend aggregates long tails)
  get `theme.palette.action.disabled` so they visually recede; all named tools use
  `colorForIndex(columnIndex)` from the shared `CHART_PALETTE`.

## Gotchas

- The three query key prefixes (`'tool-calls'`, `'tool-calls-timeseries'`,
  `'tool-calls-latency'`) must stay in sync with `QUERY_KEY_PREFIXES` in
  `ToolActivitySection.tsx` — the section-level reload invalidates by prefix, so a
  renamed key silently drops out of the reload scope.
- `ToolLatencyCard` caps rendering at 10 rows (`latencyRows.slice(0, 10)`) but the
  backend may return more; this is intentional to keep the card height reasonable.
- `EllipsisLabel` in `ToolLatencyCardView` uses a `ResizeObserver` to measure text
  truncation — the tooltip only appears when the element's `scrollWidth > clientWidth`.
  Don't remove the observer cleanup or it leaks on unmount.
- `CallsOverTimeCardView` emits a different empty-state message when exactly one bucket
  is present (`axisDates.length === 1`) versus truly no data, because `AreaTrendChart`
  requires at least two dates to plot a trend line.
- `PageLayout` on this page has no `title` prop — the section's `SectionLayout` chrome
  already renders the page heading and the `PageActions` (window selector, refresh,
  auto-refresh). Don't add a redundant title or actions slot here.
