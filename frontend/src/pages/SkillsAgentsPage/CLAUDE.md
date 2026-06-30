# Skills & Agents page

A section tab under [Tool Usage (`/tool-activity`)](../ToolActivitySection/ToolActivitySection.tsx)
that shows skill and subagent invocation breakdowns for the selected window: four KPI tiles plus
a side-by-side pair of `DonutCard` mix charts. Backend counterpart: `ToolActivityController`
→ `LogService` (`backend/.../controller/ToolActivityController.java`), endpoints
`GET /api/tool-activity/skill-usage` and `GET /api/tool-activity/subagent-usage`.

## Files

```
SkillsAgentsPage/
├── SkillsAgentsPage.tsx      container — reads useSectionContext(), runs two queries, derives share
├── SkillsAgentsPageView.tsx  view — StatCard strip + two DonutCards; no queries, no context
└── index.ts                  re-exports container as default
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
└─────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

Both fetchers live in `api/endpoints.ts` (the shared barrel, not a page-local module).

| Container hook    | Query key                       | Fetcher → endpoint |
|-------------------|---------------------------------|--------------------|
| `useQuery`        | `['skill-usage', selectionKey]` | `fetchSkillUsage(selection)` → `GET /api/tool-activity/skill-usage?…` |
| `useQuery`        | `['subagent-usage', selectionKey]` | `fetchSubagentUsage(selection)` → `GET /api/tool-activity/subagent-usage?…` |

Both return `ToolCallRow[]` (imported from `../../api`). The `tool` field on each row carries
the skill or subagent identifier; `calls` is the invocation count.

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
  predicate alongside the other tool-activity tabs.
- **`withShare` helper** (module-private function in the container) maps a `ToolCallRow[]` to
  `IdentifierRowWithShare[]` and computes `total`. It runs in two `useMemo` calls — one for skills,
  one for subagents. `share` is `(100 * row.calls) / total`; total-zero guard sets `share = 0`.
- **`toSlices` helper** (module-private in the view) converts `IdentifierRowWithShare[]` into the
  `DonutCard` slice shape: `{ label: row.tool, value: row.calls, color: colorForIndex(index) }`.
  Rows whose `tool === 'unknown'` get `muted: true`.
- Both `DonutCard`s use `ranked` mode, which renders a ranked legend beside the donut.
- The `Accent` inline component (`color: primary.main`, `fontWeight: 600`) is a view-local
  convenience for the `sub` prop of `StatCard` — it is not exported.

## Gotchas

- **`PageLayout` receives no `title` prop** — the section's `SectionLayout` already renders the
  page title and tab strip above the outlet. Adding a `title` here would produce a duplicate
  heading. Pass only `subtitle` and `error`.
- **`ToolCallRow.tool`** carries a different semantic on each endpoint: skill identifier on
  `/skill-usage`, subagent identifier on `/subagent-usage`. The field is reused for both
  because the backend notes "Reuses the `ToolCallCount` shape".
- **Empty state wording** differs intentionally: skills show `"No Skill invocations in this
  window."` while subagents show `"No Task invocations in this window."` — "Task" is the
  user-facing label for the subagent-dispatch tool.
- **`IdentifierRowWithShare`** is exported from `SkillsAgentsPageView.tsx` (not from `index.ts`);
  the container imports it directly via the named import path.
