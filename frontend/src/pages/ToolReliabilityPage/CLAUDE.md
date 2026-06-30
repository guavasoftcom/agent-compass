# Tool reliability page

The Reliability tab of the Tool activity section. Shows tool execution failure rates for the
selected window — overall rate, per-tool ranked bars, a reliability-mix donut, and a
same-tool repeat-run table that surfaces agent hunting behaviour. Backend counterpart:
`ToolActivityController` (`backend/.../controller/ToolActivityController.java`),
endpoints `GET /api/tool-activity/failure-rates` and `GET /api/tool-activity/repeats`.

This page is the `/tool-activity/reliability` child tab of `ToolActivitySection`. Read
[`../ToolActivitySection/CLAUDE.md`](../ToolActivitySection/CLAUDE.md) for routing,
section-scoped reload, and how `useSectionContext()` is wired up.

## Files

```
ToolReliabilityPage/
├── ToolReliabilityPage.tsx       container — useSectionContext, two useQuery calls, useMemo KPIs
├── ToolReliabilityPageView.tsx   view — StatCards + FailureRanking + DonutCard + ToolRepeatsCard
├── components/
│   └── ToolRepeatsCard/
│       ├── ToolRepeatsCard.tsx   single-file presentational table card (no split needed)
│       └── index.ts              re-exports default + ToolRepeatsCardProps
└── index.ts                      re-exports the container default
```

`FailureRanking` is a file-local arrow component defined inside `ToolReliabilityPageView.tsx`
(not a subfolder) — it is small enough that a subfolder would add indirection without benefit.

## Visual layout

```
┌─ PageLayout (no title — subtitle only) ─────────────────────────────────┐
│ subtitle: "Tool execution failure rate over the selected window…"         │
│ (PageActions is rendered by the parent SectionLayout, not this page)      │
├──────────────────────────────────────────────────────────────────────────┤
│ ┌─ 4-column StatCard grid ──────────────────────────────────────────────┐ │
│ │ Overall failure rate │ Total calls │ Total failures │ Most-failing    │ │
│ │                      │             │                │ tool (≥5 calls) │ │
│ └───────────────────────────────────────────────────────────────────────┘ │
│ ┌─ 2-column grid (1.6fr / 1fr) ─────────────────────────────────────────┐ │
│ │ ┌─ FailureRanking (Paper) ───────────────────┐ ┌─ DonutCard ────────┐ │ │
│ │ │ "Tools by failure rate"                    │ │ "Reliability mix"  │ │ │
│ │ │ sorted desc by failureRate, then failures  │ │ Succeeded / Failed │ │ │
│ │ │ CSS bar per row; bar color:                │ │ center: fail rate  │ │ │
│ │ │   ≥20% → error.main                        │ │                    │ │ │
│ │ │   <20%  → warning.main                     │ │                    │ │ │
│ │ └────────────────────────────────────────────┘ └────────────────────┘ │ │
│ └───────────────────────────────────────────────────────────────────────┘ │
│ ┌─ ToolRepeatsCard (Paper, full width) ─────────────────────────────────┐ │
│ │ "Same-tool repeats per session"                                        │ │
│ │ MUI Table: Tool | Scope | Median run | Max run | Sessions              │ │
│ │ Max run badge tinted: ≥6 → severe, ≥4 → warning, <4 → violet tint    │ │
│ └───────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

Fetchers live in `api/endpoints.ts` (the shared barrel), not a page-local module.

| Source                          | Query key                             | Fetcher → endpoint |
|---------------------------------|---------------------------------------|--------------------|
| `ToolReliabilityPage` (`useQuery`) | `['tool-failure-rates', selectionKey]` | `fetchToolFailureRates(selection)` → `GET /api/tool-activity/failure-rates?…` |
| `ToolReliabilityPage` (`useQuery`) | `['tool-repeats', selectionKey]`       | `fetchToolRepeats(selection)` → `GET /api/tool-activity/repeats?…` |

`selectionKey` is `'preset:<minutes>'` or `'custom:<start>:<end>'` — same pattern as every
other page. Both queries share the same `refetchInterval` (60 s when `autoRefresh && kind
=== 'preset'`, `false` otherwise). The view components (`FailureRanking`, `DonutCard`,
`ToolRepeatsCard`) never fetch — they receive data as props from the container.

## Data flow and semantics

- `ToolReliabilityPage` reads `selection` and `autoRefresh` from `useSectionContext()`.
  It runs both queries, derives the four KPI values in a `useMemo`, and passes everything as
  typed props to `ToolReliabilityPageView`. The view is pure props in, JSX out.
- **Failure rates** (`ToolFailureRateRow[]`): each row is one tool name with `calls`,
  `failures`, and a pre-computed `failureRate` ratio. The endpoint counts only
  `tool_result` events that actually fired — denied-at-hook invocations are excluded.
- **Most-failing tool KPI**: noise-filtered. Only tools with `calls >= MIN_CALLS_FOR_RANKING`
  (constant `5`) are eligible. A 1-of-1 100% spike from a single call is treated as noise,
  not signal.
- **Failure-rate bar colour threshold**: `HIGH_FAILURE = 0.2` (20%). Bars at or above that
  render in `theme.palette.error.main`; below it in `theme.palette.warning.main`.
- **Reliability-mix donut**: `succeeded = max(0, totalCalls - totalFailures)`, sliced into
  Succeeded (`success.main`) and Failed (`error.main`). Center label shows the overall
  failure-rate percentage.
- **Same-tool repeats** (`ToolRepeatStatRow[]`): each row is a `(tool, scope)` pair with
  `medianRunLength`, `maxRunLength`, and `sessions`. Scope is the file path for file-editing
  tools; the server sends `''` or a sentinel string when no scope is available, which the
  card renders as `(no scope)` in a dimmed italic style. Rows represent the longest
  consecutive run of the same tool acting on the same scope within a session, aggregated
  across sessions. Long chains on the same file indicate the agent is hunting — the table
  description links this to AGENTS.md guidance.
- **Max-run badge tinting**: defined in `runTone()` inside `ToolRepeatsCard.tsx`. Colors come
  from `theme/colors.ts` (`severity.severe`, `severity.warning`, `auroraColors.violet`) —
  no hard-coded hex values.

## Gotchas

- **ESLint warning in `ToolReliabilityPage.tsx`**: line 36 (`const rows = failureRatesQuery.data ?? []`)
  triggers `react-hooks/exhaustive-deps` — the logical expression `?? []` creates a new
  array reference on every render, which the lint rule flags as a potentially unstable
  `useMemo` dependency. The practical impact is low (the memo re-runs only when the query
  data changes) but the warning is real. Fix by either memoizing `rows` separately or
  inlining `failureRatesQuery.data ?? []` directly inside the `useMemo` reducer calls.
- **No `PageActions` in this view.** Window selection, refresh, and auto-refresh are owned by
  the parent `SectionLayout` and rendered once for all four tabs. Don't add a `PageActions`
  component here.
- **`PageLayout` is called without a `title` prop.** The section header already carries the
  "Tool Usage" title; the page supplies only `subtitle` and `error`.
- **`FailureRanking` is not a folder component.** It is a file-local React arrow function
  inside `ToolReliabilityPageView.tsx`. It takes `rows` and `isLoading` as props and
  renders no context. Don't extract it to a subfolder unless it grows significantly.
- **Query key prefixes must stay in sync with `ToolActivitySection`.** The prefixes
  `'tool-failure-rates'` and `'tool-repeats'` are listed in `QUERY_KEY_PREFIXES` inside
  `ToolActivitySection.tsx`. If you rename a query key here, update that list or the
  section-level Refresh button will silently miss these queries.
