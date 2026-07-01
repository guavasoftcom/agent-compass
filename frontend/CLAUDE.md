# Frontend conventions

Project-wide conventions for `frontend/` (React 19, Vite 8, MUI 9, TypeScript). Read [../AGENTS.md](../AGENTS.md) for repo-wide context.

## Module layout

The root of `src/` holds only the entry points (`main.tsx`, `vite-env.d.ts`); everything else lives in a folder. Cross-cutting modules are grouped into `api/`, `theme/`, and `lib/` (described below) alongside `App/`, `components/`, and `pages/`.

- `api/` — shared `fetch` calls, the matching TypeScript types, and the `WindowSelection` discriminated union. Split by concern behind a barrel `index.ts` (so imports stay `from '../../api'`): `types.ts` holds every DTO interface + `ListResult` + `WindowSelection`; `endpoints.ts` holds the `fetchXxx(selection)` functions; `http.ts` holds the transport helpers (`getJson` / `getText` / `listWithTotalCount` / `windowQueryParams`) and is intentionally **not** re-exported by the barrel. New cross-page endpoints add their type to `types.ts` and their fetcher to `endpoints.ts` — unless they serve exactly one page, in which case they live in a page-local API module (`pages/LogsPage/logsApi.ts`, `pages/MetricsPage/metricsApi.ts`, `pages/TracesPage/tracesApi.ts`). Either way, pages never call `fetch` outside these modules.
- `App/` — `App.tsx` wires the React Router routes, `AppShell.tsx` renders the app-bar + drawer chrome around `<Outlet />`, `navGroups.tsx` is the nav model, `ColorModeToggle.tsx` flips the theme.
- `components/` — cross-page primitives:
  - `PageLayout` — page chrome (title row, subtitle, actions slot, error Alert, body).
  - `PageActions` — composes `WindowSelector` + reload button + auto-refresh toggle; this is what most pages pass into `PageLayout`'s `actions` slot.
  - `WindowSelector` — preset-or-custom datetime picker; emits `WindowSelection`.
  - `SectionLayout` — `PageLayout` + tab strip + shared `selection` / `autoRefresh` context (`useSectionContext`) for grouped pages like Tool activity.
  - `StatCard`, `AttributeList`, `Sparkline`, `DonutCard`, etc. — leaf presentational primitives.
  - `TablePager` — shared offset-pager footer (rows-per-page `SegmentedToggle` + range label + prev/next); used by Sessions, Logs, and Traces tables.
  - `StreamTableToggle` — shared Stream|Table view-mode `SegmentedToggle`; used by Logs and (via `TraceViewToggle`) Traces.
  - `LineSparkline` — shared area+line SVG sparkline (guards `values.length < 2`); used by the Sessions and Metrics KPI strips.
  - `FacetRail` — shared filter-rail (search box + checkbox facet sections); Logs' `LogFacetRail` and Traces' `TraceFacetRail` build sections for it.
  - `LiveTailToggle` — shared live-tail pill; Traces' `TraceTailToggle` wraps it.
  - `BreakdownList` — shared ranked-breakdown list; used by the metric and token breakdowns.
  - `ChartCard` — shared titled chart-container card; used by the Tool-activity cards.
- `pages/<Name>Page/` — one folder per route. Each follows the container/presentational split (see below). Sections that group tabs (e.g. `ToolActivitySection`) wrap their child pages in a `SectionLayout`.
- `theme/` — the design system. Six files: `colors.ts`, `typography.ts`, `theme.ts`, `colorMode.tsx`, `fonts.ts` (self-hosted `@fontsource` side-effect imports), and `mui-stack-augment.d.ts` (Stack prop augmentation). No barrel — import the specific file (`from '../../theme/colors'`).
  - `theme/colors.ts` — **the single source of truth for every raw color**. Hue-named primitives (`auroraColors`, `neutralColors`), semantic aliases (`severity`, `tokenComposition`), and the signature `gradients`. No `#rrggbb` / `rgba(...)` literal should live anywhere else under `src/`; for transparency wrap a base token in MUI's `alpha(token, opacity)` rather than hand-writing an rgba string. `theme.ts`, `traceColors.ts`, and component `sx` props all reference these tokens.
  - `theme/typography.ts` — **the single source of truth for font-family stacks**: `fontFamilies.display` (Sora), `.body` (Space Grotesk, the app default), `.mono` (JetBrains Mono). No raw font-family string should live anywhere else under `src/`. Font *sizes* are deliberately left inline (the ~28 values in use aren't a clean scale; a 1:1 token rename would add indirection without benefit — snapping them to a real type scale is a separate visual redesign).
  - `theme/colorMode.tsx` + `theme/theme.ts` — `ColorModeProvider` persists `light` | `dark` to `localStorage`; `theme.ts` maps the `colors.ts` primitives into the light/dark theme token sets and defines the `CHART_PALETTE` + `colorForIndex(index)` helper.
- `lib/` — app-level, non-UI modules:
  - `lib/windowContext.tsx` — `WindowProvider` holds the current `WindowSelection` and `autoRefresh` flag globally so navigating between pages preserves the user's selected range. Default selection is "last 24 hours" (`{ kind: 'preset', minutes: 1440 }`).
  - `lib/constants.ts` — `WINDOWS` (the preset minute options offered by `WindowSelector`) and other shared constants.
  - `lib/sampleData.ts` — `createSampleRng(seed)` (seeded RNG factory: `rnd`/`pick`/`ri`/`hx`) + `latency(ms)`, shared by the page-local `VITE_*_SAMPLE` stores (`pages/LogsPage/logsSampleData.ts`, `pages/TracesPage/tracesSampleData.ts`). Each store passes its own seed so its mock data stays deterministic without sharing RNG state.

## Page structure (container/presentational)

Every page is split into two files in the same folder:

- `<Name>Page.tsx` — the **container**. Owns hooks: `useSectionContext()` (or `useWindowContext()` for top-level pages), `useQuery` calls, derived `useMemo` values, navigation/dialog state. Passes plain props to the view. No JSX beyond `<NameView ... />`.
- `<Name>PageView.tsx` — the **view**. Pure props in, JSX out. No `useQuery`, no fetch, no context. Composes MUI primitives (`Grid`, `Card`, `Stack`) and per-page leaf components from a `components/` subfolder when the view gets large.
- `index.ts` re-exports the container as the default.

Don't merge a container with its view, even for one-card pages — the split keeps the views easy to read and the data flow obvious in PR review.

**Every `pages/<Name>Page/` folder has its own `CLAUDE.md`** documenting that page's files, visual layout, which queries hit which endpoints, data-flow semantics, and gotchas. Read the relevant page's `CLAUDE.md` before touching it, and update it when you change the page. (`ToolActivitySection/` — the tab-grouping `SectionLayout` wrapper — has one too.)

Documented deviation: `LogsPage` keeps its two page-level `useQuery` calls in the view because they depend on view-owned filter state — read [src/pages/LogsPage/CLAUDE.md](src/pages/LogsPage/CLAUDE.md) before touching that page.

Documented deviation: `TracesPage` is data-dense enough that prop-drilling produced a ~47-prop view, so it uses a page-scoped context instead — all behavior lives in `useTracesExplorer`, the `TracesExplorerContext` provider wires it to the global window context, and `TracesPageView` reads context and takes zero props. Read [src/pages/TracesPage/CLAUDE.md](src/pages/TracesPage/CLAUDE.md) before touching that page.

## Data fetching

- **TanStack Query v5 is the only data layer.** No Redux, no SWR, no bespoke caches. `QueryClient` lives in `main.tsx` with `staleTime: 30_000` and `refetchOnWindowFocus: false`.
- **Query key shape:** `['kebab-feature', ...stableInputs, windowKey]`. The window key is `'preset:<minutes>'` or `'custom:<start>:<end>'` so cache entries split cleanly per selection. Pages that filter on more inputs (logs filters, autocomplete partial keys) append them to the key.
- **Auto-refresh is preset-only.** Set `refetchInterval = autoRefresh && selection.kind === 'preset' ? 60_000 : false`. Custom ranges have a fixed end — polling them would just re-fetch the same data.
- **Section-scoped reload.** Inside a `SectionLayout` the reload button invalidates queries by predicate against the section's `queryKeyPrefixes`, so all sibling tabs refresh together. Don't reach for `queryClient.invalidateQueries({ queryKey: [...] })` with a literal key when a predicate matches the section's contract.
- **Window selection** is always typed as `WindowSelection` from `api/index.ts` (`{ kind: 'preset', minutes }` | `{ kind: 'custom', startTimestamp, endTimestamp }`); never pass `minutes: number` or two ISO strings around as separate props. The `windowQueryParams(selection)` helper in `api/index.ts` is the only thing that flattens it into URL params.

## Charts and grids

- **All visualizations are hand-built SVG/CSS** — there is no charting library. The Aurora retheme replaced `@mui/x-charts` with bespoke components (`DonutCard`, `AreaTrendChart`, the log/trace histograms, `ToolLatencyCard`'s CSS bars) so the gradients, rounded caps, and glass surfaces match the rest of the UI exactly. `@mui/x-charts`, `@mui/x-data-grid`, and `@mui/x-tree-view` have all been removed from the dependencies. Don't reintroduce them (or a different chart library) without a deliberate decision — extend the hand-built components instead.
- **Tables are hand-built too** — `<Box component="table">` / styled `MuiTable` (see `SessionsPage`, `TraceTable`, `LogTable`), not a DataGrid. Match the existing table styling (sticky headers, hairline dividers, zebra rows, soft hover) rather than redefining it.
- Use the shared `CHART_PALETTE` / `colorForIndex(index)` from `theme.ts` so bars, donut slices, line strokes, and rank-list bars all share the same colors across pages. Every raw color resolves through `colors.ts` (see the module-layout note).

## Routing

- React Router 7, declared in `App.tsx`. Nested sections (`/tool-activity/*`) use a parent route rendering `SectionLayout` and child routes (`calls`, `reliability`, `skills-agents`) rendered via `<Outlet context={...} />`.
- `useSectionContext()` is how child pages of a `SectionLayout` read `selection` + `autoRefresh`; top-level pages read the same values from `useWindowContext()` directly.
- Leave the legacy redirect routes (`/tool-calls → /tool-activity/calls`, etc.) in place — external links rely on them.

## Style and linting

- **TypeScript.** `tsconfig.json` runs with `strict: false` / `noImplicitAny: false`; don't tighten it without discussing — large parts of the dashboard rely on `Record<string, unknown>` attribute bags. Prefer explicit types on exports and `useState` initial values; let local inference take care of the rest. New props interfaces live in the file that exports the component.
- **Quotes.** Single quotes in TS/TSX (`avoidEscape: true`, `allowTemplateLiterals: true`); double quotes in JSX attributes. ESLint enforces both.
- **Braces always.** `curly: ['error', 'all']` — every `if` / `else` / `for` / `while` body wrapped, even one-liners. Same rule as the backend.
- **Expressive names.** Spell names out — `formatGranularity` not `granLabel`, `stringBuilder` not `sb`. Variables, functions, props, hooks, and meaningful locals use full, intent-revealing names; no abbreviations or one/two-letter shorthand. Carve-outs (short names OK): standard index loops (`i`/`j`/`k`), generic type params (`T`/`K`/`V`), and single-expression lambda/callback params — including the idiomatic MUI `sx={{ color: (t) => t.palette… }}` theme arg and the `(e)` event arg — plus `e` in `catch`. Mirrors the backend's full-descriptive-names rule. Convention only (no ESLint rule backs it), so it's enforced in review.
- **Components are functions, not classes.** `func-style: expression` + `allowArrowFunctions: true` means top-level component variables are arrow functions (`const FooView = (props) => { ... }`). `export default function FooPage() { ... }` declarations are allowed and idiomatic for the container default export. Don't use `function FooView() {}` assigned to a `const`.
- **Callbacks.** `prefer-arrow-callback` — pass arrow functions to `map` / `filter` / event handlers, never `function (item) { ... }`.
- **Path aliases.** None configured; use relative imports.
- **No emojis** in source files (matches the repo-wide rule).

## Dev / build

```sh
yarn install               # one-time
yarn dev                   # Vite on :5173, proxies /api → :8080
yarn build                 # production build
yarn typecheck             # tsc --noEmit
yarn lint                  # eslint .
```

Package manager is Yarn (Berry — Yarn 4). The stray `package-lock.json` is legacy — don't `npm install`.

## Skills

Project skills under `.claude/skills/` worth invoking proactively here:

- `/react19-concurrent-patterns` — when introducing or tuning concurrent rendering primitives: `useTransition`, `useDeferredValue`, `Suspense` for data, the `use()` hook, `useOptimistic`, Actions / `useActionState`. The project is already on React 19, so the migration-safety half of the skill is moot — read **Part 2 (Adopt)** plus the linked `references/react19-use.md`, `references/react19-actions.md`, `references/react19-suspense.md` for full patterns before wiring any of them up.
