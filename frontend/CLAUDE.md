# Frontend conventions

Project-wide conventions for `frontend/` (React 19, Vite 5, MUI 9, TypeScript). Read [../AGENTS.md](../AGENTS.md) for repo-wide context.

## Module layout

- `api.ts` — single source of every `fetch` call, the matching TypeScript types, and the `WindowSelection` discriminated union. New backend endpoints add a new `fetchXxx(selection)` function here; pages never call `fetch` directly.
- `App/` — `App.tsx` wires the React Router routes, `AppShell.tsx` renders the app-bar + drawer chrome around `<Outlet />`, `navItems.tsx` is the nav model, `ColorModeToggle.tsx` flips the theme.
- `components/` — cross-page primitives:
  - `PageLayout` — page chrome (title row, subtitle, actions slot, error Alert, body).
  - `PageActions` — composes `WindowSelector` + reload button + auto-refresh toggle; this is what most pages pass into `PageLayout`'s `actions` slot.
  - `WindowSelector` — preset-or-custom datetime picker; emits `WindowSelection`.
  - `SectionLayout` — `PageLayout` + tab strip + shared `selection` / `autoRefresh` context (`useSectionContext`) for grouped pages like Tool activity.
  - `StatCard`, `AttributeList`, etc. — leaf presentational primitives.
- `pages/<Name>Page/` — one folder per route. Each follows the container/presentational split (see below). Sections that group tabs (e.g. `ToolActivitySection`) wrap their child pages in a `SectionLayout`.
- `colorMode.tsx` + `theme.ts` — `ColorModeProvider` persists `light` | `dark` to `localStorage`; `theme.ts` defines the light/dark token sets, the `CHART_PALETTE`, and the `colorForIndex(index)` helper.
- `windowContext.tsx` — `WindowProvider` holds the current `WindowSelection` and `autoRefresh` flag globally so navigating between pages preserves the user's selected range. Default selection is "last 24 hours" (`{ kind: 'preset', minutes: 1440 }`).
- `constants.ts` — `WINDOWS` (the preset minute options offered by `WindowSelector`) and other shared constants.

## Page structure (container/presentational)

Every page is split into two files in the same folder:

- `<Name>Page.tsx` — the **container**. Owns hooks: `useSectionContext()` (or `useWindowContext()` for top-level pages), `useQuery` calls, derived `useMemo` values, navigation/dialog state. Passes plain props to the view. No JSX beyond `<NameView ... />`.
- `<Name>PageView.tsx` — the **view**. Pure props in, JSX out. No `useQuery`, no fetch, no context. Composes MUI primitives (`Grid`, `Card`, `Stack`) and per-page leaf components from a `components/` subfolder when the view gets large.
- `index.ts` re-exports the container as the default.

Don't merge a container with its view, even for one-card pages — the split keeps the views easy to read and the data flow obvious in PR review.

## Data fetching

- **TanStack Query v5 is the only data layer.** No Redux, no SWR, no bespoke caches. `QueryClient` lives in `main.tsx` with `staleTime: 30_000` and `refetchOnWindowFocus: false`.
- **Query key shape:** `['kebab-feature', ...stableInputs, windowKey]`. The window key is `'preset:<minutes>'` or `'custom:<start>:<end>'` so cache entries split cleanly per selection. Pages that filter on more inputs (logs filters, autocomplete partial keys) append them to the key.
- **Auto-refresh is preset-only.** Set `refetchInterval = autoRefresh && selection.kind === 'preset' ? 60_000 : false`. Custom ranges have a fixed end — polling them would just re-fetch the same data.
- **Section-scoped reload.** Inside a `SectionLayout` the reload button invalidates queries by predicate against the section's `queryKeyPrefixes`, so all sibling tabs refresh together. Don't reach for `queryClient.invalidateQueries({ queryKey: [...] })` with a literal key when a predicate matches the section's contract.
- **Window selection** is always typed as `WindowSelection` from `api.ts` (`{ kind: 'preset', minutes }` | `{ kind: 'custom', startTimestamp, endTimestamp }`); never pass `minutes: number` or two ISO strings around as separate props. The `windowQueryParams(selection)` helper in `api.ts` is the only thing that flattens it into URL params.

## Charts and grids

- Charts stay on `@mui/x-charts`, tables on `@mui/x-data-grid`, trees on `@mui/x-tree-view`. Don't introduce a second visualization library.
- Use the shared `CHART_PALETTE` / `colorForIndex(index)` from `theme.ts` so bars, donut slices, line strokes, and rank-list bars all share the same colors across pages.
- `MuiDataGrid` theme overrides (border color, header background) come from `theme.ts` — page-level grids should inherit, not redefine, that styling.

## Routing

- React Router 6, declared in `App.tsx`. Nested sections (`/tool-activity/*`) use a parent route rendering `SectionLayout` and child routes (`calls`, `reliability`, `skills-agents`) rendered via `<Outlet context={...} />`.
- `useSectionContext()` is how child pages of a `SectionLayout` read `selection` + `autoRefresh`; top-level pages read the same values from `useWindowContext()` directly.
- Leave the legacy redirect routes (`/tool-calls → /tool-activity/calls`, etc.) in place — external links rely on them.

## Style and linting

- **TypeScript.** `tsconfig.json` runs with `strict: false` / `noImplicitAny: false`; don't tighten it without discussing — large parts of the dashboard rely on `Record<string, unknown>` attribute bags. Prefer explicit types on exports and `useState` initial values; let local inference take care of the rest. New props interfaces live in the file that exports the component.
- **Quotes.** Single quotes in TS/TSX (`avoidEscape: true`, `allowTemplateLiterals: true`); double quotes in JSX attributes. ESLint enforces both.
- **Braces always.** `curly: ['error', 'all']` — every `if` / `else` / `for` / `while` body wrapped, even one-liners. Same rule as the backend.
- **Components are functions, not classes.** `func-style: expression` + `allowArrowFunctions: true` means top-level component variables are arrow functions (`const FooView = (props) => { ... }`). `export default function FooPage() { ... }` declarations are allowed and idiomatic for the container default export. Don't use `function FooView() {}` assigned to a `const`.
- **Callbacks.** `prefer-arrow-callback` — pass arrow functions to `map` / `filter` / event handlers, never `function (item) { ... }`.
- **Path aliases.** None configured; use relative imports.
- **No emojis** in source files (matches the repo-wide rule).

## Dev / build

```sh
npm install                # one-time
npm run dev                # Vite on :5173, proxies /api → :8080
npm run build              # production build
npm run typecheck          # tsc --noEmit
npm run lint               # eslint .
```

Package manager is npm. The legacy `yarn.lock` is unused — don't `yarn install` and don't add `yarn` scripts.

## Skills

Project skills under `.claude/skills/` worth invoking proactively here:

- `/react19-concurrent-patterns` — when introducing or tuning concurrent rendering primitives: `useTransition`, `useDeferredValue`, `Suspense` for data, the `use()` hook, `useOptimistic`, Actions / `useActionState`. The project is already on React 19, so the migration-safety half of the skill is moot — read **Part 2 (Adopt)** plus the linked `references/react19-use.md`, `references/react19-actions.md`, `references/react19-suspense.md` for full patterns before wiring any of them up.
