# Frontend folder structure

## Rule

Every **page** and every **component** lives in its own folder. The folder name is the page/component name, and it contains:

- `<Name>.tsx` — the implementation
- `index.ts` — a barrel that re-exports the default (and any named exports)

Top-level helper modules (`api.ts`, `theme.ts`, `constants.ts`, etc.) are NOT pages or components and stay as flat files at the root of `src/`.

## Layout

```
frontend/src/
  api.ts
  colorMode.tsx
  constants.ts
  theme.ts
  windowContext.tsx
  main.tsx                       // entry; not a component
  App/
    App.tsx
    AppShell.tsx
    ColorModeToggle.tsx
    NavItem.tsx
    navItems.tsx
    index.ts
  components/
    AttributeList/
      AttributeList.tsx
      AttributeListView.tsx
      AttributeValue.tsx
      ExpandedValueDialog.tsx
      types.ts
      utils.ts
      index.ts
    PageActions/
      PageActions.tsx
      PageActionsView.tsx
      index.ts
    PageLayout/
      PageLayout.tsx
      index.ts
    SectionLayout/
      SectionLayout.tsx
      SectionLayoutView.tsx
      index.ts
    StatCard/
      StatCard.tsx
      index.ts
    WindowSelector/
      WindowSelector.tsx
      WindowSelectorView.tsx
      index.ts
  pages/
    LogsPage/
      LogsPage.tsx                // container
      LogsPageView.tsx            // presentational
      index.ts
    MetricsPage/
      MetricsPage.tsx
      MetricsPageView.tsx
      index.ts
    PermissionDenialsPage/
      PermissionDenialsPage.tsx
      PermissionDenialsPageView.tsx
      index.ts
    ReportPage/
      ReportPage.tsx
      ReportPageView.tsx
      index.ts
    SessionsPage/
      SessionsPage.tsx
      SessionsPageView.tsx
      index.ts
    SkillsAgentsPage/
      SkillsAgentsPage.tsx
      SkillsAgentsPageView.tsx
      index.ts
    TokensPage/
      TokensPage.tsx
      TokensPageView.tsx
      index.ts
    ToolActivitySection/          // SectionLayout wrapper; renders child pages via <Outlet>
      ToolActivitySection.tsx
      index.ts
    ToolCallsPage/
      ToolCallsPage.tsx
      ToolCallsPageView.tsx
      index.ts
      components/                 // page-internal sub-components (see below)
        CallsOverTimeCard/
          CallsOverTimeCard.tsx
          CallsOverTimeCardView.tsx
          index.ts
        StatsRow/
          StatsRow.tsx
          StatsRowView.tsx
          index.ts
        ToolLatencyCard/
          ToolLatencyCard.tsx
          ToolLatencyCardView.tsx
          index.ts
        ToolRankingCard/
          ToolRankingCard.tsx
          index.ts
    ToolReliabilityPage/
      ToolReliabilityPage.tsx
      ToolReliabilityPageView.tsx
      index.ts
      components/
        ToolRepeatsCard/
          ToolRepeatsCard.tsx
          index.ts
    TraceDetailPage/
      TraceDetailPage.tsx
      TraceDetailPageView.tsx
      traceDetailHelpers.ts       // page-internal helper; no barrel needed
      index.ts
    TracesPage/
      TracesPage.tsx
      TracesPageView.tsx
      timeFormat.ts               // page-internal helper; no barrel needed
      index.ts
```

## Container / presentational split

Every page is split into two files inside its folder:

- **`<Name>.tsx` — container.** Owns React state, `useQuery` calls, derived data, and event handlers. Exports a default component that wires those values into the view. Zero JSX beyond rendering `<<Name>View ... />`.
- **`<Name>View.tsx` — presentational.** Pure render. Receives props (data, flags, handlers) and renders MUI. May still call view-only hooks like `useTheme`, `useMediaQuery`, and `useMemo` for layout-derived values, but no data fetching and no app state that outlives a render.

The barrel re-exports the container as the page's default export:

```ts
// pages/LogsPage/index.ts
export { default } from './LogsPage';
```

### Why split

- Keeps the data layer (TanStack Query, derived selectors) separate from layout — so swapping UI or A/B-testing a layout doesn't touch the fetch logic, and vice versa.
- Makes the view trivially testable with fixture props; no `QueryClientProvider` needed in a unit test.
- Makes the container the single place to look for "where does this page get its data from?".

### Where page-internal helpers go

**Flat helper files** (e.g. `TracesPage/timeFormat.ts`, `TraceDetailPage/traceDetailHelpers.ts`) live as flat `.ts` files inside the page folder. They are implementation details, not a public surface, and do NOT need their own folder + `index.ts`.

**Page-internal sub-components** go in a `components/` sub-folder inside the page folder (e.g. `ToolCallsPage/components/ToolLatencyCard/`). Apply the same container/view split and barrel rule as top-level components. Use this pattern when a page grows enough sub-components that the page folder would otherwise become cluttered.

Promote a sub-component from `PageName/components/` to `src/components/` only when a second page needs it.

## Index file shape

```ts
// pages/LogsPage/index.ts
export { default } from './LogsPage';
```

If the component file also exports named symbols (types, sub-components consumed by tests), re-export them too:

```ts
export { default } from './LogsPage';
export type { LogRow } from './LogsPage';
```

## Imports

Callers import the folder, not the file:

```ts
import LogsPage from './pages/LogsPage';            // resolves to index.ts
import PageLayout from '../components/PageLayout';  // resolves to index.ts
```

Never reach past the folder boundary (`import X from './pages/LogsPage/LogsPage'`). The barrel is the public surface.

## Why a folder per component

- Co-locates a component with its tests, styles, sub-components, and types as the feature grows — no need to refactor the folder later.
- Keeps `import` paths stable when internal files are split or renamed; only `index.ts` is part of the public surface.
- Lets the grep target `LogsPage/` show every file that belongs to that page in one place.

## TypeScript config

`tsconfig.json` is intentionally permissive (no `strict`, `noImplicitAny: false`). Vite transpiles TS via esbuild and does NOT type-check the build; the config exists for editor/IDE support and to let us tighten types incrementally. Run `npm run typecheck` (`tsc --noEmit`) manually if a stricter pass is needed.

### Known typecheck noise

`npm run typecheck` currently reports a handful of errors against `@mui/material@9.0.0` + React 19 — Stack typings missing `alignItems`/`direction`, `Dialog.PaperProps` deprecated in favor of `slotProps`, `highlightScope.faded` vs `fade`, etc. These are pre-existing API/typing mismatches inherited from the JS version, not regressions introduced by adding TS. The Vite build (`npm run build`) is unaffected. Resolving them is a separate MUI API-migration task.
