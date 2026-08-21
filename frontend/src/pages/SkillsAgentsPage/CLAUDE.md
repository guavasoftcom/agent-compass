# Skills & Subagents page

A section tab under [Tool Usage (`/tools`)](../ToolActivitySection/ToolActivitySection.tsx)
that shows skill and subagent invocation breakdowns for the selected window: four KPI tiles, a
side-by-side pair of `DonutCard` mix charts (with per-model coverage ticks), and a side-by-side
pair of model-first "by model" ranked-block cards. Backend counterpart: `ToolActivityController`
→ `LogService` (`backend/.../controller/ToolActivityController.java`), endpoints
`GET /api/tool-activity/skill-usage` and `GET /api/tool-activity/subagent-usage`.

## Files

```
SkillsAgentsPage/
├── SkillsAgentsPage.tsx           container — reads useSectionContext(), runs two queries,
│                                  derives share + model colours + coverage models + by-model blocks
├── SkillsAgentsPageView.tsx       view — StatCard strip + two DonutCards + two
│                                  ModelFirstBlocks cards; no queries, no context
├── skillsAgentsDerivations.ts     pure derivations (withShare, buildModelColorIndexes,
│                                  buildModelCoverageModels, buildModelFirstBlocks)
├── skillsAgentsDerivations.test.ts vitest coverage for the above
├── components/
│   └── ModelFirstBlocks/          "Skills by model" / "Subagents by model" — one block per
│       ├── ModelFirstBlocks.tsx   model, each ranking the rows that model actually called,
│       └── index.ts               inside a ChartCard
└── index.ts                       re-exports container as default
```

## Visual layout

```
┌─ PageLayout (no title — SectionLayout owns the title row) ──────────────┐
│ subtitle prose                                                            │
├─────────────────────────────────────────────────────────────────────────┤
│ ┌─ 4-column StatCard grid ──────────────────────────────────────────┐   │
│ │ Skill invocations │ Top skill │ Subagent invocations │ Top subagent│   │
│ └───────────────────────────────────────────────────────────────────┘   │
│ ┌─ 2-column DonutCard grid ─────────────────────────────────────────┐   │
│ │  Skill mix (donut + ranked legend + coverage ticks + colour key)   │   │
│ │  Subagent mix (same)                                               │   │
│ └───────────────────────────────────────────────────────────────────┘   │
│ ┌─ 2-column ModelFirstBlocks grid ──────────────────────────────────┐   │
│ │  Skills by model: one block per model (Sonnet, Opus, Haiku),       │   │
│ │  each block ranking the skills that model actually called          │   │
│ │  Subagents by model (same)                                         │   │
│ └───────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

Both fetchers live in `api/endpoints.ts` (the shared barrel, not a page-local module).

| Container hook    | Query key                       | Fetcher → endpoint |
|-------------------|---------------------------------|--------------------|
| `useQuery`        | `['skill-usage', selectionKey]` | `fetchSkillUsage(selection)` → `GET /api/tool-activity/skill-usage?…` |
| `useQuery`        | `['subagent-usage', selectionKey]` | `fetchSubagentUsage(selection)` → `GET /api/tool-activity/subagent-usage?…` |

Both return `IdentifierUsageRow[]` (imported from `../../api`). The `tool` field on each row
carries the skill or subagent identifier; `calls` is the invocation count; `byModel` maps model
id → calls and sums to `calls` (models with no calls are omitted, never sent as `0`). Both the
mix donuts' coverage ticks and both "by model" cards are fed from these same two responses —
there is no third query.

## Data flow and semantics

- **Section tab, not a top-level page.** The container reads `selection` and `autoRefresh` from
  `useSectionContext()` (not `useWindowContext()`). The `WindowSelector`, reload button, and
  auto-refresh toggle all live in `SectionLayout`'s `PageActions`; this page renders no chrome
  of its own.
- **`selectionKey`** is built in the container: `preset:<minutes>` or
  `custom:<startTimestamp>:<endTimestamp>`. It is placed in both query keys so the TanStack cache
  splits cleanly per window selection.
- **`refetchInterval`** is `60_000` when `autoRefresh && selection.kind === 'preset'`, otherwise
  `false`. Custom ranges have a fixed end and are not polled.
- **Section-level reload.** The `ToolActivitySection` lists `'skill-usage'` and `'subagent-usage'`
  in its `QUERY_KEY_PREFIXES`, so the section-level Refresh button invalidates both queries by
  predicate alongside the other tool-activity tabs. The by-model cards need no prefix of their
  own — they ride on the same two queries.
- **`withShare`** (in `skillsAgentsDerivations.ts`) maps `IdentifierUsageRow[]` to
  `IdentifierRowWithShare[]` and computes `total`. It runs in two `useMemo` calls — one for skills,
  one for subagents. `share` is `(100 * row.calls) / total`; total-zero guard sets `share = 0`.
- **`buildModelColorIndexes`** takes *both* row sets and returns `model id → palette index`. The
  three known model families — Sonnet, Opus, Haiku — always land at indexes 0/1/2 (the app's
  aurora violet/pink/cyan trio via `colorForIndex`), **regardless of call volume**; this is a
  brand-consistency decision (see the design handoff), not a ranking. Any other model family gets
  the next indexes, ordered by total calls (ties on model id), so a future 4th model still gets a
  stable colour without colliding with the fixed trio.
- **`buildModelCoverageModels`** turns that map into the single ordered `DonutCoverageModel[]`
  list (`{ key, label, color }`) reused for *three* things: the `coverageTicks` prop on both
  `DonutCard`s, the `legendCaption` prop on both `DonutCard`s, and (implicitly, via the same
  `modelColorIndexes` map) the block order in both `ModelFirstBlocks` cards. It lists every known
  model regardless of whether that particular row set (skills vs. subagents) has any calls from
  it — a model absent from one card's data still gets a tick/caption/block slot, just an unlit
  tick or an empty block.
- **`buildModelFirstBlocks(rows, modelColorIndexes)`** produces one `ModelFirstBlock` per model in
  `modelColorIndexes` order. Each block ranks only the rows that model actually called
  (`byModel[model] > 0`), highest first; the bar scale inside a block is local to that block's own
  max, not global across models. A model with zero calls anywhere in the row set still gets a
  block (`rows: []`) so `ModelFirstBlocks` can render "No calls in this window" instead of
  omitting the model — don't filter those blocks out upstream.
- **`toSlices` helper** (module-private in the view) converts `IdentifierRowWithShare[]` into the
  `DonutCard` slice shape: `{ label: row.tool, value: row.calls, color: colorForIndex(index),
  muted, coverageByModel: row.byModel }`. `coverageByModel` is what `DonutCard` reads to light up
  each row's coverage ticks. Rows whose `tool === 'unknown'` get `muted: true`.
- Both `DonutCard`s use `ranked` mode (ranked legend) plus `coverageTicks` + `legendCaption` (the
  per-model tick group between each row's name and its value, and the colour-key caption row below
  the legend).
- **Stat-card overflow guard.** "Top skill" / "Top subagent" pass `long={isLongStatValue(value)}`
  (from `StatCard`) — a real identifier can run past the point where the default 30px value wraps
  onto three lines, so `StatCard` shrinks to a 23px step (tighter letter-spacing, word-break) once
  the value string exceeds `LONG_STAT_VALUE_THRESHOLD` (20 chars). This is length-driven, not a
  hardcoded flag on these two cards specifically — any `StatCard` consumer can opt in the same way.
- The `Accent` inline component (`color: primary.main`, `fontWeight: 600`) is a view-local
  convenience for the `sub` prop of `StatCard` — it is not exported.

## Gotchas

- **The two `byModel` splits are not derived the same way.** Skill rows come from `api_request`
  log records, which carry the `model` attribute directly, so no correlation is needed — but the
  split is per *invocation*, not per turn (see the invocation-counting note below), and an
  invocation is credited to the model of its earliest turn. Subagent rows
  come from `Agent` `tool_result` records, which carry **no** model attribute at all, so the
  backend attributes each call to the last main-loop `api_request` in the same session at or
  before it (the turn that emitted the tool_use). Calls whose dispatching turn is not in the data
  land in an `unknown` model bucket. Both by-model cards now use the identical subtitle sentence
  ("…split by the model that made the call, ranked within each model.") per the design handoff —
  that's a UI-copy decision, not a claim that the two computations are equivalent; this note is
  the place to look if the subagent split ever needs re-explaining.
- **Model colours are fixed by family, not page-local ranking.** Unlike most other per-model
  breakdowns in the app (e.g. Token Usage, which still ranks by that page's own volume), this page
  hardcodes Sonnet → violet, Opus → pink, Haiku → cyan via `buildModelColorIndexes`. Don't
  "simplify" this back to a volume-sorted `colorForIndex` — that was the pre-redesign behavior and
  is exactly what the redesign replaced.
- **`PageLayout` receives no `title` prop** — the section's `SectionLayout` already renders the
  page title and tab strip above the outlet. Adding a `title` here would produce a duplicate
  heading. Pass only `subtitle` and `error`.
- **`calls` counts invocations, not model turns — don't compare it to anything on Tool Calls.**
  Claude Code stamps `skill.name` on every `api_request` made while a skill runs, including inside
  subagents the skill spawns, so the backend deduplicates by `prompt.id` and drops subagent turns.
  A skill that shows 11 here can easily own 1467 log records; that is the intended gap, not a
  dropped-rows bug. The same is true in reverse of the subagent card: `calls` there is one row per
  `Agent` `tool_result`, so the two cards count comparable units but neither counts model calls.
  Backend rationale lives on `LogRecordRepository.aggregateSkillInvocationsByModelInRange`.
- **`IdentifierUsageRow.tool`** carries a different semantic on each endpoint: skill identifier on
  `/skill-usage`, subagent identifier on `/subagent-usage`. The field is reused for both
  because the backend notes "the 'tool' field carries the skill identifier".
- **Empty state wording**: skills show `"No Skill invocations in this window."` and subagents
  show `"No subagent invocations in this window."` — both use the same "subagent" vocabulary as
  the rest of the page. (The subagent empty state previously read "No Task invocations" — "Task"
  was the old name for the subagent-dispatch tool, now named `Agent` in the backend's
  `subagentToolName`; don't reintroduce it.) The by-model cards reuse the same two strings for
  their "no data at all" state — distinct from a single model's own "No calls in this window"
  line, which `ModelFirstBlocks` renders per-block.
- **No zero rows inside a block; ticks answer "which models touched this" instead.** A skill/
  subagent that a given model never called is simply absent from that model's block — don't add
  it back as a dimmed 0 row (rejected in the design handoff: it reintroduces the density problem
  a skill×model matrix had). The mix-legend coverage ticks are the intentional answer to "does
  every model touch every row" instead.
- **`IdentifierRowWithShare`** is declared in `skillsAgentsDerivations.ts` and re-exported from
  `SkillsAgentsPageView.tsx` (not from `index.ts`), so the older import path still resolves.
- **`DonutCard`'s `coverageTicks`/`legendCaption` are shared, generic props** (declared in
  `components/DonutCard`, not page-local) — any other page's mix donut could opt into the same
  per-model tick pattern by passing a `DonutCoverageModel[]` and each slice's `coverageByModel`.
