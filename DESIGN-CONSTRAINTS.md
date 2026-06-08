# Aurora dashboard — generation constraints

Paste this into the Claude Design prompt (or point it at this file) so regenerated
`frontend/` code stops reverting fixes the project has already applied. The target
stack is **MUI v9 / React 19 / TypeScript with strict ESLint** — generate code that
satisfies every rule below.

> Keep this file at the **repo root**, NOT inside `frontend-aurora/`. The Aurora
> handoff regenerates the entire `frontend-aurora/` directory, so any constraints
> doc placed there is wiped on the next export. Only files outside that bundle
> survive.

## MUI v9 API (the project is on v9, NOT v5)

- **`Stack` has no layout shorthand props.** `alignItems`, `justifyContent`, `gap`,
  `flexWrap` are NOT valid `<Stack>` props — put them in `sx`:
  `<Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>`.
  (`direction` and `spacing` remain valid props.)
- **`PaperProps` was removed** from `Drawer` / `Menu` / `Popover` / `Dialog`. Use
  `slotProps={{ paper: { sx: {...} } }}`.
- **Icon names** must exist in `@mui/icons-material` v9. The outlined variant is
  `CheckCircleOutlined`, not `CheckCircleOutline`. Prefer the `*Outlined` suffix.
- **`useTheme` is generic.** Don't type a theme parameter as
  `ReturnType<typeof useTheme>` — it resolves to `unknown` and every `.palette`
  access fails to compile. Import `Theme` from `@mui/material/styles` and annotate
  the parameter `(t: Theme)`.

## ESLint (the build fails otherwise)

- Braces on every `if` / `else` / `for` / `while` body, even one-liners:
  `if (!el) { return; }`.
- Single quotes in TS/TSX; double quotes in JSX attributes.
- No stray/duplicate JSX closing tokens — verify each `{cond ? (...) : (...)}` and
  `{cond && (...)}` block closes exactly once (a stray `)}` is a parse error).

## Charts (`AreaTrendChart` and any hand-built SVG chart)

- **X-tick index must guard the empty case:** `n === 0 ? [] : n === 1 ? [0] : [...]`.
  Never index `axisDates[0]` when `n === 0` — it throws and blanks the page.
- **Y-axis uses a fine "nice-max" ladder** (`1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10`)
  so the data peak fills most of the plot height — not the coarse `1/2/5/10` ladder
  that leaves large empty headroom.
- **X-axis labels are two-line date+time for any window > 2h** ("Jun 4" over "9 PM"),
  and a single time line for windows <= 2h. `PAD.bottom` must be ~44 to fit two lines.
- **Series spanning orders of magnitude must NOT be stacked on a linear axis.** When
  one series dwarfs the others (token types: cache-read is 100x input), stacking
  crushes the small lines together at the top. Use `stacked={false}` +
  `yScale="log"` so each series is its own line from a shared floor. The Token Usage
  "Token usage over time" chart relies on this; "Calls over time" stays stacked-linear.

## Data wiring (do NOT ship sample fixtures as the live source)

- Pages fetch via TanStack Query in the `<Name>Page.tsx` container, keyed on the
  window selection; views are pure props. Never default a card to a
  `*SampleData` / fixture constant as its live data source.
- **Metrics page** (`/insights`) reads `/api/metrics/catalog`, `/api/metrics/cost`,
  `/api/metrics/distribution`. `metricsApi.ts` must default to live
  (`USE_SAMPLE_DATA = env.VITE_METRICS_SAMPLE === '1'`) and import fixtures from
  `./components/metricsExplorerData` (that file lives under `components/`).
- **Token Usage page** `Cost` and `Token sum by model` cards read from the existing
  `/api/sessions/token-usage` response (already window-aware), NOT the `TOKEN_COST` /
  `TOKEN_BY_MODEL` fixtures. The cost fields are **nested under `summary.cost`**
  (a `CostSummary`) and are **pre-formatted strings**, not flat numbers:
  - "Total cost" headline value = `summary.cost.spend24h` (already `$39.75` /
    `$1,284` / `$1.2M`). There is **no** `summary.totalCostUsd` number — do not
    invent one or call `formatUsd()` on it.
  - The vs-previous caption = `summary.cost.deltaPct` (already a signed string like
    `+8.0%` / `-3.0%`). There is **no** `summary.costDeltaPct` number.
  - Per-model spend = `summary.cost.byModel`; per-model tokens = `summary.byModel`.
- The Cost period labels ("Spend · …", "vs. prev …", composition footer "Over the
  …") take a `periodLabel` prop derived from the selected window — don't hardcode
  "24h". Delta arrow/color is sign-aware (down/green when cost decreased).
- **Sessions page — the "Total sessions" `StatCard` shows the count plus a
  `sessionsTrend` sparkline; never a "N fresh · M resumed" `sub`.** `SessionKpis` has
  **no** `freshSessions` / `resumeSessions` fields. Its fields are `totalSessions`,
  `medianCostUsd`, `p95CostUsd`, `medianCostPerActiveMinuteUsd`, and `sessionsTrend`
  (`number[]` — new-session count per window bucket, summing to `totalSessions`; the
  card renders it via the `SessionsSparkline` child). Reason there's no fresh/resume
  split: every `start_type="resume"` session in this telemetry is a long-lived
  non-interactive heartbeat host emitting only `session.count` (no cost/tokens), so
  the KPI population is ~all fresh and the split would be permanently "N fresh · 0
  resumed". The per-row `startType` badge in the sessions grid stays — that's factual
  per session; only the summary split is gone.
- **Sessions page — the other three KPI `StatCard`s keep their `sub` caption** (regen
  tends to drop them to value-only). Restore: "Median cost / session" →
  `sub="half of sessions cost less"` (and `accent`); "P95 cost / session" → a dynamic
  caption `<accented>{Math.round(totalSessions * 0.05)}</accented> session(s) above this`
  (count is the ~5% tail above P95, accented in `primary.main`, pluralized);
  "Median $/active min" → `sub="cost per minute of work"`.

## Theme

- `theme.ts` must keep the `MuiStack` `styleOverrides.root` that forwards
  `alignItems` / `justifyContent` / `gap` / `flexWrap` from `ownerState` — runtime
  back-compat for any generated v5-style Stack usage that slips through.

## Backend contract

- New read endpoints and their exact response shapes are documented in
  `frontend-aurora/BACKEND.md`. The frontend types in `api.ts` and
  `components/metricsExplorerData.ts` match those shapes 1:1 — keep them in sync.
  When the generated view needs a field, read it from the documented shape rather
  than inventing a flatter field name.
