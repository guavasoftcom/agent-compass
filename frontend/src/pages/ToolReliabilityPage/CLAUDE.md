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
# Tool reliability page

The Reliability tab of the Tool activity section. Shows tool execution failure rates for the
selected window — overall rate, per-tool ranked bars (failing tools only), a reliability-mix
stacked bar, and a same-tool repeat-run table that surfaces agent hunting behaviour. Backend
counterpart:
`ToolActivityController` (`backend/.../controller/ToolActivityController.java`),
endpoints `GET /api/tool-activity/failure-rates` and `GET /api/tool-activity/repeats`.

This page is the `/tools/reliability` child tab of `ToolActivitySection`. Read
[`../ToolActivitySection/CLAUDE.md`](../ToolActivitySection/CLAUDE.md) for routing,
section-scoped reload, and how `useSectionContext()` is wired up.

## Files

```
ToolReliabilityPage/
├── ToolReliabilityPage.tsx       container — useSectionContext, two useQuery calls, useMemo KPIs
├── ToolReliabilityPageView.tsx   view — StatCards + FailureRanking + ReliabilityMixCard + ToolRepeatsCard
├── ToolReliabilityPageView.test.tsx  vitest coverage for the view (renderWithProviders, prop fixtures)
├── components/
│   └── ToolRepeatsCard/
│       ├── ToolRepeatsCard.tsx   single-file presentational table card (no split needed)
│       └── index.ts              re-exports default + ToolRepeatsCardProps
└── index.ts                      re-exports the container default
```

`FailureRanking` and `ReliabilityMixCard` are file-local arrow components defined inside
`ToolReliabilityPageView.tsx` (not subfolders) — each is small enough that a subfolder would
add indirection without benefit. `ReliabilityMixCard` deliberately does **not** reuse the
shared `components/DonutCard` — a donut/ring fails at the pass/fail skew this data actually
has (97-99% success is typical, so the failure slice shrinks to an invisible sliver); it needs
its own stacked-bar rendering, and `DonutCard` itself is still used as-is by
`PermissionDenialsPage`, `ToolCallsPage`, and `SkillsAgentsPage`, so it wasn't changed.

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
│ │ ┌─ FailureRanking (Paper) ─────────────┐ ┌─ ReliabilityMixCard ─────┐ │ │
│ │ │ "Tools by failure rate"              │ │ "Reliability mix"        │ │ │
│ │ │ only tools with ≥1 failure, ranked   │ │ big fail-rate number +   │ │ │
│ │ │ desc; bar scaled to highest rate     │ │ slim 2-seg stacked bar   │ │ │
│ │ │ in the current dataset. Zero-failure │ │ (ok/bad, flex-grow) +    │ │ │
│ │ │ tools collapse into a closed         │ │ Succeeded/Failed legend  │ │ │
│ │ │ <details> disclosure below.          │ │                          │ │ │
│ │ │ Bar color: ≥20% → error.main,        │ │                          │ │ │
│ │ │            <20%  → warning.main      │ │                          │ │ │
│ │ └────────────────────────────────────────┘ └──────────────────────────┘ │
│ └───────────────────────────────────────────────────────────────────────┘ │
│ ┌─ ToolRepeatsCard (Paper, full width) ─────────────────────────────────┐ │
│ │ "Same-tool repeats per session"                                        │ │
│ │ MUI Table (table-layout:fixed + colgroup):                             │ │
│ │   Tool | Scope | Median run | Max run | Sessions                       │ │
│ │ Scope is two lines for file paths (filename bold / dir dimmed, each    │ │
│ │ independently truncated); one line with a dim "$" prefix for Bash      │ │
│ │ commands; sandbox tmp paths collapse to an italic label.               │ │
│ │ Max run badge tinted: ≥10 → severe, 6-9 → warning, <6 → violet tint  │ │
│ │ Sessions === 1 gets an inline "spike" tag.                             │ │
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
=== 'preset'`, `false` otherwise). The view components (`FailureRanking`,
`ReliabilityMixCard`, `ToolRepeatsCard`) never fetch — they receive data as props from the
container.

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
- **Failure ranking shows only failing tools.** `FailureRanking` filters `rows` to
  `failures > 0` before ranking/rendering bars — a tool inventory commonly runs 10-15+ tools
  where most have zero failures, and showing every tool as an equal-weight bar buried the
  2-3 that matter. Zero-failure tools (sorted by `calls` descending) collapse into a native
  `<details>`/`<summary>` disclosure ("N tools with no failures · M calls"), closed by
  default, expanding to a 2-column name/call-count grid.
- **Failure-rate bar width is relative to the dataset, not absolute.** `width% = failureRate /
  maxRate * 100` where `maxRate` is the highest `failureRate` among currently-failing tools —
  so the worst offender always fills the track regardless of how low absolute rates run (real
  data clusters 0-5.5%, not the 0-24% a small curated demo might show). Bar *color* threshold
  is unrelated to this scaling: `HIGH_FAILURE = 0.2` (20%) — at or above renders
  `theme.palette.error.main`, below renders `theme.palette.warning.main`.
- **Reliability mix is a stacked bar, not a donut** (`ReliabilityMixCard`, file-local in
  `ToolReliabilityPageView.tsx`). `succeeded = max(0, totalCalls - totalFailures)`; a big
  fail-rate number sits above a slim two-segment bar (`flex-grow: succeeded` /
  `flex-grow: totalFailures`) using `success.main` / `error.main`. The failure segment gets a
  `minWidth: 4` floor whenever `totalFailures > 0` so it never fully disappears at low fail
  rates — omitted entirely when `totalFailures === 0` so a healthy window doesn't show a false
  sliver. The Succeeded/Failed legend below is unchanged in content from the old donut legend.
- **Same-tool repeats** (`ToolRepeatStatRow[]`): each row is a `(tool, scope)` pair with
  `medianRunLength`, `maxRunLength`, and `sessions`. Scope is the file path for file-editing
  tools; for Bash it's the command with any leading `cd <path> &&`/`cd <path>;` chain
  stripped, then its first two whitespace-delimited tokens (program + subcommand/flag) — a
  bare first token used to collapse e.g. `cd backend && ./mvnw test` and `cd frontend &&
  yarn dev` onto the same `cd` scope, and `git status`/`git commit` onto the same `git`
  scope, making unrelated commands look like a repeat run (verified against live data:
  stripping cd-chains and widening to two tokens dropped the `Bash`/`cd` bucket from 79
  sessions/max run 12 to a single genuine repeated-directory case). Every tool that isn't
  Edit/Write/Read/MultiEdit/Bash has no resolvable scope, and rows where scope couldn't be
  determined are dropped by the query entirely rather than reported under a shared
  `(no scope)` value — that shared value isn't evidence two calls repeated the same action
  (worst case: every MCP server's tools report as the single generic `mcp_tool` name, so two
  unrelated MCP calls in a row used to register as a "repeat"; dropping unscoped rows removed
  it, along with noise from `Agent`/`WebFetch`/`TodoWrite`/`Grep`/`ToolSearch` and stray
  Edit/Write/Read calls with no captured `file_path`). That `mcp_tool` collapse is a fact about
  this **log**-backed query specifically, and this query is deliberately left unsplit (a
  per-server identity still isn't a *scope*, so re-admitting these rows would reintroduce the
  same false-repeat bug) — every other log-backed aggregation on this page and the rest of the
  dashboard now splits it back out to `mcp:<server>` rows, and the **span**-backed latency card
  never had this problem to begin with: `tool_name` there is already the per-server prefixed
  form `mcp__<server>__<tool>` (collapsed to one `mcp:<server>` row per server instead, the
  opposite direction). The card therefore never receives an
  unscoped row and has no `(no scope)` rendering branch — don't reintroduce one without
  reintroducing the backend rows it would represent. Rows represent the longest consecutive
  run of the same tool acting on the same scope within a session, aggregated across sessions.
  Long chains on the same file indicate the agent is hunting — the table description links
  this to AGENTS.md guidance; Bash rows are a noisier proxy for the same idea since a shell
  command's target isn't as unambiguous as a file path.
- **Max-run badge tinting**: defined in `runTone()` inside `ToolRepeatsCard.tsx`. Thresholds
  are `≥10 → severe`, `6-9 → warning`, `<6 → violet` — re-bucketed to the real value range
  (live data clusters 4-14; the old `≥7 / ≥4` thresholds were tuned to a 3-7 demo range and
  real data never hit the "cool" tier). Colors come from `theme/colors.ts`
  (`severity.severe`, `severity.warning`, `auroraColors.violet`) — no hard-coded hex values.
- **Scope display is derived client-side by `describeScope()`** in `ToolRepeatsCard.tsx` (not
  a backend field) — it does not change what `scope` *is*, only how it's split into the
  table's two-line cell. For Edit/Write/Read/MultiEdit rows it splits `scope` (a file path) on
  the last `/` into a bold filename line + a dimmed directory line (no directory line for a
  root-level file); for Bash rows it renders one line with a dim `$` prefix, since the scope
  string is already just `program subcommand`. Either kind can additionally match
  `SANDBOX_TMP_PATTERN` (`/private/tmp/claude-<n>/<mangled-project-dir>/...`, the shape Claude
  Code's own scratch paths take, including a bare un-chained `cd <path>` that survived the
  backend's cd-chain stripping) — that collapses the mangled middle segment to an italic
  `sandbox tmp · claude-<n>/` label with just the basename on top, since the raw path is
  unreadable and the full string is still one hover away via the `Tooltip`. The `Tooltip`
  wraps the file/sandbox two-line cell only; command-scope cells skip it since the full
  command is already the visible text. Rows with `sessions === 1` get an inline "spike" tag
  next to the count — a chronic cross-session pattern reads very differently from a one-off,
  and this is the same `sessions` field the table already had, not a new backend value.
- **Table uses `table-layout: fixed` with an explicit `<colgroup>`** (10/46/14/16/14%) so the
  two-line scope cell has a stable width to truncate against. The scope `<td>` must stay a
  normal table-cell — don't put `display: flex` on the `TableCell` itself (only the `Box`
  inside it), or row-height stretching breaks and single-line rows stop matching the height of
  two-line rows, misaligning the bottom borders.

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
- **`FailureRanking` and `ReliabilityMixCard` are not folder components.** Both are file-local
  React arrow functions inside `ToolReliabilityPageView.tsx`, take plain props, and render no
  context. Don't extract either to a subfolder unless it grows significantly.
- **Query key prefixes must stay in sync with `ToolActivitySection`.** The prefixes
  `'tool-failure-rates'` and `'tool-repeats'` are listed in `QUERY_KEY_PREFIXES` inside
  `ToolActivitySection.tsx`. If you rename a query key here, update that list or the
  section-level Refresh button will silently miss these queries.
