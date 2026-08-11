# Tokens page

Token usage dashboard, split across two in-page tabs. **Overview**: spend KPIs, a stacked-area
time-series chart of the four token types over the selected window, a token-composition donut
with cache-efficiency health gauge, and a per-model token breakdown. **Cache & Context**: a
worst-cache-efficiency session ranking (rows open a per-session detail dialog) and an estimated
per-tool context footprint.
Backend counterpart: `SessionController.tokenUsage` →
`MetricService.aggregateTokenUsage[InRange]` (`backend/.../controller/SessionController.java`),
served at `GET /api/sessions/token-usage`, plus `GET /api/sessions/cache-efficiency` and
`GET /api/tool-activity/context-footprint`.

## Files

```
TokensPage/
├── TokensPage.tsx              container — window context, three queries, emptySummary fallback,
│                               activeTab state, selected cache-efficiency row (dialog state)
├── TokensPageView.tsx          view — derives KPI cards, chart series, donut slices, cache-ratio
│                               color/label; renders the PillTabs strip and the two tab panels
│                               (TokensPageTab, TOKENS_PAGE_TABS)
├── components/
│   ├── TokenSummaryCards/      four-tile KPI strip (Total cost · Total tokens · Models used · Top model)
│   │   ├── TokenSummaryCards.tsx
│   │   └── index.ts
│   ├── TokenByModelCard/       per-model token sums — name + colour dot, big total, share bar
│   │   ├── TokenByModelCard.tsx
│   │   └── index.ts
│   ├── TokenCompositionCard/   token-mix donut (SVG hand-built) + cache-efficiency gauge
│   │   ├── TokenCompositionCard.tsx
│   │   └── index.ts
│   ├── CacheEfficiencyRankCard/  worst-cache-efficiency sessions as a 4-column table; the
│   │   │                         Efficiency cell's bar length = the ratio itself; rows click
│   │   ├── CacheEfficiencyRankCard.tsx     through to the detail dialog
│   │   └── index.ts
│   ├── SessionCacheEfficiencyDialog/  per-session detail modal — band chip, two KPI tiles,
│   │   ├── SessionCacheEfficiencyDialog.tsx  4-segment all-kinds token bar + legend, and a
│   │   └── index.ts                          "View in Sessions" deep link
│   ├── cacheEfficiencyBandColors.ts  band → theme color + short chip labels — the single
│   │                                 source of truth, cross-page-imported by SessionsPage's
│   │                                 CacheEfficiencyCell too (plain module, no index.ts)
│   └── ContextFootprintCard/   per-tool context footprint (BreakdownList, showRank) + "estimated" chip
│       ├── ContextFootprintCard.tsx
│       └── index.ts
├── tokenKindColors.ts          TOKEN_KIND_COLORS / TOKEN_KIND_LABELS — one color+label per
│                               token kind, shared by the donut (mixSlices), the trend chart
│                               (series), and SessionCacheEfficiencyDialog's token bar so a
│                               kind never wears two colors on this page (plain module, no
│                               index.ts)
└── index.ts
```

## Visual layout

```
┌─ PageLayout ────────────────────────────────────────────────────────────────┐
│ "Token Usage" title/subtitle        [PageActions: WindowSelector ⟳ auto]    │
├─────────────────────────────────────────────────────────────────────────────┤
│ ( Overview ) ( Cache & Context )   ← PillTabs, in-page (no route change)    │
├─ tab: Overview ─────────────────────────────────────────────────────────────┤
│ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────────────┐  │
│ │ Total cost   │ │ Total tokens │ │ Models used  │ │ Top model          │  │
│ │ (accent KPI) │ │              │ │              │ │ colour dot + name  │  │
│ └──────────────┘ └──────────────┘ └──────────────┘ └────────────────────┘  │
│  ← TokenSummaryCards: 4-column grid (xs:1, sm:2, lg:4) ──────────────────  │
│                                                                             │
│ ┌─ Paper: Token usage over time ────────────────────────────────────────┐  │
│ │ title + ⓘ tooltip      [AreaTrendLegend — Cache read/creation/Input/  │  │
│ │                          Output; click to toggle series visibility]   │  │
│ │ AreaTrendChart (stacked=false, yScale=log, height=320)                │  │
│ └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│ ┌─ TokenCompositionCard ────────────────────────────────────────────────┐  │
│ │ ┌──── donut (SVG) ────┐  │  Cache read ratio                         │  │
│ │ │  total-tokens label  │  │  big % + LinearProgress bar (0–100%)     │  │
│ │ └─────────────────────┘  │  health badge (healthy / mixed / poor)    │  │
│ │ descriptive legend rows  │  savings note (savedTokens)               │  │
│ └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│ ┌─ TokenByModelCard ────────────────────────────────────────────────────┐  │
│ │ "Token sum by model"                                                  │  │
│ │ BreakdownList (layout="column-card") — name/dot · big total · bar    │  │
│ └───────────────────────────────────────────────────────────────────────┘  │
├─ tab: Cache & Context ──────────────────────────────────────────────────────┤
│ ┌─ CacheEfficiencyRankCard ─────────────────────────────────────────────┐  │
│ │ "Worst cache efficiency" + ⓘ · noise-floor subtitle                   │  │
│ │ Session                              │ Efficiency    │  Cost │ Cached   │  │
│ │ 8f2a91cd-6b34-4e02-9a71-c5d0e8f21a44 │ ▇▇▇░░░░░ 18%  │ $9.42 │ 210K/1.2M│  │
│ │ … click a row → SessionCacheEfficiencyDialog                          │  │
│ └───────────────────────────────────────────────────────────────────────┘  │
│ ┌─ ContextFootprintCard ────────────────────────────────────────────────┐  │
│ │ "What's filling the context window" [estimated] + ⓘ                   │  │
│ │ BreakdownList per tool — rank № · bytes · ~est. tokens · calls · p95  │  │
│ └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘

┌─ SessionCacheEfficiencyDialog (MUI Dialog, maxWidth="xs") ──────────────┐
│ full session id (mono)                                             [×]  │
│ ( Poor cache efficiency )   ← band chip, band color at 16% tint         │
├─────────────────────────────────────────────────────────────────────────┤
│ CACHE EFFICIENCY        │ COST          ← two KPI tiles                 │
│ 18%  (band color)       │ $9.42                                         │
│ ▓▓▓▓▓▓▒▒▒▒▒▒▒▒░░░▒▒  ← Cache read / Input / Cache creation / Output,    │
│                         width = share of all four                       │
│ ■ Cache read 210K · ■ Input 825K · ■ Cache creation 145K · ■ Output 38K │
│ [ View in Sessions → ]  → /sessions?sessionId=…                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

| Source                   | Query key                         | Fetcher → endpoint |
|--------------------------|-----------------------------------|--------------------|
| `TokensPage` (`useQuery`) | `['token-usage', selectionKey]`   | `fetchTokenUsage(selection)` → `GET /api/sessions/token-usage?…` |
| `TokensPage` (`useQuery`) | `['session-cache-efficiency', selectionKey, limit]` | `fetchSessionCacheEfficiency(selection, 8)` → `GET /api/sessions/cache-efficiency?…&limit=8` |
| `TokensPage` (`useQuery`) | `['tool-context-footprint', selectionKey]` | `fetchToolContextFootprint(selection)` → `GET /api/tool-activity/context-footprint?…` |

`selectionKey` is `'preset:<minutes>'` or `'custom:<start>:<end>'`. No sub-card fetches
independently — the container owns all three queries and passes plain props down.

The token summary is the page's spine, so its error wins `PageLayout`'s error slot; the two
ranked lists are supplementary, and when only one of them fails the page still renders with
that card showing its own empty state. `onReload` refetches all three.

## Data flow and semantics

- `TokensPage` reads `selection` / `setSelection` / `autoRefresh` / `setAutoRefresh` from
  `useWindowContext()`. It builds `selectionKey`, sets `refetchInterval` to
  `AUTO_REFRESH_INTERVAL_MS` (60 000 ms) only when `autoRefresh && selection.kind === 'preset'`
  (custom ranges have a fixed end, so polling would re-fetch the same slice), and passes the
  resolved `summaryQuery.data ?? emptySummary` as the `summary` prop to the view.
- `activeTab` (`TokensPageTab` — `'overview'` | `'cache-context'`) is `useState` in the
  container, defaulting to `'overview'`; the view renders `TOKENS_PAGE_TABS` through the shared
  `PillTabs` and switches panels on it. It is deliberately **not** in the URL or a route:
  the two panels share one window selection and one reload, and `/tokens` stays a single
  bookmarkable page. (Contrast `ToolActivitySection`, whose tabs are real child routes because
  each tab is its own page with its own queries.)
- `selectedCacheEfficiencyRow` is the container's dialog state, and it holds the **clicked row
  object, not its session id**. Every field the dialog renders is already on the row (the ranking
  response carries the four-way token split), so opening it costs no fetch — and holding the row
  rather than re-deriving it from `cacheEfficiencyQuery.data` means the 60-second poll can't
  blank the dialog or close it out from under the user when a session re-ranks or drops off the
  list mid-read. The trade-off is deliberate: an open dialog shows the figures as of the click.
  Switching tabs clears it (`handleActiveTabChange`) — the dialog unmounts with the panel, so
  keeping the selection would re-open it on the way back.
- `emptySummary` is a fully-typed zero-state constant declared in the container. It prevents the
  view and its sub-cards from having to guard against `undefined` on every field access.
- `TokensPageView` is the only place where data is derived. It computes:
  - `totalTokens` — the four scalar fields summed for the KPI tile and the donut center.
  - `mixSlices` — the four `CompositionSlice` entries (colored per kind from `tokenKindColors.ts`'s
    `TOKEN_KIND_COLORS`, not a raw `colorForIndex(0–3)` call), sorted largest-first, with
    `TYPE_DESCRIPTIONS` descriptions; passed to `TokenCompositionCard`.
  - `series` and `axisDates` — mapped from `summary.points` (each `TokenUsagePoint` has
    `timestamp`, `input`, `output`, `cacheCreation`, `cacheRead`); also colored per kind from
    `TOKEN_KIND_COLORS`, so a kind's color matches `mixSlices` exactly (see the "Color ordering"
    gotcha below for why this used to disagree).
  - `hasChartData` — true when `axisDates.length >= 2` (one bucket is not plottable on a log scale).
  - `ratioColor` / `ratioLabel` — threshold-band helpers that map `cacheReadRatio` (0–1), via
    `cacheEfficiencyBand`, through the shared `cacheEfficiencyBandColor(band, theme)` (the same
    mapping `CacheEfficiencyRankCard`, `SessionCacheEfficiencyDialog`, and the Sessions grid's
    `CacheEfficiencyCell` all use) rather than a page-local color ternary.
  - `summaryCards` — four `TokenSummaryCard` objects for `TokenSummaryCards`; cost card uses the
    pre-formatted `summary.cost.spend24h` string (not a raw number), delta sign is read from
    `summary.cost.deltaPct` prefix (`-` → green down-arrow, otherwise red up-arrow).
  - `windowLabel` — derived from the current `selection` so cost captions read "vs. prev Last 24 h"
    (not a hardcoded "24h").
- `TokenByModelCard` receives `summary.byModel` (`TokenModelShare[]`) verbatim; it delegates
  rendering to `BreakdownList` with `layout="column-card"`.
- `TokenCompositionCard` contains the only hand-built SVG on this page: a donut built from
  `<circle>` arcs using `strokeDasharray` / `strokeDashoffset` math. The ratio gauge uses a
  standard MUI `LinearProgress`.
- The area chart uses the shared `AreaTrendChart` with `stacked={false}` and `yScale="log"` so
  that cache-read (large) and output (small) series are both visible without one drowning the
  other. Series visibility is managed by `useSeriesVisibility` from `AreaTrendChart`; the
  `AreaTrendLegend` in the card header provides the toggle UI.

## Gotchas

- **All three queries run regardless of the active tab.** The two Cache & Context cards fetch
  (and poll) while Overview is showing, so switching tabs is instant and the header's polling
  indicator means the same thing on both. Don't "optimize" this into `enabled: activeTab ===
  'cache-context'` — it would make the reload button and auto-refresh cover a different set of
  data depending on which tab happened to be open, and the noise floor already keeps the
  cache-efficiency response small.
- **Cache efficiency has exactly one definition, and it lives in `lib/cacheEfficiency.ts`.**
  It is `cacheRead / (input + cacheCreation + cacheRead)` — the share of input-side tokens
  served from cache. Output is excluded (generated, never sent); cache-creation is deliberately
  kept in the denominator, because dropping it would let a session that constantly rebuilds its
  cache read as efficient. Four places compute this and must move together: this module,
  `MetricService.aggregateTokenUsage[InRange]`'s `cacheReadRatio` (the gauge),
  `MetricPointRepository.aggregateSessionSummaries`' whitelisted `cacheEfficiency` `ORDER BY`
  (the Sessions grid column's server-side sort), and
  `aggregateWorstCacheEfficiencySessions` (this page's ranking). The gauge's bands are the
  shared `cacheEfficiencyBand` bands (≥85% strong, ≥60% mixed) — **not** the old page-local
  0.7/0.4 pair, and **not** the old `cacheRead / (cacheCreation + cacheRead)` denominator that
  made this page's number disagree with the Sessions column.
- **The dialog's four segments are a decomposition, not a second measurement — and the bar's
  denominator is deliberately not the KPI's.** `SessionCacheEfficiencyRow` carries all four kinds
  individually (`inputTokens` / `cacheCreationTokens` / `cacheReadTokens`, which the backend
  guarantees sum to `inputSideTokens`, plus `outputTokens`); they are the same aggregation one
  level less collapsed, which is what lets the dialog open with no second fetch
  (`MetricPointRepository`'s ranking query selects the kinds and `SessionCacheEfficiency` derives
  `inputSideTokens()` / `totalTokens()` from them, so the sums can't drift;
  `TokenTelemetryQueryIntegrationTest` pins the per-kind reads). The **bar spans all four kinds**
  — it answers "what did this session spend its tokens on" — while the **cache-efficiency KPI
  above it excludes output** (generated rather than sent, so the cache could never have served
  it). They therefore do not line up on purpose: the Cache read segment is always narrower than
  the KPI's percentage, by exactly the output share. Don't "fix" that by dropping output from the
  bar or by adding it to the ratio. The bar still divides by its own segment sum rather than by
  `row.totalTokens`, so a contract drift shows up as segments that don't fill the track instead
  of as overflow. A segment with `value: 0` (an explicitly supported case — see the backend test
  `decomposesTheRatioDenominatorIntoItsThreeInputSideKinds`) still gets a legend row reading "0",
  but the bar filters it out before mapping to chips — the `MINIMUM_SEGMENT_WIDTH` floor would
  otherwise paint a visible colored sliver for a value that isn't actually there.
- **The context-footprint rows lead with a rank number, not a color dot.** `ContextFootprintCard`
  passes `BreakdownList`'s `showRank` (not `showColorDot`) — ordering is the whole claim of that
  card, and its bar colors are decorative here, shared with nothing else on the page (unlike the
  token-kind colors, which mean something everywhere they appear). The bars stay colored via
  `colorIndex`; only the leading marker changes. `showRank` exists on the `'stacked'` layout
  only, and wins over `showColorDot` if both are somehow passed.
- **The dialog keeps rendering the last-selected row while it closes.** `open={row != null}`
  means MUI's Dialog stays mounted and visible through its ~200ms exit transition after the
  caller nulls the row — `SessionCacheEfficiencyDialog` guards against that the same way
  `SpanInspectorDrawer` (`TraceDetailPage`) does: a `lastRow` state updated during render
  whenever a non-null `row` arrives with a different `sessionId`, and the body (split into an
  inner `DialogBody`) renders against `row ?? lastRow`. `DialogBody` never sees a null row, so it
  has no `row?.` fallbacks — don't reintroduce optional chaining there as a shortcut; render
  `DialogBody` only when `row ?? lastRow` is non-null instead.
- **Session ids print in full in the ranking table**, not as the mockup's leading uuid segment
  with the rest on hover. Every other surface that names a session — the prompt-timeline panel
  header, the detail dialog, the `?sessionId=` deep link — prints the whole id, and a truncated
  one here would be an id the user can't match against any of them. The column is `nowrap` mono
  and sized for a full uuid; don't re-shorten it to buy horizontal room. The table itself is
  wrapped in an `overflowX: 'auto'` `Box` (same idiom as `SessionsPageView`'s table wrapper) so a
  narrow viewport scrolls the ~700px-wide table instead of overflowing the card.
- **Band colors live in `components/cacheEfficiencyBandColors.ts`, not in either component —
  and not just this page's components anymore.** The rank table's fill/percentage, the dialog's
  chip/KPI, and (via a cross-page import) the Sessions grid's `CacheEfficiencyCell` must always
  name the same band in the same color; `lib/cacheEfficiency.ts` owns the bands themselves but is
  deliberately theme-free, so the paint (and the short chip labels) sit in this module.
  `TokensPageView`'s `ratioColor` (the composition-card health badge) also calls
  `cacheEfficiencyBandColor` now, rather than its own `{strong,mixed,weak,unknown}` object
  literal — that literal used to disagree with this mapping (`weak` → `error.main` there vs.
  `warning.main` here), so the same session could read "healthy"-toned in one place and
  "alarm"-toned in another for the identical ratio. The mapping is ≥85% `success.main`, ≥60%
  `text.primary`, else `warning.main`, `text.disabled` for the undefined ratio. The Aurora mockup
  tints "mixed" with `--primary`; the repo keeps `text.primary` so every one of these agrees.
- **Token-kind colors come from `tokenKindColors.ts`'s `TOKEN_KIND_COLORS`, not a bare
  `colorForIndex(0–3)` call.** `mixSlices`, the trend chart's `series`, and the dialog's
  `segments` all key their color off the token kind (`cacheRead` / `input` / `cacheCreation` /
  `output`) through that shared map, so "Input" (for example) is always the same color whether
  it's a donut slice, a trend line, or a dialog bar chip. Before this existed, the trend chart
  assigned `colorForIndex` by its series array's position (`[Cache read, Cache creation, Input,
  Output]`) while the donut assigned it by a different literal array order
  (`[Cache read, Input, Cache creation, Output]`) — same four colors, but Cache creation and
  Input landed on each other's color between the two. See the "Color ordering" gotcha below for
  what's still legitimately different (display order, not color).
- **"View in Sessions" is a deep link built by `sessionsDeepLink(sessionId)`** (exported from
  `SessionsPage.tsx`, not a hand-written template literal — the `?sessionId=` param name lives in
  exactly one place, `DEEP_LINK_SESSION_PARAM`), and the Sessions page consumes the param once.
  `SessionsPage` seeds `openSessionId` from that param at mount and then strips it from the
  URL (`replace: true`). Two consequences: the target session's detail drawer only auto-opens
  if that session is on the Sessions table's **first page** under its default sort (there is no
  server-side "which page is this session on" lookup — when it isn't, the Sessions page's
  `sessionPromptsQuery` stays disabled rather than firing a wasted whole-session fetch for a row
  that can never render), and a reload after the user closes the row does not re-open it. Don't
  turn the param into two-way-bound state — expansion is ordinary page state everywhere else on
  that page.
- **`ContextFootprintCard`'s token figure is an estimate and must stay visually separate from
  the exact cards.** Living on the Cache & Context tab is part of that separation — don't move
  it next to the KPI strip or the composition donut. It is `bytes / 4`, carries its own
  "estimated" chip, and leads with byte
  values for that reason. Two things it is not: (1) billed spend — never add it to or compare
  it against `TokenUsageSummary`; (2) a full accounting of cost — a tool result is re-sent with
  every later request in its session, so the one-time size understates what it actually costs.
  Its `calls` also runs lower than the Tool Calls page's count, because calls that reported no
  `tool_result_size_bytes` are excluded rather than counted as zero.
- **The context footprint counts tools the tuning report deliberately skips.** Agent/WebFetch
  and image reads are excluded from the report's oversized-results list because nothing in
  AGENTS.md can tune them; they are included here because this card asks a different question
  — what is filling the window — and "delegate this to a subagent" is one of the levers the
  comparison exists to inform.
- **Rows past the top 8 collapse into "Other" rather than being dropped**, so the percentages
  still sum to 100. MCP tool names (`mcp__server__tool`) proliferate, and a silent top-N would
  make the visible shares look complete when they weren't.
- **An empty cache-efficiency ranking is a real answer, not a failure.** The server applies a
  noise floor (`tuning.cache-efficiency-minimum-input-tokens`, default 100k input-side tokens)
  before ranking, because a session that made two small calls can sit at 0% without anything
  being wrong. `CACHE_EFFICIENCY_FLOOR_LABEL` in the view mirrors that default for the card's
  copy only — the server owns the real floor.
- **`summary.cost.spend24h` is a pre-formatted string** (e.g. `"$4.23"`), not a raw number.
  The field is named `spend24h` for historical reasons but always reflects the selected window's
  spend — don't treat it as a 24-hour-only metric. The same applies to `deltaPct`, `burnRate`,
  `projected30d`, and `costPer1k`.
- **`summary.byModel[].tokens` is a pre-formatted string** (e.g. `"7.8M"`). `TokenByModelCard`
  renders it as-is; don't pass it through `formatCompact`.
- **Log scale requires at least two points.** The chart is hidden and `emptyMessage` is shown
  when `axisDates.length < 2`. If there is exactly one bucket, a targeted message says so rather
  than a generic "No data."
- **Cache-read-ratio denominator guard.** `denominatorEmpty` is `cacheCreationTokens + cacheReadTokens === 0`.
  When true, `ratioBandLabel` returns "No cache activity in this window" and the ratio display
  renders `—` rather than `0.0%`.
- **Display ordering in chart vs. donut still differs — colors no longer do.** The area chart's
  `series` array is built in `[Cache read, Cache creation, Input, Output]` order (that's the
  stacking/legend order the chart renders). The donut's `mixSlices` starts from a different
  literal order (`[Cache read, Input, Cache creation, Output]`) and is then **sorted
  largest-first** so the arc order tracks data shape; its legend shows the pre-sort label order.
  Both now pull each slice/series' color from `tokenKindColors.ts`'s `TOKEN_KIND_COLORS` keyed by
  kind rather than by array position, so — unlike before — the two orderings no longer imply two
  different colors for the same kind.
- **`TokenCompositionCard` calls `useTheme` directly** for the SVG track color and alpha.
  It is a presentational component that deviates minimally from the pure-props rule; it does not
  call any hook that performs I/O or owns state.
