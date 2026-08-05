# Skills & Subagents page

A section tab under [Tool Usage (`/tool-activity`)](../ToolActivitySection/ToolActivitySection.tsx)
that shows skill and subagent invocation breakdowns for the selected window: four KPI tiles, a
side-by-side pair of `DonutCard` mix charts, and a side-by-side pair of by-model segmented-bar
cards. Backend counterpart: `ToolActivityController` → `LogService`
(`backend/.../controller/ToolActivityController.java`), endpoints
`GET /api/tool-activity/skill-usage` and `GET /api/tool-activity/subagent-usage`.

## Files

```
SkillsAgentsPage/
├── SkillsAgentsPage.tsx           container — reads useSectionContext(), runs two queries,
│                                  derives share + model colours + breakdown rows
├── SkillsAgentsPageView.tsx       view — StatCard strip + two DonutCards + two
│                                  ModelBreakdownCards; no queries, no context
├── skillsAgentsDerivations.ts     pure derivations (withShare, buildModelColorIndexes,
│                                  buildModelLegendItems, toModelBreakdownRows)
├── skillsAgentsDerivations.test.ts vitest coverage for the above
├── components/
│   └── ModelBreakdownCard/        "Skills by model" / "Subagents by model" — one segmented
│       ├── ModelBreakdownCard.tsx CSS bar per skill/subagent, inside a ChartCard
│       └── index.ts
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
│ │  Skill mix (donut + ranked legend)  │  Subagent mix (same)        │   │
│ └───────────────────────────────────────────────────────────────────┘   │
│ ┌─ 2-column ModelBreakdownCard grid ────────────────────────────────┐   │
│ │  Skills by model                    │  Subagents by model         │   │
│ │  legend: one swatch per model       │  (same)                     │   │
│ │  row: name + total, segmented bar   │                             │   │
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
id → calls and sums to `calls` (models with no calls are omitted, never sent as `0`). Both
by-model cards are fed from these same two responses — there is no third query.

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
- **`buildModelColorIndexes`** takes *both* row sets and returns `model id → palette index`,
  ordered by total calls across the page (ties broken on model id so the assignment is stable).
  Both cards colour from that one map, so a model keeps one colour whether it appears under a
  skill or a subagent. `toModelBreakdownRows` and `buildModelLegendItems` sort by that index, so
  every bar stacks its segments in the same order as the legend.
- **Bar scale.** `ModelBreakdownCard` scales every bar against the busiest row's total
  (`largestRowCalls`), not against each row's own total — bar length reads as call volume and the
  segments read as the model mix inside it. Rows are already sorted by calls descending by the
  backend.
- **`toSlices` helper** (module-private in the view) converts `IdentifierRowWithShare[]` into the
  `DonutCard` slice shape: `{ label: row.tool, value: row.calls, color: colorForIndex(index) }`.
  Rows whose `tool === 'unknown'` get `muted: true`.
- Both `DonutCard`s use `ranked` mode, which renders a ranked legend beside the donut.
- The `Accent` inline component (`color: primary.main`, `fontWeight: 600`) is a view-local
  convenience for the `sub` prop of `StatCard` — it is not exported.

## Gotchas

- **The two `byModel` splits are not derived the same way.** Skill rows come from `api_request`
  log records, which carry the `model` attribute directly — that split is exact. Subagent rows
  come from `Agent` `tool_result` records, which carry **no** model attribute at all, so the
  backend attributes each call to the last main-loop `api_request` in the same session at or
  before it (the turn that emitted the tool_use). Calls whose dispatching turn is not in the data
  land in an `unknown` model bucket. Hence the different card subtitles ("made the call" vs.
  "dispatched the call") — don't collapse them into one string.
- **Model colours are page-local, not global.** The palette index comes from this page's own
  call-volume ranking, so the same model can have a different colour here than on the Token Usage
  page (which ranks by token totals). Consistency is guaranteed *within* the page only.
- **`PageLayout` receives no `title` prop** — the section's `SectionLayout` already renders the
  page title and tab strip above the outlet. Adding a `title` here would produce a duplicate
  heading. Pass only `subtitle` and `error`.
- **`IdentifierUsageRow.tool`** carries a different semantic on each endpoint: skill identifier on
  `/skill-usage`, subagent identifier on `/subagent-usage`. The field is reused for both
  because the backend notes "the 'tool' field carries the skill identifier".
- **Empty state wording**: skills show `"No Skill invocations in this window."` and subagents
  show `"No subagent invocations in this window."` — both use the same "subagent" vocabulary as
  the rest of the page. (The subagent empty state previously read "No Task invocations" — "Task"
  was the old name for the subagent-dispatch tool, now named `Agent` in the backend's
  `subagentToolName`; don't reintroduce it.) The by-model cards reuse the same two strings.
- **`IdentifierRowWithShare`** is declared in `skillsAgentsDerivations.ts` and re-exported from
  `SkillsAgentsPageView.tsx` (not from `index.ts`), so the older import path still resolves.
