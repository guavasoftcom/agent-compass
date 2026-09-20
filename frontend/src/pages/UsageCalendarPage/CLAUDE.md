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
# Usage Calendar page

Top-level page (Activity group, `/usage-calendar`) showing a month or week of daily usage: cost, tokens
and skill/subagent invocations per day, a "Color by" heat tint over the cells, a four-card KPI strip, and a
click-through day drawer. Backend counterpart: `UsageCalendarController` → `UsageCalendarService`
(`backend/.../controller/UsageCalendarController.java`), served at `GET /api/usage/calendar/daily`. Built from
`design_handoff_usage_calendar/` (README + `Usage Calendar Mockup.html`), ported by hand rather than pasted.

## Files

```
UsageCalendarPage/
├── UsageCalendarPage.tsx          container — view / anchor / colorBy / drawer state, the two rollup queries
│                                  (current + prior period), the three per-day drawer queries, the "today" tick
├── UsageCalendarPageView.tsx      view — PageLayout + PageActionsView, control bar, KPI strip, grid, drawer;
│                                  no queries, no context
├── UsageCalendarPageView.test.tsx vitest coverage for the view (renderWithProviders, prop fixtures)
├── usageCalendarApi.ts            UsageCalendarDay (mirrors the backend record) + fetchUsageCalendarDaily
├── usageCalendarDerivations.ts    pure: local-calendar period arithmetic, the 42-cell month grid, heat tint,
│                                  KPI totals + like-for-like deltas, buildKpiCards, buildModelRows, formatters
├── usageCalendarDerivations.test.ts
├── components/
│   ├── CalendarDayCell/           one day, two variants ('month' compact / 'week' tall, with sessions + active
│   │                              time, a spend-by-model bar and commit/PR badges) — the shared cell
│   ├── MonthCalendarGrid/         weekday header + six weeks of CalendarDayCell
│   ├── WeekCalendarRow/           seven CalendarDayCell in a row
│   ├── calendarGridProps.ts       the props both grids share
│   ├── CalendarPeriodPicker/      prev / period label / next — replaces WindowSelector on this page
│   ├── CalendarControlBar/        Month|Week toggle, Low→High heat legend, "Color by" menu
│   ├── UsageKpiStrip/             four StatCards; the week view adds a per-day Sparkline beneath each
│   └── UsageDayDrawer/            day quick-peek on the shared PeekDrawer
└── index.ts
```

## Who calls which API

| Container hook | Query key | Fetcher → endpoint |
|---|---|---|
| `useQuery` | `['usage-calendar-daily', from, to, timeZone, repositoryUrl ?? 'all']` (current period) | `fetchUsageCalendarDaily` → `GET /api/usage/calendar/daily?from=&to=&timeZone=[&repositoryUrl=]` |
| `useQuery` | same shape, the prior period's range | same fetcher — feeds only the KPI deltas |
| `useQuery` ×3, `enabled` once a day is picked | `['usage-calendar-day-skills' \| '-subagents' \| '-models', daySelectionKey]` | `fetchSkillUsage` / `fetchSubagentUsage` / `fetchTokenUsage` with a one-day `custom` `WindowSelection` |

The current-period query is the spine: its error wins `PageLayout`'s slot, and its `isLoading` or its error makes cells inert and blank (`isDayDataUnavailable`).
The prior-period query only drops the KPI deltas when it fails. The three drawer queries reuse **existing**
endpoints scoped to the day (`00:00:00.000`..`23:59:59.999` local, the shape `WindowSelector` emits), so nothing
new exists server-side for the drawer's lists.

## Data flow and semantics

- **The day is the browser's LOCAL day, and the backend is told the zone.** `from` is the first local midnight,
  `to` the EXCLUSIVE local midnight after the last day, both full UTC ISO instants; `timeZone` is
  `Intl.DateTimeFormat().resolvedOptions().timeZone`. The backend buckets with `AT TIME ZONE`, so its `date`
  keys equal `toDateKey()` here by construction. Date arithmetic goes through the `Date` constructor
  (`addDays`), never millisecond maths, so a daylight-saving day never drifts off midnight
  (`usageCalendarDerivations.test.ts` pins the spring-forward case).
- **Cost and tokens are counter-derived**, the Tokens and Sessions pipeline — not the `api_request` figures the Cost
  page reads — so a day's cost can read slightly off the Cost page. The page subtitle says so. Never put a figure
  from here and one from the Cost page in the same sentence (AGENTS.md's two-pipelines note). The drawer's model
  split (`fetchTokenUsage`) is the same counter pipeline, so it reconciles with the day's own headline figures.
- **KPI deltas are like-for-like, not the mockup's.** The mockup compares a half-finished month with all of last
  month, which shows a permanent red drop mid-month. `comparablePriorDays` trims the prior period to as many days
  as the current one has elapsed; a fully elapsed period compares against the whole prior. When the prior month is
  the shorter one (February against March) `buildKpiCards` trims the current side to the same day count for the
  deltas only, so identical daily usage reads 0.0% rather than 31/28; the headline value and the active-days
  `/ N` suffix still cover every elapsed day. A zero/missing prior
  gives no delta at all (`percentChange` → null) rather than a fabricated one. `StatCard`'s trend is green-up /
  red-down regardless of metric, so a cost *rise* reads green — that is `StatCard`'s convention, not a judgment.
- **Heat is normalized against the visible period's ACTIVE, NON-FUTURE days** (`buildHeatPercents`), 6% + normalized
  × 16% of the "Color by" metric mixed into the surface with `color-mix`. Inactive, future and out-of-month days
  render the plain surface, and a future day's value can never squash the real ones. With one active day the
  range is zero and that day sits at the floor.
- **KPI bars are week-view only.** Month view renders each `StatCard` with no `children` (headline number and delta
  alone): 28-31 bars in one card is noise. `UsageKpiStrip` takes `showBars`, and the view passes `view === 'week'`.
- **The week cell's model bar is the rollup's `costByModel`, not a second request.** `buildModelMix` turns a day's
  models (largest spend first, as the backend sends them) into segments whose shares sum to exactly 100 of the
  models' own total, and colors each by its rank that day — the same rank-by-spend rule the drawer's model split
  uses over its window, so a model can wear a different color on two days. A day with no model-attributed spend
  draws no bar at all rather than an empty track. Commit / PR badges read `commits` / `pullRequests` off the same
  row and each renders only when non-zero.
- **"No activity" is a real answer, not a missing one.** The backend returns one all-zero row per quiet day;
  `isActiveDay` is false for it and the cell says "No activity". A day with no row at all (still loading, or the
  rollup failed) renders nothing and is inert — a click then would open the drawer on figures that have not
  arrived, and an empty map after a failure must never read as a run of quiet days.
- **The week starts on Sunday**, in the period, the month grid, the header and the label alike. Everything keys off
  `Date#getDay()` (0 = Sunday) through `startOfWeek`; there is no Monday-shifted index left to drift out of step.
- **Only in-period, non-future, loaded days are buttons.** Month view shows six Sunday-first weeks (42 cells, as the
  mockup does); the neighbouring months' days fill the grid dimmed and inert. Cells are real `<button>`s with an
  `aria-label` carrying the day's figures.
- **Today comes from a minute tick** (`useNowTick`), memoized on its date key, so a page left open across midnight
  moves the today marker instead of freezing it.
- **The drawer keeps its content through the slide-out by keeping the selection.** Closing only clears
  `isDrawerOpen`; `selectedDateKey` stays, so the day's queries keep their keys and the content does not
  blank mid-animation. (`MetricExemplarDrawer` solves the same problem with a last-value state; keeping the
  selection is simpler here because the data is query-backed.)
- **Skills and subagents are two lists, never one.** The mockup merges them for brevity, but the data comes from two
  endpoints and they are two different things. "Agent runs" is also its own stat in the drawer's headline row.
- **"Open in Metrics Explorer" sets the global window, then navigates.** `/metrics` reads `WindowContext`, so the
  hand-off writes a one-day `custom` selection (repository is left off — it lives beside the selection in the
  context and is already what the calendar was filtered by) and calls `navigate('/metrics')`.

## Deviations from the mockup / handoff

- **No hourly sparkline in week cells.** The mockup draws one from random data and there is no hourly rollup to feed
  it (the rollup is per day, and building an hourly one is a separate backend piece). The week cell uses the room for
  sessions and active time, the spend-by-model bar and the commit / PR badges instead, and anchors them under the
  date rather than sinking them to the bottom, so the space the sparkline would fill stays empty.
- **Repository selector, no `WindowSelector`, no auto-refresh.** `UsageCalendarPageView` composes
  `PageActionsView` directly with the period pill in `windowSelector`'s slot — the same route `SettingsPage`
  takes around the shared `PageActions` — rather than adding a `hideWindowSelector` flag to it. Auto-refresh is
  hidden: there is no rolling window to keep fresh, only a calendar the user pages through. Reload refetches
  everything.
- **Drawer gains an "Agent runs" stat** (the rollup carries `subagentCalls`, and the two lists are separate).
- **`Sparkline` gained three opt-in props** (`color`, `emphasizedIndex`, `placeholderFromIndex`) for the KPI bars
  and **`PeekDrawer` was extracted** from `MetricExemplarDrawer` — both documented in `frontend/CLAUDE.md`.

## Gotchas

- **Range width is capped server-side at 42 days** (a 31-day month plus headroom; `TimeWindowParams`' 30 would reject
  a 31-day month). The month range is at most 31 days, so this is never hit from the UI.
- **Prior period for a month is the previous *calendar* month**, whose length can differ from the current one;
  `comparablePriorDays` slices to the current elapsed count and never reaches past the prior's own length, so it can
  return fewer days than asked for. Both sides of a delta are therefore trimmed to
  `min(current elapsed, prior length)`; trimming only the prior would compare 31 days against 28.
- **`buildKpiCards` measures the prior period "as of" its own last comparable day**, so every trimmed prior day
  counts as elapsed. Passing the real `today` there would treat all of it as future and return zeros.
- **The active-days card's bars use a 0.08 floor for a quiet day** (as the mockup does) so it still reads as a day
  rather than a gap; that value is a drawing height, not a metric.
