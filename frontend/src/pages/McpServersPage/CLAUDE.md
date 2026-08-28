# MCP servers page

A section tab under [Tool Usage (`/tools`)](../ToolActivitySection/ToolActivitySection.tsx)
that answers "is this MCP server worth its context and latency cost?" — four KPI tiles, a
`DonutCard` server-mix chart, a `BreakdownList`-based server ranking (failure rate / p95 /
context bytes per server), and a per-(server, tool) detail table. Backend counterpart:
`ToolActivityController` → `LogService` (`backend/.../controller/ToolActivityController.java`),
endpoint `GET /api/tool-activity/mcp-usage`.

## Files

```
McpServersPage/
├── McpServersPage.tsx        container — reads useSectionContext(), runs one query,
│                              rolls the per-tool rows up per server, derives KPIs + colors
├── McpServersPageView.tsx    view — StatCard strip + DonutCard + ServerRankingCard
│                              (BreakdownList) + McpToolDetailTable; no queries, no context
├── mcpDerivations.ts         pure derivations (rollupByServer, withShare,
│                              buildServerColorIndexes)
├── mcpDerivations.test.ts    vitest coverage for the above
└── index.ts                  re-exports container as default
```

`ServerRankingCard` and `McpToolDetailTable` are file-local arrow components defined inside
`McpServersPageView.tsx` (not subfolders), matching `ToolReliabilityPageView`'s
`FailureRanking`/`ReliabilityMixCard` convention — each is small enough that a subfolder would
add indirection without benefit.

## Visual layout

```
┌─ PageLayout (no title — SectionLayout owns the title row) ──────────────┐
│ subtitle prose                                                            │
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─ 4-column StatCard grid ──────────────────────────────────────────┐   │
│ │ MCP calls │ Failure rate │ Slowest server (p95) │ Context consumed│   │
│ └───────────────────────────────────────────────────────────────────┘   │
│ ┌─ 2-column grid ────────────────────────────────────────────────────┐   │
│ │  DonutCard "Server mix" (per-server calls, ranked legend)           │   │
│ │  ServerRankingCard "Servers" (BreakdownList, stacked + color dot;   │   │
│ │  each row's secondaryText carries failure rate / p95 / bytes /      │   │
│ │  tool count)                                                        │   │
│ └───────────────────────────────────────────────────────────────────┘   │
│ ┌─ McpToolDetailTable (full width) ─────────────────────────────────┐   │
│ │  Server | Tool | Calls | Failure rate | Avg | P95 | Context bytes |  │
│ │  Est. tokens — one row per (server, tool) pair, calls desc          │   │
│ │  Failure rate and P95 cells flag warning.main when outlying         │   │
│ │  (see Data flow and semantics)                                      │   │
│ └───────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

The fetcher lives in `api/endpoints.ts` (the shared barrel, not a page-local module).

| Container hook | Query key                    | Fetcher → endpoint |
|-----------------|-------------------------------|---------------------|
| `useQuery`      | `['mcp-usage', selectionKey]` | `fetchMcpServerUsage(selection)` → `GET /api/tool-activity/mcp-usage?…` |

Returns `McpServerUsageRow[]` (imported from `../../api`) — one row per **(server, tool)**
pair, not per server. `server`/`tool` identify the MCP server (e.g. `playwright`) and the
server-side tool it exposed (e.g. `browser_evaluate`); `calls`/`failures`/`failureRate` are
scoped to that one pair, and `avgDurationMs`/`p95DurationMs` are execution-only latency (never
includes time blocked on a permission prompt — see the backend's `mcpToolName` javadoc). This
single response feeds every card on the page — there is no second query.

## Data flow and semantics

- **Section tab, not a top-level page.** The container reads `selection` and `autoRefresh` from
  `useSectionContext()` (not `useWindowContext()`). The `WindowSelector`, reload button, and
  auto-refresh toggle all live in `SectionLayout`'s `PageActions`; this page renders no chrome
  of its own.
- **`selectionKey`** is built in the container: `preset:<minutes>` or
  `custom:<startTimestamp>:<endTimestamp>`, placed in the query key so the TanStack cache splits
  cleanly per window selection.
- **`refetchInterval`** is `AUTO_REFRESH_INTERVAL_MS` (60 s) when `autoRefresh &&
  selection.kind === 'preset'`, otherwise `false`. Custom ranges have a fixed end and are not
  polled.
- **Section-level reload.** `ToolActivitySection` lists `'mcp-usage'` in its
  `QUERY_KEY_PREFIXES`, so the section-level Refresh button invalidates this query by predicate
  alongside the other tool-activity tabs.
- **`rollupByServer`** (in `mcpDerivations.ts`) is the core derivation: it groups the raw
  per-(server, tool) rows by `server` and sums `calls`/`failures`/`totalBytes`/
  `estimatedTokens`. `failureRate` is **recomputed** from the rolled-up totals
  (`failures / calls`), not averaged across the server's tools — averaging per-tool rates would
  overweight a low-volume tool's rate against a high-volume one. `avgDurationMs` is a
  calls-weighted average across the server's tools (`Σ(avgDurationMs × calls) / Σcalls`);
  `p95DurationMs` takes the **max** of each tool's own p95 rather than averaging — a server's
  "worst case" latency is what answers "is this server slow", and averaging percentiles across
  tools isn't a real percentile. Both choices mirror the report's own per-server MCP rollup
  (design doc §Approach) so the dashboard and the report never disagree about how a server's
  latency is summarized.
- **`withShare`** maps `McpServerRollup[]` to `McpServerRollupWithShare[]` and computes `total`
  (total calls across every server). `share` is `(100 * row.calls) / total`; total-zero guard
  sets `share = 0`. Same signature/shape as `SkillsAgentsPage`'s `withShare`, but it operates on
  the already-rolled-up server rows, not the raw per-tool rows.
- **`buildServerColorIndexes`** orders every server by total calls descending (ties on server
  name) and returns `server → palette index`. Unlike `SkillsAgentsPage`'s
  `buildModelColorIndexes`, there is **no fixed "known family" set to pin first** — MCP servers
  are whatever the user has configured (Playwright, CodeGraphContext, or anything else), so
  colors are assigned purely by volume, not by identity. The same map drives both the
  `DonutCard` slice colors and the `ServerRankingCard` bar/dot colors, so one server keeps one
  color across the page.
- **`slowestServer`** (derived in the container, not `mcpDerivations.ts` — it's a `reduce` over
  already-rolled-up `servers`, not pure enough to warrant its own exported function) picks the
  server with the highest rolled-up `p95DurationMs`. This is a **per-server** p95, not a global
  p95 across every call — a slow-but-rare server would otherwise hide behind a fast-but-frequent
  one in a flat aggregate.
- **`toSlices` helper** (module-private in the view) converts `McpServerRollupWithShare[]` into
  the `DonutCard` slice shape: `{ label: server.server, value: server.calls, color:
  colorForIndex(serverColorIndexes.get(server.server)), muted }`. The `unknown` bucket (a row
  whose `tool_parameters` carried no resolvable `mcp_server_name`) renders muted, same
  convention as `SkillsAgentsPage`'s `UNKNOWN_IDENTIFIER`.
- **`ServerRankingCard`** uses `BreakdownList`'s `'stacked'` layout with `showColorDot` (not
  `showRank`) — the dot ties each row back to its `DonutCard` slice color, which matters more
  here than an explicit rank number since the list is already calls-sorted top to bottom. Each
  row's `secondaryText` carries the failure rate, p95, context bytes, and tool count — the four
  numbers this page exists to let a reader weigh against call volume, without adding a second
  ranked list per metric.
- **`McpToolDetailTable`** is a hand-built MUI `Table` (not `BreakdownList` — the design doc's
  contract needs 8 numeric columns per row, well past what a ranked-bar row can show), listing
  every raw `McpServerUsageRow`, calls-descending. This is the drill-down the per-server rollup
  can't show: a server can have one dominant tool and several rarely-used ones, and the failure
  rate or latency of any *one* tool doesn't necessarily match its server's overall figure.
- **Outlier flagging (no row collapse)** — a reader's eye should go to the rows worth
  investigating without hiding any row: every `(server, tool)` pair from `toolRows` renders,
  calls-descending, in one table. Failure rate already rendered `warning.main` for any
  `failures > 0`; P95 gets the same treatment via `mcpDerivations.ts`'s `SLOW_P95_MS` (5000ms)
  constant — `p95DurationMs >= SLOW_P95_MS` renders `warning.main` + `fontWeight: 600`.
  `ToolDetailTableRow`/`ToolDetailTableHead` (file-local in `McpServersPageView.tsx`) hold the
  per-row cell rendering and header so they stay defined once. An earlier version of this
  table also collapsed zero-failure/low-volume rows into a closed `<details>` disclosure
  (mirroring `ToolReliabilityPageView`'s `zerowrap` pattern); that collapse was removed on
  request — the table is now a flat, ungrouped list of every row, no matter how low-volume.
  Don't reintroduce a collapse/disclosure here without it being asked for again.

## Gotchas

- **Rows are keyed `(server, tool)`, not `tool` alone.** Two different servers could in
  principle expose a tool with the same name; `McpToolDetailTable`'s React `key` is
  `` `${row.server}::${row.tool}` ``, matching `ToolRepeatsCard`'s `(tool, scope)` key
  convention. Don't key on `tool` alone.
- **No model dimension, and no `ModelFirstBlocks` equivalent.** MCP tool calls carry no `model`
  attribute path the way skill/subagent invocations do (§4 of the design doc explicitly drops
  the `LEFT JOIN LATERAL` model correlation for this reason) — this page has no by-model split,
  no `coverageTicks`/`legendCaption` on its `DonutCard`, and no `ModelFirstBlocks`-style card.
  Don't clone `SkillsAgentsPage`'s `ModelFirstBlocks` component here; there is nothing for it to
  bind to.
- **`PageLayout` receives no `title` prop** — the section's `SectionLayout` already renders the
  page title and tab strip above the outlet. Adding a `title` here would produce a duplicate
  heading.
- **Latency is execution-only (log `duration_ms`), never span duration.** Span-side latency on
  `claude_code.tool` includes time blocked waiting for permission approval — the design doc
  found `browser_resize` measuring p95 297s on spans versus 8ms average execution in the log.
  `McpServerUsageRow.avgDurationMs`/`p95DurationMs` come from the backend's log-backed
  aggregation for exactly this reason; don't wire this page to any span-latency endpoint (e.g.
  `/calls/latency`) expecting the same numbers — they measure different things.
  `mcp:<server>`-prefixed rows on `ToolCallsPage`'s `ToolLatencyCard` are the span-side view and
  will legitimately disagree with this page.
- **`estimatedTokens` is `totalBytes / 4`, matching `ToolContextFootprintRow`'s convention —
  not billed spend.** Never add it to, or display it alongside, `TokenUsageSummary`'s exact
  figures. It also understates real cost the same way `ContextFootprintCard`'s estimate does: a
  tool result is re-sent with every later request in its session.
- **The rollup's `failureRate` is recomputed, not averaged.** If you add a new per-server
  aggregate to `mcpDerivations.ts`, check whether it needs the same treatment (recompute from
  summed numerator/denominator) rather than a naive average of the per-tool `McpServerUsageRow`
  values — an average silently misweights a low-volume tool against a high-volume one.
