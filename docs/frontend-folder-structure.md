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
  fonts.ts
  theme.ts
  windowContext.tsx
  main.tsx                       // entry; not a component
  App/
    App.tsx
    AppShell.tsx
    AuroraMark.tsx
    ColorModeToggle.tsx
    CompassIcon.tsx
    NavIcons.tsx
    NavItem.tsx
    navItems.tsx
    index.ts
  components/
    AreaTrendChart/
      AreaTrendChart.tsx
      AreaTrendLegend.tsx
      useSeriesVisibility.ts
      index.ts
    AttributeList/
      AttributeList.tsx
      AttributeListView.tsx
      AttributeValue.tsx
      ExpandedValueDialog.tsx
      types.ts
      utils.ts
      index.ts
    DonutCard/
      DonutCard.tsx
      index.ts
    GhostButton/
      GhostButton.tsx
      index.ts
    PageActions/
      PageActions.tsx
      PageActionsView.tsx
      index.ts
    PageLayout/
      PageLayout.tsx
      index.ts
    SearchInput/
      SearchInput.tsx
      index.ts
    SectionLayout/
      SectionLayout.tsx
      SectionLayoutView.tsx
      index.ts
    SegmentedToggle/
      SegmentedToggle.tsx
      index.ts
    Sparkline/
      Sparkline.tsx
      index.ts
    StatCard/
      StatCard.tsx
      index.ts
    WindowSelector/
      AuroraCalendar.tsx
      WindowSelector.tsx
      WindowSelectorView.tsx
      index.ts
  pages/
    LogsPage/
      CLAUDE.md                   // page-local conventions and data-flow notes
      LogsPage.tsx                // container
      LogsPageView.tsx            // presentational
      logsApi.ts                  // page-local API module (fetchers, types, query serialization)
      resolveWindow.ts            // page-internal helper; no barrel needed
      index.ts
      components/
        LogFacetRail/
          LogFacetRail.tsx
          index.ts
        LogHistogramChart/
          LogHistogramChart.tsx
          LogHistogramChartView.tsx
          index.ts
        LogStream/
          LogStream.tsx
          LogStreamView.tsx
          index.ts
        LogTable/
          LogTable.tsx
          LogTableView.tsx
          index.ts
        severity.ts               // helper shared by the sub-components
    MetricsPage/
      MetricsPage.tsx
      MetricsPageView.tsx
      metricsApi.ts               // page-local API module
      index.ts
      components/                 // flat leaf sub-components + barrel (see below)
        MetricBreakdown.tsx
        MetricHeader.tsx
        MetricKpiStrip.tsx
        metricsSampleData.ts
        index.ts
    PermissionDenialsPage/
      PermissionDenialsPage.tsx
      PermissionDenialsPageView.tsx
      HookExecutionsCard.tsx      // flat leaf sub-components
      ToolDenialsCard.tsx
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
      components/                 // flat leaf sub-components
        CostPanel.tsx
        TokenByModelCard.tsx
        TokenCompositionCard.tsx
        TokenSummaryCards.tsx
        tokensSampleData.ts
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
      attrFormat.ts               // page-internal helpers; no barrel needed
      criticalPath.ts
      logBuckets.ts
      severity.ts
      spanTree.ts
      index.ts
      components/
        SpanDetailDock/
          SpanDetailDock.tsx
          SpanAttributesColumn.tsx
          SpanEventsList.tsx
          TokensSection.tsx
          LogEntry.tsx
          dockParts.tsx           // shared leaf bits (SectionTitle/AttrRows/clock)
          useResizableHeight.ts
          index.ts
        SpanWaterfallRow/
          SpanWaterfallRow.tsx
          index.ts
        TraceDetailHeader/
          TraceDetailHeader.tsx
          TraceDetailHeaderView.tsx
          IdChip.tsx
          SummaryStrip.tsx
          useCopyToClipboard.ts
          index.ts
        TraceMinimap/
          TraceMinimap.tsx
          index.ts
        WaterfallToolbar/
          WaterfallToolbar.tsx
          index.ts
    TracesPage/
      CLAUDE.md                   // page-local conventions and data-flow notes
      TracesPage.tsx              // container (provider wrapper only)
      TracesPageView.tsx          // presentational; reads page-scoped context, takes no props
      TracesExplorerContext.tsx   // provider + useTracesExplorerContext()
      useTracesExplorer.ts        // the behavior hook (filters, queries, paging, handlers)
      tracesApi.ts                // page-local API module (re-exports the modules below)
      traceTypes.ts               // shared types; no runtime
      traceDerivations.ts         // serviceOf/quantile/formatDuration helpers + buildTracesQuery
      tokenBreakdown.ts           // per-span token-usage breakdown (also used by TraceDetailPage)
      tracesSampleData.ts         // VITE_TRACES_SAMPLE synthetic store + query engine
      index.ts
      components/
        TraceFacetRail/
          TraceFacetRail.tsx
          TraceFacetRailView.tsx
          index.ts
        TraceFilterChips/
          TraceFilterChips.tsx
          TraceFilterChipsView.tsx
          index.ts
        TraceHistogram/
          TraceHistogram.tsx
          TraceHistogramView.tsx
          index.ts
        TraceSortDropdown/
          TraceSortDropdown.tsx
          TraceSortDropdownView.tsx
          index.ts
        TraceStream/
          TraceStream.tsx
          TraceStreamView.tsx
          index.ts
        TraceSummaryInline/
          TraceSummaryInline.tsx
          TraceSummaryInlineView.tsx
          index.ts
        TraceTable/
          TraceTable.tsx
          TraceTableView.tsx
          index.ts
        TraceTailToggle/
          TraceTailToggle.tsx     // pure prop-driven leaf, single file
          index.ts
        TraceViewToggle/
          TraceViewToggle.tsx     // pure prop-driven leaf, single file
          index.ts
        TraceWaterfallInline/
          TraceWaterfallInline.tsx
          TraceWaterfallInlineView.tsx
          index.ts
        traceColors.ts            // helper shared by the sub-components (and TraceDetailPage)
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

**Flat helper files** (e.g. `TraceDetailPage/criticalPath.ts`, `LogsPage/resolveWindow.ts`, `TraceDetailPage/spanTree.ts`) live as flat `.ts` files inside the page folder. They are implementation details, not a public surface, and do NOT need their own folder + `index.ts`. Page-local API modules (`LogsPage/logsApi.ts`, `MetricsPage/metricsApi.ts`, `TracesPage/tracesApi.ts`) follow the same rule — they hold fetchers and types for endpoints only that page consumes.

**Page-internal sub-components** go in a `components/` sub-folder inside the page folder (e.g. `ToolCallsPage/components/ToolLatencyCard/`, `LogsPage/components/LogStream/`). Apply the same container/view split and barrel rule as top-level components. Use this pattern when a page grows enough sub-components that the page folder would otherwise become cluttered. Small leaf sub-components that have no container/view split may stay as flat `.tsx` files inside `components/` (e.g. `MetricsPage/components/`, `TokensPage/components/`); give them a folder + barrel once they grow one.

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

`tsconfig.json` is intentionally permissive (no `strict`, `noImplicitAny: false`). Vite transpiles TS via esbuild and does NOT type-check the build; the config exists for editor/IDE support and to let us tighten types incrementally. Run `yarn typecheck` (`tsc --noEmit`) manually if a stricter pass is needed.

### Known typecheck noise

`yarn typecheck` currently reports a handful of errors against `@mui/material@9.0.0` + React 19 — Stack typings missing `alignItems`/`direction`, `Dialog.PaperProps` deprecated in favor of `slotProps`, `highlightScope.faded` vs `fade`, etc. These are pre-existing API/typing mismatches inherited from the JS version, not regressions introduced by adding TS. The Vite build (`yarn build`) is unaffected. Resolving them is a separate MUI API-migration task.
