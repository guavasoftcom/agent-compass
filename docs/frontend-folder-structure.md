# Frontend folder structure

## Rule

Every **page** and every **component** lives in its own folder. The folder name is the page/component name, and it contains:

- `<Name>.tsx` — the implementation
- `index.ts` — a barrel that re-exports the default (and any named exports)

A small single-file leaf component may skip the barrel and be imported by its file path (e.g. `components/SearchInput/SearchInput`, `components/FacetRail/FacetRail`, `components/BreakdownList/BreakdownList`).

Cross-cutting modules that are neither pages nor components are grouped into folders at the root of `src/`: `api/` (fetchers, DTO types, and transport behind a barrel), `lib/` (app-level non-UI modules — `constants`, `windowContext`, `resolveWindow`, `sampleData`, …), and `theme/` (the design system). Only the entry points (`main.tsx`, `vite-env.d.ts`) sit loose at the root.

Unit tests are colocated as `<name>.test.ts` / `<name>.test.tsx` next to the module they cover; the layout below omits them.

## Layout

```
frontend/src/
  main.tsx                         // entry; not a component
  vite-env.d.ts
  api/                             // shared fetchers + types, split behind a barrel
    index.ts                       // barrel — re-exports types + endpoints (NOT http)
    types.ts                       // every DTO interface + ListResult + WindowSelection
    endpoints.ts                   // fetchXxx(selection) functions
    http.ts                        // transport helpers (getJson/getText/listWithTotalCount/windowQueryParams)
  lib/                             // app-level, non-UI modules
    constants.ts                   // WINDOWS, PAGE_SIZE_OPTIONS, MS_PER_* factors, MAX_WINDOW_SPAN_MS
    format.ts                      // formatCompact (12.3K / 4.5M) shared by trend cards
    resolveWindow.ts               // WindowSelection → concrete start/end + label (Logs + Traces)
    sampleData.ts                  // createSampleRng(seed) + latency() for the VITE_*_SAMPLE stores
    useDebouncedValue.ts           // debounce hook for the Logs/Traces search inputs
    windowContext.tsx              // WindowProvider — global WindowSelection + autoRefresh
  theme/                           // the design system (no barrel — import the specific file)
    colors.ts                      // single source of truth for every raw color
    typography.ts                  // single source of truth for font-family stacks
    theme.ts                       // light/dark token sets + CHART_PALETTE / colorForIndex
    colorMode.tsx                  // ColorModeProvider (persists light/dark to localStorage)
    fonts.ts                       // @fontsource side-effect imports
    mui-stack-augment.d.ts         // Stack prop augmentation
  App/
    App.tsx                        // React Router routes
    AppShell.tsx                   // app-bar + drawer chrome around <Outlet />
    AuroraMark.tsx
    ColorModeToggle.tsx
    NavIcons.tsx
    NavItem.tsx
    navGroups.tsx                  // nav model
    index.ts
  components/                      // cross-page primitives (folder + barrel; single-file leaves omit index.ts)
    AreaTrendChart/
      AreaTrendChart.tsx
      AreaTrendLegend.tsx
      areaTrendGeometry.ts         // pure path/tick/hover math (memoized by the chart)
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
    BreakdownList/
      BreakdownList.tsx            // single-file leaf (no barrel)
    ChartCard/
      ChartCard.tsx                // single-file leaf (no barrel)
    DonutCard/
      DonutCard.tsx
      index.ts
    ErrorBoundary/
      ErrorBoundary.tsx            // class-component render-crash fallback (wired in AppShell)
      index.ts
    FacetRail/
      FacetRail.tsx                // shared facet rail (Logs + Traces); single-file leaf
    GhostButton/
      GhostButton.tsx
      index.ts
    LineSparkline/
      LineSparkline.tsx            // shared area+line sparkline (Sessions + Metrics KPI strips)
      index.ts
    LiveTailToggle/
      LiveTailToggle.tsx           // single-file leaf (no barrel)
    PageActions/
      PageActions.tsx
      PageActionsView.tsx
      index.ts
    PageLayout/
      PageLayout.tsx
      index.ts
    SearchInput/
      SearchInput.tsx              // single-file leaf (no barrel)
    SectionLayout/
      SectionLayout.tsx
      SectionLayoutView.tsx
      index.ts
    SegmentedToggle/
      SegmentedToggle.tsx
      index.ts
    Sparkline/
      Sparkline.tsx                // bar sparkline
      index.ts
    StatCard/
      StatCard.tsx
      index.ts
    StreamTableToggle/
      StreamTableToggle.tsx        // shared Stream|Table view toggle (Logs + Traces)
      index.ts
    TablePager/
      TablePager.tsx               // shared offset-pager footer (Sessions + Logs + Traces)
      index.ts
    WindowSelector/
      AuroraCalendar.tsx
      WindowSelector.tsx
      WindowSelectorView.tsx
      index.ts
  pages/                           // every page folder has its own CLAUDE.md (page files + data flow + gotchas)
    LogsPage/
      CLAUDE.md
      LogsPage.tsx                 // container
      LogsPageView.tsx             // presentational
      logsApi.ts                   // page-local API module (re-exports the types + derivations below)
      logsTypes.ts                 // DTO types + Severity/FacetKey enums + LogsFilters
      logsDerivations.ts           // buildLogsQuery + severityOf/eventNameOf/toolNameOf row helpers
      logsSampleData.ts            // VITE_LOGS_SAMPLE synthetic store + query engine
      index.ts
      components/
        LogFacetRail/
          LogFacetRail.tsx
          index.ts
        LogHistogramChart/
          LogHistogramChart.tsx
          HistogramTooltip.tsx
          SeverityLegend.tsx
          bucketTotal.ts
          index.ts
        LogStream/
          LogStream.tsx
          LogRowDetail.tsx
          index.ts
        LogTable/
          LogTable.tsx
          LogTableRow.tsx
          index.ts
        SeverityChip/
          SeverityChip.tsx
          index.ts
        severity.ts               // severity → theme color (shared by the sub-components)
    MetricsPage/
      CLAUDE.md
      MetricsPage.tsx
      MetricsPageView.tsx
      metricsApi.ts               // page-local API module
      index.ts
      components/
        MetricBreakdown/
          MetricBreakdown.tsx
          index.ts
        MetricHeader/
          MetricHeader.tsx
          index.ts
        MetricKpiStrip/
          MetricKpiStrip.tsx
          index.ts
        MetricTrendCard/
          MetricTrendCard.tsx
          index.ts
        metricsSampleData.ts      // MetricSeries types + METRICS fixture (shared by the sub-components)
    PermissionDenialsPage/
      CLAUDE.md
      PermissionDenialsPage.tsx
      PermissionDenialsPageView.tsx
      HookExecutionsCard.tsx      // flat leaf sub-components
      ToolDenialsCard.tsx
      index.ts
    ReportPage/
      CLAUDE.md
      ReportPage.tsx
      ReportPageView.tsx
      index.ts
    SessionsPage/
      CLAUDE.md
      SessionsPage.tsx
      SessionsPageView.tsx
      index.ts
      components/
        SessionsKpiStrip/
          SessionsKpiStrip.tsx
          index.ts
        SessionsTable/
          SessionsTable.tsx
          index.ts
        sessionsFormat.ts         // shared formatters (USD / duration / tokens / timestamp)
    SkillsAgentsPage/
      CLAUDE.md
      SkillsAgentsPage.tsx
      SkillsAgentsPageView.tsx
      index.ts
    TokensPage/
      CLAUDE.md
      TokensPage.tsx
      TokensPageView.tsx
      index.ts
      components/
        TokenByModelCard/
          TokenByModelCard.tsx
          index.ts
        TokenCompositionCard/
          TokenCompositionCard.tsx
          index.ts
        TokenSummaryCards/
          TokenSummaryCards.tsx
          index.ts
    ToolActivitySection/          // SectionLayout wrapper; renders child pages via <Outlet>
      CLAUDE.md
      ToolActivitySection.tsx
      index.ts
    ToolCallsPage/
      CLAUDE.md
      ToolCallsPage.tsx
      ToolCallsPageView.tsx
      index.ts
      components/
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
    ToolReliabilityPage/
      CLAUDE.md
      ToolReliabilityPage.tsx
      ToolReliabilityPageView.tsx
      index.ts
      components/
        ToolRepeatsCard/
          ToolRepeatsCard.tsx
          index.ts
    TraceDetailPage/
      CLAUDE.md
      TraceDetailPage.tsx
      TraceDetailPageView.tsx
      attrFormat.ts               // page-internal helpers; no barrel needed
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
      CLAUDE.md
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
          TraceFacetRail.tsx      // builds sections for the shared FacetRail
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
          TraceTailToggle.tsx     // pure prop-driven leaf
          index.ts
        TraceViewToggle/
          TraceViewToggle.tsx     // thin wrapper over the shared StreamTableToggle
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

**Flat helper files** (e.g. `TraceDetailPage/spanTree.ts`, `TraceDetailPage/logBuckets.ts`, `TraceDetailPage/attrFormat.ts`) live as flat `.ts` files inside the page folder. They are implementation details, not a public surface, and do NOT need their own folder + `index.ts`. Page-local API modules (`LogsPage/logsApi.ts`, `MetricsPage/metricsApi.ts`, `TracesPage/tracesApi.ts`) follow the same rule — they hold fetchers and types for endpoints only that page consumes.

**Page-internal sub-components** go in a `components/` sub-folder inside the page folder (e.g. `ToolCallsPage/components/ToolLatencyCard/`, `LogsPage/components/LogStream/`). Apply the same container/view split and barrel rule as top-level components. Use this pattern when a page grows enough sub-components that the page folder would otherwise become cluttered. Small leaf sub-components that have no container/view split may stay as flat `.tsx` files in the page folder (e.g. `PermissionDenialsPage/HookExecutionsCard.tsx`); give them a folder + barrel once they grow one.

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

Never reach past the folder boundary (`import X from './pages/LogsPage/LogsPage'`). The barrel is the public surface. The one exception is a single-file leaf component with no `index.ts` (e.g. `components/SearchInput/SearchInput`, `components/FacetRail/FacetRail`): there is no barrel, so the file path is the import.

## Why a folder per component

- Co-locates a component with its tests, styles, sub-components, and types as the feature grows — no need to refactor the folder later.
- Keeps `import` paths stable when internal files are split or renamed; only `index.ts` is part of the public surface.
- Lets the grep target `LogsPage/` show every file that belongs to that page in one place.

## TypeScript config

`tsconfig.json` is intentionally permissive (no `strict`, `noImplicitAny: false`). Vite transpiles TS via esbuild and does NOT type-check the build; the config exists for editor/IDE support and to let us tighten types incrementally. Run `yarn typecheck` (`tsc --noEmit`) for a stricter pass — it currently passes clean.
