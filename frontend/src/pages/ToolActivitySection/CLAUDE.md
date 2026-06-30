# Tool activity section

A `SectionLayout` wrapper that groups the four tool-activity tabs — Calls, Reliability,
Skills & Agents, and Denials — under the `/tool-activity/*` parent route with a shared
`selection` / `autoRefresh` context. It makes no backend calls of its own; all data
fetching happens inside the child tab pages.

## Files

```
ToolActivitySection/
├── ToolActivitySection.tsx   declares TABS + QUERY_KEY_PREFIXES, renders <SectionLayout>
└── index.ts                  re-exports the default
```

Child tab pages (each in its own folder):

- [`../ToolCallsPage/`](../ToolCallsPage/) — `/tool-activity/calls`
- [`../ToolReliabilityPage/`](../ToolReliabilityPage/) — `/tool-activity/reliability`
- [`../SkillsAgentsPage/`](../SkillsAgentsPage/) — `/tool-activity/skills-agents`
- [`../PermissionDenialsPage/`](../PermissionDenialsPage/) — `/tool-activity/permissions`

## Routing

In `App.tsx` the section is a React Router 7 nested route:

```
<Route path="/tool-activity" element={<ToolActivitySection />}>
  <Route index element={<Navigate to="calls" replace />} />
  <Route path="calls" element={<ToolCallsPage />} />
  <Route path="reliability" element={<ToolReliabilityPage />} />
  <Route path="skills-agents" element={<SkillsAgentsPage />} />
  <Route path="permissions" element={<PermissionDenialsPage />} />
</Route>
```

`SectionLayout` renders `<SectionLayoutView>`, which renders the tab strip and then
`<Outlet context={context} />`. Child pages read `selection` and `autoRefresh` via
`useSectionContext()` (exported from `SectionLayout`), not `useWindowContext()`.

Legacy redirects in `App.tsx` preserve old paths:
`/tool-calls`, `/tool-reliability`, `/skills-agents` each redirect to their
`/tool-activity/*` equivalents.

## Section-scoped reload and polling

`QUERY_KEY_PREFIXES` lists every React Query key prefix used across the four child tabs:

```ts
'tool-calls', 'tool-calls-timeseries', 'tool-calls-latency',
'tool-repeats', 'tool-failure-rates',
'skill-usage', 'subagent-usage',
'tool-denials', 'hook-executions'
```

The `SectionLayout` container invalidates all matching queries when the Refresh button is
pressed (`queryClient.invalidateQueries({ predicate })`) and drives the polling indicator
(`isPolling`) by counting in-flight queries against the same predicate. Child pages must
use one of these prefixes as the first element of their `useQuery` key — otherwise their
queries are invisible to section reload.

## Gotchas

- **No container/view split needed here.** The section component is a thin config wrapper
  (~35 lines); it delegates all chrome, tab rendering, and context provision to
  `SectionLayout` in `components/SectionLayout/`. Don't add state or queries to this file.
- **Adding a new tab** requires: a new child `<Route>` in `App.tsx`, a new entry in
  `TABS`, and all new query key prefixes added to `QUERY_KEY_PREFIXES` so section reload
  covers them.
- Child pages call `useSectionContext()`, **not** `useWindowContext()`. Top-level pages
  (outside a section) use `useWindowContext()`. Mixing them up compiles but silently
  ignores the section's shared selection state.
