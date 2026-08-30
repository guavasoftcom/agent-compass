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
# SettingsPage

Route `/settings`, nav group **System**. The dashboard's view of itself: how much disk the telemetry
occupies, whether it is still arriving, what schema and build are running, which `tuning.*` values
drive every other page's aggregations, and what a retention cutoff would reclaim.

Backend counterpart: `SystemController` → `SystemService` → `SystemRepository`
(`backend/src/main/java/com/guavasoft/agentcompass/`). The repository's class javadoc carries the
measured query plans and timings that shaped the SQL — read it before changing any of it.

## Files

```
SettingsPage.tsx                          container: 5 useQuery, retentionDays state, refetch-all
SettingsPageView.tsx                      view: PageLayout + KPI strip + 5 sections
settingsApi.ts                            fetchers over the shared api/http getJson
settingsTypes.ts                          interfaces mirroring the Java records
settingsDerivations.ts                    pure helpers (freshness, shares, spans, config filter)
settingsDerivations.test.ts               24 cases over those helpers
components/StorageBreakdownCard.tsx       ranked share list + exact heap/index/TOAST table
components/IngestHealthCard.tsx           per-signal freshness chip + volume windows
components/SchemaBuildCard.tsx            version strip + scrollable flyway_schema_history table
components/EffectiveConfigurationCard.tsx searchable tuning.* list with the SQL-mirroring chip
components/PurgeDryRunCard.tsx            retention estimate, caveats, copyable SQL, purge button
components/PurgeConfirmDialog.tsx         type-to-confirm dialog gating the purge
index.ts
```

## Visual layout

```
┌───────────────────────────────────────────────────────────────────────────┐
│ SYSTEM · Settings                                            [ Refresh ]  │
├───────────┬───────────┬───────────┬───────────────────────────────────────┤
│ DB size   │ Growth    │ Total rows│ Last received                         │
├───────────┴───────────┴───────────┼───────────────────────────────────────┤
│ Storage by table                  │ What the space holds (donut)          │
│  ranked bars + exact table        │  heap / indexes / TOAST, DB-wide      │
├───────────────────────────────────┴───────────────────────────────────────┤
│ Ingest health — signal · status · last received · 1h/24h/7d · cardinality │
├───────────────────────────────────────────────────────────────────────────┤
│ Schema & build — app/java/postgres/schema strip + migration history       │
├───────────────────────────────────────────────────────────────────────────┤
│ Effective configuration — 8 groups, searchable, mirroring chips           │
├───────────────────────────────────────────────────────────────────────────┤
│ Retention dry run — 30/60/90/180d toggle, estimate, caveats, SQL          │
└───────────────────────────────────────────────────────────────────────────┘
```

## Who calls which API

| Component | Query key | Fetcher → endpoint |
|---|---|---|
| KPI strip, `StorageBreakdownCard`, donut | `['system-storage']` | `fetchStorageOverview` → `GET /api/system/storage` |
| `IngestHealthCard` | `['system-ingest']` | `fetchIngestHealth` → `GET /api/system/ingest` |
| `SchemaBuildCard` | `['system-build']` | `fetchSystemBuild` → `GET /api/system/build` |
| `EffectiveConfigurationCard` | `['system-configuration']` | `fetchEffectiveConfiguration` → `GET /api/system/configuration` |
| `PurgeDryRunCard` | `['system-purge-preview', retentionDays]` | `fetchPurgePreview` → `GET /api/system/purge-preview?days=` |
| `PurgeConfirmDialog` | `useMutation` (no key) | `purgeTelemetry` → `DELETE /api/system/telemetry?days=&confirmation=` |

## Documented deviations from the page conventions

Two, both deliberate:

1. **No window scoping.** These figures describe the database as it stands, not a slice of it, so the
   query keys carry **no window key**, there is no `refetchInterval`, and the page does not read
   `useWindowContext()`. It is the only page in the app like this.
2. **No `PageActions`.** `PageActions` composes a `WindowSelector` with reload and auto-refresh, and
   `PageActionsView` takes `windowSelector` as a *required* prop — two thirds of it would be dead
   here. The page passes a bare `GhostButton` into `PageLayout`'s `actions` slot instead. Prefer this
   over adding a `hideWindowSelector` flag to the shared component.

Five independent queries rather than one combined endpoint, on purpose: configuration and build
resolve in about 2 ms while the ingest aggregation takes ~1.3 s, so the cheap blocks paint
immediately instead of waiting behind the expensive one.

## Data flow and semantics

- **Growth is an estimate, and says so.** No historical size samples are retained, so
  `estimatedBytesPerDay` is average on-disk bytes per row times the last seven days' insert rate. It
  assumes row size is stationary and therefore understates a table whose payloads have been growing.
  Never present it as a measurement.
- **Freshness reads `newestReceivedAt`, not `newestTimestamp`.** The former is when this server
  persisted the row; the latter is when the agent says the event happened. A collector that stopped
  forwarding is exactly what this card exists to catch, and every other page would keep rendering
  stale data silently. `ingestFreshness` buckets it: live ≤ 15 min, delayed ≤ 24 h, then stale.
- **`overridden` means "differs from the compiled-in default", not "appears in application.yml".**
  `application.yml` sets `bash-antipattern-replacements` and `externally-determined-tools` to values
  identical to their defaults, so both correctly report `overridden: false`. That is the more useful
  signal — it answers "is this instance behaving unusually", not "is this key written down".
- **The mirroring flag is three-state.** `MIRRORED` (14 properties) means the value is written as a
  literal into migration SQL and overriding it needs a new migration. `SHARED_LITERAL` (3) means
  `V6`'s severity function hardcodes the same string for its own reasons — overriding does not
  invalidate the SQL, but the two then describe different events. Everything else is `NOT_MIRRORED`
  and only the two flagged states get a chip; labelling 30 safe properties "safe" would bury the 17
  that are not.
- **Signal ordering is server-side and load-bearing.** `UNION ALL` guarantees no order and the live
  database was observed returning traces-logs-metrics; the repository's `ORDER BY signal` is what
  keeps the rows from reshuffling between refreshes. Don't sort them again here.

## The purge

The page is read-only except for one action: `DELETE /api/system/telemetry`, the only write in the
whole dashboard API. Four things guard invocation, and none is decoration:

1. **A type-to-confirm dialog.** It restates the cutoff as an absolute timestamp (not "30 days"),
   lists per-table row counts, carries the warnings, and keeps the button disabled until the operator
   types `PURGE`. The dialog is **mounted only while open** so the typed phrase resets itself each
   time — an effect that cleared it would trip the "no setState in an effect" lint rule, and a stale
   confirmation could pre-arm the button for a window nobody reviewed.
2. **A server-side confirmation phrase.** `SystemService.PURGE_CONFIRMATION_PHRASE` is re-checked in
   the service, so a client that skipped the dialog still cannot delete anything. It is not auth —
   there is none — it makes accidental invocation impossible.
3. **Bounded retention.** `@Min(1) @Max(3650)` on the controller, same bounds as the preview.
4. **Whole-session gating in one transaction.** See below — this is the mechanism, not just a guard.

**What the purge actually deletes: sessions, not rows.** The naive design — `DELETE ... WHERE
timestamp < cutoff` per table — silently splits any session whose activity straddles the cutoff:
early turns gone, recent ones kept, lifetime cost and token totals permanently understated. Measured
on the live database: 0 sessions straddle the default 30-day window, but 21 straddle a 7-day one,
accounting for 604k rows a naive delete would have partially removed. `session.id` sits in the
`attributes` jsonb of **all three tables** (100% coverage on live data), which is what makes
whole-session gating possible: `SystemRepository.DORMANT_SESSION_IDS_SUBQUERY` computes every
session whose *last activity anywhere* — logs, metrics, or traces — is older than the cutoff, and a
row is only deleted if its session is in that set (a sessionless row falls back to plain row-age
deletion, the pre-session-gating behavior, unobserved on live data but not guaranteed by the schema).
A session with any recent activity is left completely alone, including its oldest rows.

**The stream-marker rule stacks on top, inside `metric_points` only.** `value_delta` is computed at
ingest against the previous row in the same stream and falls back to zero when there is none, so
even a session judged dormant and eligible for deletion still keeps the newest row of each of its
metric streams — `SystemRepository.purgeMetricPoints`'s `EXISTS` clause is unconditional, not gated
on session dormancy. This is deliberate belt-and-suspenders, and the belt is a defensive assumption
rather than a confirmed mechanism: "no activity anywhere for a full retention window" is strong
evidence the underlying process exited (Claude Code re-emits every counter roughly once a minute for
its life), but nothing here has verified a way for a purged `session.id` to actually re-emit — it is
specifically *not* known to be `claude --resume`, which a live-database investigation found mints a
disjoint, never-before-seen session id for the heartbeat it emits (a genuinely new stream needs no
predecessor, so that's not the corruption case; what session id ongoing interactive work uses after a
real resume is unverified). Absent a way to rule the possibility out entirely, the marker is kept
regardless of dormancy. On the live database this fallback retains ~2,200 rows at the default window
— the same count the old, session-unaware design produced, because at 30 days every one of those rows
belongs to a dormant session.

**A separate, unrelated gap: `cleanupPeriodDays`.** Claude Code's own local setting (default 30 days)
controls how long a session's transcript stays resumable on the user's machine — it is never sent as
telemetry, so this application cannot see it. If an operator's retention window is shorter than their
`cleanupPeriodDays`, a session can still be resumable in Claude Code after its telemetry is already
purged: the conversation continues normally on resume, but the Sessions/Logs/Traces pages show that
session's history starting only at the resume point. Nothing in this codebase can close that gap — it
spans two independent systems — so `PurgeConfirmDialog` and `PurgeDryRunCard` both say to set the
retention window at least as long as `cleanupPeriodDays` if dashboard history should survive as long
as sessions stay resumable.

Three things must stay in lockstep or the page starts lying: the preview's `preservedRows` count, the
`DELETE` the endpoint runs, and the SQL the "Copy SQL" button hands over — all three run the identical
predicate. An operator pasting a naive `DELETE FROM metric_points` (or cutting one table without the
others) would corrupt their own counters or split a session, which is why the generated script wraps
all three deletes in one transaction behind a shared `dormant_sessions_for_purge` temp table.
`SystemPurgeIntegrationTest` is where each guarantee has its own test:
`purgeNeverTouchesASessionThatIsStillActiveInAnySignal` and
`purgeGatesOnActivityAcrossAllThreeSignalsNotJustOneTable` prove the whole-session mechanism (the
second specifically proves cross-signal gating, not just per-table);
`purgeKeepsALiveStreamsPredecessorSoTheNextEmissionIsNotASpike` and
`purgeStillKeepsAStreamMarkerInsideAFullyDormantSession` prove the marker survives even inside an
eligible session — each purges, re-ingests, and asserts the new delta is the increment, not the whole
counter.

## Gotchas

- **A purge is irreversible and there is no undo.** The preview is the only safety net before it, and
  the result banner is the only record after it. `SystemQueryIntegrationTest` still asserts the
  *preview* changes nothing — keep that separation: `purge-preview` measures, `telemetry` deletes.
- **The purge is synchronous and can run for minutes.** `purgeTelemetry` deliberately bypasses the
  shared `getJson` helper: it needs `DELETE`, no client timeout, and the server's own error text
  (a refused confirmation is a 400 whose body explains itself). The request transaction raises
  `statement_timeout` to 30 minutes via `set_config(..., is_local => true)`, which reverts at commit
  so the raised ceiling cannot leak onto the next borrower of that pooled connection.
- **Changing the retention toggle resets the purge result.** A result banner describing a different
  cutoff would read as if it applied to the newly selected one.
- **The retention toggle re-keys the preview query** (`['system-purge-preview', retentionDays]`), so
  switching to a window not already cached drops `purgePreviewQuery.data` back to `undefined` for a
  beat. `PurgeDryRunCard` renders `PurgeDryRunSkeleton` — an MUI `Skeleton` placeholder shaped like
  the real summary line, table, warning box, and SQL box — during that gap instead of a single
  "Estimating…" line, so the card holds its height rather than collapsing and snapping back open.
- **`DonutCard` needed a `formatSliceValue` prop** to render this page's byte slices; every other
  caller passes counts and gets the default `toLocaleString()`. If you add a donut whose values are
  not counts, pass a formatter rather than pre-scaling the numbers, which would break the ring's own
  proportions.
- **Numeric table cells set `whiteSpace: 'nowrap'`.** A size reads as one token — without it, "1.6
  GB" split across two lines at the `md` breakpoint.
- **`flyway_schema_history` is in the storage table on purpose**, using `installed_on` as its time
  axis. It is 48 KB and will always round to 0.0% of the database; that is the honest answer, not a
  bug to filter out.
- **Adding a `tuning.*` property fails the build until it is classified.**
  `TuningPropertyCatalogTest` reflects over `TuningProperties`' declared fields and asserts every one
  appears in `TuningPropertyCatalog` exactly once. That is intentional: the moment a property is
  added is the cheapest time to answer "does this also need a migration?".
