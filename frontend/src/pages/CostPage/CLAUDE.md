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
# Cost page

Top-level page answering "where did the money go", split across two in-page tabs. **Where it
went**: tab-driven KPI strip, the work-category money map (Main loop / Subagents / Skills /
Auxiliary), Skill/Subagent spend donuts, and a stacked spend-over-time trend. **What drove it**:
its own tab-driven KPI strip, a Model mix donut, a (model, effort) cost-drivers grid with token
composition, and the "Most expensive sessions" ranking (rows open a per-session cost detail
dialog). Backend counterpart: `CostController.breakdown` → `CostService.breakdown[InRange]`
(`backend/.../controller/CostController.java`), served at `GET /api/cost/breakdown`.

## Files

```
CostPage/
├── CostPage.tsx                container — window context, three queries, activeTab state,
│                               emptyBreakdown fallback, selectedSession (dialog state)
├── CostPageView.tsx            view — tab-driven KPI strips, PillTabs strip, trend chart wiring,
│                               donuts (Skill/Subagent/Model mix); no queries, no context
├── costDerivations.ts          pure derivations (CATEGORY_ORDER/LABELS, categoryColorIndex,
│                               inFixedCategoryOrder, buildTrendSeries, buildModelMix)
├── costDerivations.test.ts     vitest coverage for the above
├── components/
│   ├── MoneyMapCard/           the top-level category partition ONLY — no nested drilldown
│   │                           (removed 2026-08, see gotchas)
│   ├── CostDriversCard/        (model, effort) grid with token composition + per-row model
│   │                           color dot + neutral zebra/hover row styling
│   ├── TopSessionsCard/        "Most expensive sessions" — prompt+id row shape, share-of-spend
│   │                           bar, clickable rows opening SessionCostDialog
│   └── SessionCostDialog/      per-session cost detail modal — KPI tiles, 4-segment
│                               category bar + legend, "View in Sessions" deep link
└── index.ts                    re-exports container as default
```

`McpContextPanel` was deleted in the 2026-08 Aurora handoff (see gotchas) — do not re-add it here.

## Visual layout

```
┌─ PageLayout: "Cost" ──────────────────────────────────────────────────────────┐
│ subtitle: measured from api_request, not the counter    [PageActions: ⟳ auto] │
├─────────────────────────────────────────────────────────────────────────────────┤
│ ( Where it went ) ( What drove it )   ← PillTabs, in-page                      │
├─ tab: Where it went ─────────────────────────────────────────────────────────┤
│ ┌─ 4-column StatCard grid ────────────────────────────────────────────────┐    │
│ │ Total spend │ Burn rate │ Projected 30d │ Top category                  │    │
│ └──────────────────────────────────────────────────────────────────────────┘   │
│ ┌─ MoneyMapCard ──────────────────────────────────────────────────────────┐   │
│ │ Main loop     ▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇░░░░  $291.67  71.5%                     │  │
│ │ Subagents     ▇▇▇▇░░░░░░░░░░░░░░░░░░   $82.43  20.2%                     │  │
│ │ Skills        ▇░░░░░░░░░░░░░░░░░░░░░   $13.82   3.4%                     │  │
│ │ Auxiliary     ░░░░░░░░░░░░░░░░░░░░░░    $1.30   0.3%                     │  │
│ └────────────────────────────────────────────────────────────────────────┘   │
│ ┌─ Skill mix (DonutCard) ──────┐  ┌─ Subagent mix (DonutCard) ────────────┐  │
│ └──────────────────────────────┘  └────────────────────────────────────────┘  │
│ ┌─ Spend over time (stacked AreaTrendChart, USD (stacked)) ────────────────┐  │
│ │ ← moved AFTER the Skill/Subagent mix row (was between MoneyMapCard and   │  │
│ │   the mix row before this handoff)                                       │  │
│ └────────────────────────────────────────────────────────────────────────┘   │
├─ tab: What drove it ───────────────────────────────────────────────────────────┤
│ ┌─ 4-column StatCard grid ────────────────────────────────────────────────┐    │
│ │ Requests │ Cost per 1k tokens │ Top model │ Priciest session            │    │
│ └──────────────────────────────────────────────────────────────────────────┘   │
│ ┌─ Model mix (DonutCard) ───────────────────────────────────────────────┐    │
│ └──────────────────────────────────────────────────────────────────────┘    │
│ ┌─ CostDriversCard ─────────────────────────────────────────────────────┐    │
│ │ ● Model | Effort | Requests | Cost | Share | Expensive tokens⌗ | Cache │    │
│ │                                                                  read  │    │
│ │   (● = per-row dot, colored to match its Model mix donut slice;        │    │
│ │    zebra + neutral hover on the table; ⌗ = "Expensive tokens" is       │    │
│ │    input + cache write + output summed, with a hover tooltip           │    │
│ │    breaking the three back out; cache read stays its own column       │    │
│ │    since it's billed at a steep discount)                              │    │
│ └──────────────────────────────────────────────────────────────────────┘    │
│ ┌─ TopSessionsCard ("Most expensive sessions") ──────────────────────────┐  │
│ │ prompt (bold) / id (mono, muted) · requests · share bar+% · cost        │  │
│ │ … click a row → SessionCostDialog                                       │  │
│ └───────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────┘

┌─ SessionCostDialog (MUI Dialog, maxWidth="xs") ──────────────────────────┐
│ Migrate the billing service off the legacy Stripe SDK              [×]   │
│ 8467bd17-5940-4b2d-9eed-cdcff18faa34                                      │
├────────────────────────────────────────────────────────────────────────┤
│ COST            │ SHARE OF SPEND      ← two KPI tiles                   │
│ $58.40          │ 14.3%                                                 │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▒▒▒▒▒▒▒▒▒░░░  ← Main loop / Subagents / Skills / Auxiliary  │
│ ■ Main loop $41.90 · ■ Subagents $14.20 · ■ Skills $2.00 · ■ Aux $0.30  │
│ [ View in Sessions → ]  → /sessions?sessionId=…                         │
└────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

| Container hook | Query key | Fetcher → endpoint |
|---|---|---|
| `useQuery` | `['cost-breakdown', selectionKey]` | `fetchCostBreakdown(selection)` → `GET /api/cost/breakdown?…` |
| `useQuery` | `['skill-usage', selectionKey]` | `fetchSkillUsage(selection)` → `GET /api/tool-activity/skill-usage?…` (reused from Skills & Subagents) |
| `useQuery` | `['subagent-usage', selectionKey]` | `fetchSubagentUsage(selection)` → `GET /api/tool-activity/subagent-usage?…` (reused) |

`fetchCostBreakdown` is the page's spine — its error wins `PageLayout`'s error slot; the other
two queries back the Skill/Subagent mix donuts and fall back to an empty array on failure rather
than blanking the page. `onReload` refetches all three. No page-local API module — all three
fetchers already live in the shared `api/endpoints.ts` barrel.

The `tool-context-footprint` query (`fetchToolContextFootprint`) that used to back the deleted
`McpContextPanel` is gone from this page entirely — see the gotcha below. Don't re-add it here;
the Tokens page's `ContextFootprintCard` already covers MCP servers' context footprint.

## Data flow and semantics

- **Single pipeline, stated up front.** Every figure on this page is read from `api_request` log
  records' exact per-call `cost_usd`, never the `claude_code.cost.usage` cumulative counter that
  backs the Tokens and Sessions pages. The two do not reconcile (`AGENTS.md`'s two-pipelines
  note) — `breakdown.totalCostUsd` reads a few percent below the counter-derived KPIs shown
  elsewhere for the same window, and the page's subtitle says so. Never blend a figure from this
  page with one from Tokens/Sessions in the same sentence or chart.
- **The money map partitions, it doesn't overlap.** `CostCategoryShare.costUsd` across the four
  categories always sums exactly to `breakdown.totalCostUsd` — the backend resolves a request
  tagged as both a subagent call and a skill invocation (a skill running inside a subagent) to
  SUBAGENT only, in a fixed precedence order. `MoneyMapCard` renders categories in the backend's
  own cost-descending order (not `CATEGORY_ORDER`) since ranking is the point of that card;
  everywhere else on the page (the trend chart's stacking/legend, `SessionCostDialog`'s category
  bar) uses the fixed `CATEGORY_ORDER` from `costDerivations.ts` so a category never changes
  position or colour between polls just because its rank shifted.
- **`CostCategoryShare.drilldown` / `identifiedCostUsd` still arrive on the wire but are no
  longer rendered anywhere on this page.** `MoneyMapCard` used to render a nested "of which, $X
  identified" breakdown per SUBAGENT/SKILL category; that was removed (2026-08 Aurora handoff —
  see gotchas) because the Skill mix / Subagent mix donut+list cards showed the exact same numbers
  a second time. Don't reintroduce a second rendering of this data — those two donuts are now the
  single source.
- **`buildTrendSeries`** (in `costDerivations.ts`) converts the backend's per-bucket
  `costByCategory` maps (which omit a category with zero spend in that bucket, never sending an
  explicit `0`) into one `AreaTrendSeries` per category in `CATEGORY_ORDER`, filling missing
  buckets with `0` so `AreaTrendChart` gets a dense array. Per the repo-wide stacked-chart
  labelling convention, the y-axis reads `"USD (stacked)"` and the chart is `stacked` (a Total row
  is implied by `AreaTrendChart`'s own stacked-mode tooltip). It now renders LAST in the "Where it
  went" tab, after the Skill/Subagent mix donut row (previously 2nd, right after `MoneyMapCard`).
- **`buildModelMix`** (in `costDerivations.ts`) is the single source for every model-keyed
  visual on the "What drove it" tab: it groups `breakdown.modelEffort` (one row per (model,
  effort) pair) by model, sums `costUsd`, and ranks cost-descending, assigning each model a
  `colorIndex` equal to its rank. The Model mix donut, the "Top model" KPI, and
  `CostDriversCard`'s per-row color dot all read a model's color from this one ranking (via a
  `Map<model, colorIndex>` the view builds once) — a model must wear the same color everywhere on
  the tab no matter how many effort-level rows it has in the drivers grid. Don't derive a model's
  color independently in more than one place (and don't substring-match on a model name like the
  design mockup's own JS does — that's mockup-only convenience data, not a generalizable rule).
- **The Skill/Subagent donuts are dollar-valued, not call-valued** — `toDonutSlices` (in the view)
  reads `row.costUsd` off the same `IdentifierUsageRow[]` the Skills & Subagents page renders as
  calls, filtering to rows with `costUsd > 0`. This page adds no new backend query for them; it
  reuses `/api/tool-activity/skill-usage` and `/subagent-usage` purely for their existing cost
  fields.
- **`CostDriversCard` never derives a per-token-kind dollar figure.** `cost_usd` is one number per
  request; an earlier revision of this dashboard's cost model tried a token-rate-table estimate
  and measured it running 2–3× off real spend (see `V14`'s migration header). The grid shows
  tokens and cost side by side and correlates them visually, never multiplies them.
- **`CostDriversCard`'s two token columns ("Expensive tokens" = input + cache write + output,
  and "Cache read" kept separate) each carry a hover tooltip styled to match the trace waterfall's
  token badges** (`TraceDetailPage/components/SpanWaterfallRow`'s `SpanFullRateBadge`/
  `SpanCacheReadBadge`) — bold mono header line, a `tipGridSx` label/value grid, no dividers; the
  Cache read tooltip additionally reuses `cacheHitRateLabel` from
  `../TracesPage/tokenBreakdown` (imported cross-page, same as `TraceDetailPage` already does) so
  the "N% of cacheable tokens" figure and its `>99%` rounding rule can't drift from the trace
  pages' own cache-hit chips. `tipGridSx`/`TipRow` are duplicated locally in `CostDriversCard.tsx`
  rather than imported, since the trace-page versions are file-local (not exported) — if a third
  caller needs this pattern, that's the point to extract a shared component instead of a third
  copy.
- **`effort` renders as "not recorded", never a default level**, whenever
  `CostModelEffortCell.effort` is `null` (~7% of `api_request` rows carry no effort attribute) —
  matches the same rule the Traces page's span effort follows.
- **`TopSessionsCard`'s rows and `SessionCostDialog`'s footer both reuse `sessionsDeepLink`**
  exported from `pages/SessionsPage/SessionsPage.tsx` (not a hand-written `?sessionId=` template
  literal), the same helper the Tokens page's `SessionCacheEfficiencyDialog` uses — the deep-link
  param name lives in exactly one place.
- **`CostSessionShare`'s four `*CostUsd` fields are a decomposition of `costUsd`, not a second
  measurement** — same "own-sum" pattern as `SessionCacheEfficiencyRow`'s token split on the
  Tokens page. `SessionCostDialog`'s category bar divides by its own segment sum rather than by
  `session.costUsd`, so a contract drift shows up as segments that don't fill the track instead of
  as overflow.

## Gotchas

- **This page buckets background subagent spend by when it burned; the Sessions drawer attributes
  it to the turn that dispatched it. Don't reconcile the two turn-by-turn.** A turn can dispatch a
  fire-and-forget subagent whose `Agent` span closes in milliseconds while the subagent itself keeps
  issuing `api_request` records for another hour — past its own `claude_code.interaction` root span
  and past the next user prompt. Every query on this page classifies each request row by *its own*
  `query_source`/`skill_name` and *its own* `timestamp`, with no `prompt.id` join and no trace
  correlation, so that spend lands in SUBAGENT in the trend bucket where it actually happened. The
  Sessions prompt drawer answers the other question — it trace-correlates the same dollars back onto
  the dispatching turn and flags the post-root-span portion with a `+$X background` badge (see
  `pages/SessionsPage/CLAUDE.md`). Both are correct; they will disagree about *when* and *whose*,
  and no arithmetic makes a turn card's total match a trend bucket. Same rule as the
  single-pipeline note above: never put a figure from this page and one from the Sessions drawer in
  the same sentence.
- **The SUBAGENT drilldown's identification gap is not a detachment problem** — worth knowing even
  though the drilldown itself no longer renders on this page (it still backs the Skill mix /
  Subagent mix donuts via the same underlying identification query).
  `aggregateSubagentCostByModelInRange` reaches only a dispatch's direct child LLM spans (its own
  Javadoc's KNOWN LIMITATION), which reads like it should lose a detached subagent's whole hour of
  work. Measured over 14 days of real data it doesn't: background subagent spend came back **100%
  identified**, and the entire unidentified remainder sat in the pre-root-span portion. Whatever
  `identifiedCostUsd` is missing, span detachment isn't the cause.
- **`emptyBreakdown` in the container is a fully-typed zero state**, same idiom as
  `TokensPage`'s `emptySummary` — it exists so the view never needs `breakdown?.` guards on every
  field access while the spine query is loading or errored.
- **`activeTab` is in-page `useState`, not a route** — same reasoning as `TokensPage`'s
  `activeTab`: both tabs share one window selection and one reload, and `/cost` stays a single
  bookmarkable page. Switching tabs clears `selectedSession` (`handleActiveTabChange`), same as
  `TokensPage`'s `selectedCacheEfficiencyRow` — the dialog unmounts with the "What drove it" panel,
  so keeping the selection would re-open it on the way back.
- **All three queries run regardless of the active tab**, matching `TokensPage`'s documented
  choice — switching tabs must not change what the reload button or auto-refresh cover.
- **`costPer1k` and `burnRatePerDay` are computed in the view, not the backend.** The backend
  sends `burnRatePerHour` (× 24 here) and enough token totals to derive cost-per-1k client-side;
  there was no reason to duplicate that arithmetic in a service that already returns the inputs.
  **It renders through its own `COST_PER_1K_FORMATTER` (5 decimals), not the shared 2-decimal
  `USD_FORMATTER`/`usdOrDash`.** Cache-read tokens dominate the token total but cost comparatively
  little, so real values are routinely sub-mill (e.g. `$0.00033` on a cache-heavy window) —
  a 2-decimal formatter rounds that straight to `"$0.00"`, and even 3 decimals (the original
  choice here) still rounds it to `"$0.000"`, both indistinguishable from an actual zero. 5
  decimals matches the Tokens page's own cost-per-1k figure (`CostSummary.costPer1k`, formatted
  server-side by `MetricService.formatCostPer1k` as `"$%.5f"`) — same concept, same precision,
  computed on two different sides of the API only because this page's version didn't exist
  server-side yet.
- **A category with zero categories at all (`breakdown.categories.length === 0`) is a real "no
  priced requests in this window" answer**, not a loading state — `MoneyMapCard` and
  `CostDriversCard` both render an explicit empty message rather than a blank card, guarded by
  `!isLoading` so the message doesn't flash before the first fetch resolves.
- **2026-08 Aurora handoff — KPI strip is now tab-driven, MoneyMapCard's drilldown was removed,
  a Model mix donut was added, and McpContextPanel was deleted.** Applied by diffing
  `design_handoff_cost_page_redesign/` (the mockup's own README + `Aurora Cost Mockup.html`)
  against the live files and porting the described deltas by hand — same discipline as the
  Tokens page's `TokenCostByModelCard` merge (see that page's CLAUDE.md gotcha for the general
  pattern), not a wholesale overwrite from the handoff's own static HTML/CSS/JS. Concretely:
  - The single static 5-stat KPI row above the tabs became two 4-stat rows, one per tab, switching
    on `activeTab` — "Where it went" gains a **Top category** stat
    (`breakdown.categories[0]`, already cost-descending), "What drove it" gains **Top model**
    (`buildModelMix(breakdown.modelEffort)[0]`) and **Priciest session**
    (`breakdown.topSessions[0]`).
  - **Top category's and Top model's `sub` is JSX, not a plain string** — the mockup renders the
    leading dollar figure in `<em>` (primary-colored, bold) with the trailing "· X% of spend" in a
    dimmer tone (confirmed against `screenshots/what-drove-it.png`'s Top model tile: `$198.40` is
    bold purple, `· 48.6% of spend` is plain gray). Both KPIs pass `sub` a fragment — a
    `Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}` wrapping the formatted
    cost, followed by the plain-text share — rather than one flat `text.secondary` string. Keep
    the two in sync if either changes; they're the same visual pattern on two different tabs.
  - **`McpContextPanel` was deleted outright** (`frontend/src/pages/CostPage/components/
    McpContextPanel/`, both files) — it duplicated the "What's filling the context window"
    ranking already on the Tokens page's Cache & Context tab (`ContextFootprintCard`), same MCP
    servers, same "no cost_usd of their own" framing. Its `tool-context-footprint` query was
    removed from `CostPage.tsx` along with it (no orphaned fetch). Don't re-add MCP context
    footprint content anywhere on this page — the Tokens page card is the one place for it.
  - **"Biggest sessions" → "Most expensive sessions"**, rebuilt to the Tokens page's
    `CacheEfficiencyRankCard` row idiom (prompt bold + id mono/muted below, `table-layout: fixed`
    + `<colgroup>`, `firstUserPrompt ?? "No prompt captured"`), plus a Requests column and a
    share-of-spend `LinearProgress` + percentage (`session.costUsd / breakdown.totalCostUsd *
    100` — `totalCostUsd` is passed down as a prop, never hardcoded). Rows are clickable and
    keyboard-activatable (`tabIndex={0}`, Enter/Space), opening the new `SessionCostDialog`.
  - **The Model mix donut needed two new opt-in `DonutCard` props, not a page-local rebuild.**
    Confirmed against `screenshots/what-drove-it.png`: the mockup's Model mix card is a *wide*
    single-card layout (donut fixed-size on the left, the ranked list flexing to fill the rest —
    it isn't paired 2-up like Skill mix/Subagent mix, so there's a full card width to use) and
    each ranked-list row carries a full-width colored progress bar below it (rank number + dot +
    name + value/% on one line, bar beneath — no divider between rows). Neither existed on
    `DonutCard` before, and `BreakdownList` (the other candidate) was ruled out because its
    `'stacked'` layout deliberately treats `showRank`/`showColorDot` as mutually exclusive (see
    `frontend/CLAUDE.md`'s `BreakdownList` note) while this row needs both simultaneously —
    `DonutCard`'s `ranked` legend already renders rank + dot together, so it was extended instead:
    `orientation?: 'vertical' | 'horizontal'` (default `'vertical'`, the existing stacked
    ring-on-top layout every other caller keeps) and `showBars?: boolean` (default `false`, adds
    the per-row bar and drops the row divider). Model mix passes both, plus the new
    `description` prop (`"N models identified, ranked by spend"`, also confirmed in the
    screenshot) — a generic muted caption line under the title, rendered only when passed.
    **Skill mix and Subagent mix are NOT yet updated to match** — the README's own prose (section
    2) describes the identical ranked-list-with-bars treatment for both ("each showing a donut
    plus the full ranked list with progress bars ... with an 'N skills/subagents identified'
    caption"), and the mockup's `drawDonut()` function draws all three rings identically, but
    their tab's screenshot (`nav-and-where-it-went.png`) is corrupted (verified all-black pixels),
    so this pass only fixed what a working screenshot could confirm. Applying the same
    `showBars`/`description` props to those two (they'd stay `orientation="vertical"`, still
    paired 2-up) is the likely next step — do it deliberately, not as a silent side effect of an
    unrelated change.
  - **"Spend over time" is a `Paper variant="outlined"` card, not a plain bordered `Box`.** The
    handoff's `.card` class applies the same elevated surface fill to every panel including this
    one; the pre-existing implementation used a bare `Box` with only a divider border, which
    rendered flat/transparent against the page background instead of matching the other cards on
    the tab. Match `TokensPage`'s "Token usage over time" card (`Paper variant="outlined"` +
    `Typography variant="subtitle1"` title), which already got this right.
  - **The "Priciest session" KPI tile truncates the session id to its first 8 characters
    (`SESSION_ID_PREVIEW_LENGTH` in `CostPageView.tsx`) with the full id in a hover tooltip —
    the one deliberate exception to this dashboard's usual never-truncate-a-session-id rule**
    (see the Tokens page's `CacheEfficiencyRankCard` gotcha for that rule and why it exists
    elsewhere). Justified here specifically because: (1) the KPI tile is genuinely
    space-constrained — four tiles across one row, sub-caption sharing space with "N requests ·
    ", (2) it is not the page's primary identity surface for that session — the "Most expensive
    sessions" row directly below it (and `SessionCostDialog`'s header) both print the same
    session's id in full and un-truncated, so a reader who needs to match it elsewhere always has
    a full-id surface one glance away. Don't extend this truncation to the table row or the
    dialog header — both keep the full id, same as `CacheEfficiencyRankCard` and
    `SessionCacheEfficiencyDialog`.
  - **`CostDriversCard` gained a per-row model color dot** (left of the model name), rank-colored
    via `buildModelMix` — see the "Data flow" note above. It also gained neutral zebra striping
    (`nth-of-type(even)`) and a neutral gray row hover, replacing the table's previously unstyled
    rows; both built from `theme.custom?.progressTrack` (the same neutral track token
    `BreakdownList`/`CacheEfficiencyRankCard` already use) via `alpha()`, not a new raw color.
    `TopSessionsCard` picked up the same zebra/hover treatment for consistency between the two
    dense tables on the "What drove it" tab.
  - **`SessionCostDialog` (new component) reuses the Tokens page's `SessionCacheEfficiencyDialog`
    shell idiom**: a `lastSession` state updated during render whenever a non-null `session` with
    a different `sessionId` arrives, body split into a `DialogBody` that only ever renders against
    `session ?? lastSession` (never a null-guarded prop) — so the dialog's content stays visible
    through MUI `Dialog`'s ~200ms exit transition instead of blanking when the caller nulls the
    selection. Its 4-segment bar (Main loop / Subagents / Skills / Auxiliary) uses
    `CATEGORY_ORDER`/`CATEGORY_LABELS`/`categoryColor` from `costDerivations.ts`, the same colors
    as the money map and trend chart, so a category never wears two colors across this page.
  - **Left nav**: `/cost` moved to the first item in the Activity group (`navGroups.tsx`), and
    `TokenUsageIcon`'s glyph (`NavIcons.tsx`) changed from a wave/pulse squiggle to a simple
    document-with-lines glyph, so it reads distinctly at a glance next to the Cost icon (both are
    circular with a squiggle-in-circle motif otherwise).
