/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
# Permission Denials page

Tool permission denials and hook execution outcomes: which tools the agent was blocked from using,
broken down by denial source (policy rule vs. hook vs. user decision), plus a per-hook summary of
blocking and non-blocking execution errors. Backend counterpart: `ToolActivityController`
(`backend/.../controller/ToolActivityController.java`), endpoints `/api/tool-activity/denials` and
`/api/tool-activity/hook-executions`.

## Files

```
PermissionDenialsPage/
├── PermissionDenialsPage.tsx      container — reads useSectionContext, runs both queries,
│                                  derives KPI values, passes flat props to the view
├── PermissionDenialsPageView.tsx  view — KPI strip + tool-breakdown grid + hook table;
│                                  derives donut slices + tooltip from denialRows in a useMemo
├── ToolDenialsCard.tsx            "Denials by tool & source" — stacked bars per tool broken
│                                  down by denial source, with a legend per tool block
├── HookExecutionsCard.tsx         "Hook execution outcomes" — MUI Table with total/OK/
│                                  blocking/non-blocking columns; blocking errors shown as a
│                                  red chip, zero as muted text
└── index.ts                       re-exports container default
```

## Visual layout

```
┌─ PageLayout (no title — subtitle only) ─────────────────────────────────┐
│ subtitle (fixed string, no PageActions — window + reload live in         │
│ the parent SectionLayout chrome)                                          │
├──────────────────────────────────────────────────────────────────────────┤
│ ┌── 4-column KPI grid ───────────────────────────────────────────────┐   │
│ │ Total denials (accent) · Distinct tools denied · Hook blocking     │   │
│ │ errors · Most denied tool                                          │   │
│ └────────────────────────────────────────────────────────────────────┘   │
│ ┌── 2-column grid (1.6fr · 1fr) ─────────────────────────────────────┐   │
│ │ ┌─ ToolDenialsCard ──────────────┐  ┌─ DonutCard ────────────────┐ │   │
│ │ │ "Denials by tool & source"     │  │ "Denials by tool"          │ │   │
│ │ │ stacked bar + legend per tool  │  │ donut · center = total     │ │   │
│ │ │ sorted by total desc           │  │ ranked legend              │ │   │
│ │ └────────────────────────────────┘  └────────────────────────────┘ │   │
│ └────────────────────────────────────────────────────────────────────┘   │
│ ┌── HookExecutionsCard (full width) ─────────────────────────────────┐   │
│ │ "Hook execution outcomes" · table: event · hook · total · ok ·     │   │
│ │ blocking (red chip) · non-blocking                                  │   │
│ └────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

Both fetchers live in `api/endpoints.ts` (shared barrel, `from '../../api'`).

| Component (hook)                 | Query key                        | Fetcher → endpoint |
|----------------------------------|----------------------------------|--------------------|
| `PermissionDenialsPage` (`useQuery`) | `['tool-denials', selectionKey]` | `fetchToolDenials(selection)` → `GET /api/tool-activity/denials?…` |
| `PermissionDenialsPage` (`useQuery`) | `['hook-executions', selectionKey]` | `fetchHookExecutions(selection)` → `GET /api/tool-activity/hook-executions?…` |

`ToolDenialsCard`, `HookExecutionsCard`, and `DonutCard` never fetch — they receive
`denialRows` / `hookRows` as props and do all derivation locally.

## Data flow and semantics

- **Section tab, not top-level route.** The container reads `selection` and `autoRefresh` from
  `useSectionContext()` (not `useWindowContext()`). Window selection, the reload button, and the
  auto-refresh toggle live in the parent `SectionLayout` chrome, so `PageLayout` here receives no
  `actions` prop and no `title`.
- **`selectionKey`** is `preset:<minutes>` or `custom:<startTimestamp>:<endTimestamp>` — same
  shape used across all section tab containers. Both query keys include it so the TanStack cache
  splits cleanly per window.
- **`refetchInterval`** is `AUTO_REFRESH_INTERVAL_MS` (60 s) when `autoRefresh && selection.kind
  === 'preset'`; `false` for custom ranges (fixed end timestamp, no point polling).
- **KPI derivation in the container.** `PermissionDenialsPage` computes `totalDenials`,
  `distinctDeniedTools`, `totalBlockingErrors`, and `mostDeniedTool` in a single `useMemo` over
  `denialRows` and `hookRows`. The most-denied tool is the key with the highest aggregate count
  across all `(tool, source)` pairs.
- **View-local derivation.** `PermissionDenialsPageView` derives `denialSlices` (sorted by
  count desc, colored via `colorForIndex(index)`) to feed `DonutCard` and the top-share stat
  on the "Most denied tool" tile. `toolListSub` builds the subtitle for the "Distinct tools
  denied" tile — up to three tool names displayed inline; longer lists truncate with an ellipsis
  wrapped in an MUI `Tooltip` that shows the full set on hover. `blockingHookCount` is the
  number of distinct hooks that have `blockingErrors > 0`.
- **`ToolDenialsRow` shape.** Each row is `{ tool, source, count }` — one row per `(tool,
  source)` pair. `ToolDenialsCard` groups them by `tool`, sorts groups by total desc and sources
  within a group by count desc, then renders a stacked proportional bar plus an inline legend.
- **Denial source colors.** `useSourceColor` in `ToolDenialsCard` assigns stable hues per source
  name so the same source reads the same color across every tool's bar. `config` → `colorForIndex(0)`,
  `hook` → `colorForIndex(2)`, `user_reject` → `colorForIndex(1)`, `user_temporary` →
  `colorForIndex(4)`, `user_permanent` → `theme.palette.error.main`, `user_abort` →
  `theme.palette.text.disabled`. Unknown sources fall through a deterministic hash into the
  palette rather than crashing or showing a default color.
- **`HookExecutionRow` shape.** Each row is `{ hookEvent, hookName, total, successes,
  blockingErrors, nonBlockingErrors, cancelled }`. `HookExecutionsCard` renders one table row per
  `(hookEvent, hookName)` pair; blocking errors use a red pill (`error.main` on a 16% opacity
  background); a zero is muted with `text.disabled`.

## Gotchas

- **No `title` prop on `PageLayout`.** The page subtitle is a fixed string — the section
  header above the tabs already names the section. Don't add a `title` prop without checking
  the SectionLayout chrome height; it would duplicate heading hierarchy.
- **`DonutCard` receives `denialSlices` from the view, not from the container.** The container
  only computes the four scalar KPIs; slice derivation (including `colorForIndex` assignment)
  lives in the view's `useMemo`. This is intentional — the slices are purely presentational and
  the view owns them.
- **`mostDeniedTool` is derived from aggregated `(tool, source)` rows**, not from the raw
  `denialSlices` array that the view builds separately. Both end up with the same winner, but
  the container's derivation happens before the view exists and is the authoritative KPI value.
- **`cancelled` field on `HookExecutionRow` is not rendered.** The table shows total, OK,
  blocking, and non-blocking. If the cancelled count becomes significant enough to display, add
  a column to `HookExecutionsCard` — the data is already in the row.
