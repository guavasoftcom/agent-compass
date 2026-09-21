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
SettingsPage.tsx                                   container: 8 useQuery, 5 useMutation, retentionDays +
                                                    Ollama form state (incl. enabled + dirty tracking),
                                                    tab-switch guard, refetch-all
SettingsPageView.tsx                               view: PageLayout + KPI strip + 5 tabs (6th is Ollama,
                                                    rendered as two stacked full-width cards) +
                                                    UnsavedOllamaChangesDialog
SettingsPageView.test.tsx                          vitest coverage for the view (renderWithProviders, prop fixtures)
settingsApi.ts                                     fetchers over the shared api/http getJson, plus three
                                                    bare-fetch writes (purgeTelemetry, saveOllamaSettings,
                                                    testOllamaConnection — see Who calls which API)
settingsTypes.ts                                   interfaces mirroring the Java records
settingsDerivations.ts                             pure helpers (freshness, shares, spans, config filter)
settingsDerivations.test.ts                        24 cases over those helpers
components/StorageBreakdownCard/                   ranked share list + exact heap/index/TOAST table
components/IngestHealthCard/                       per-signal freshness chip + volume windows
components/SchemaBuildCard/                        version strip + scrollable flyway_schema_history table
components/UpdateCheckCard/                        "is a newer release out?" verdict + Enabled/Disabled switch +
                                                    Check now, stacked above SchemaBuildCard on the same tab —
                                                    see the Update check section below
components/EffectiveConfigurationCard/             searchable tuning.* list with the SQL-mirroring chip
components/PurgeDryRunCard/                        retention estimate, caveats, copyable SQL, purge button
components/PurgeConfirmDialog/                     type-to-confirm dialog gating the purge
components/OllamaConfigurationCard/                Enabled/Disabled toggle + editable Ollama port/model
                                                    (host hardcoded to localhost; DB override, falls
                                                    back to the application.yml default when cleared) —
                                                    see the Ollama configuration section below
components/OllamaStatusCard/                       Overridden/Using default chip + "Test connection"
                                                    probe against whatever is currently typed in the
                                                    sibling card, kept as its own card so testing
                                                    doesn't visually compete with editing
components/UnsavedOllamaChangesDialog/             confirms leaving the Ollama tab with unsaved edits
                                                    (port, model, or the Enabled toggle)
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
| `UpdateCheckCard` | `['system-update-check']` | `fetchUpdateCheck` → `GET /api/system/update-check` |
| `UpdateCheckCard` switch (`useMutation`) | no key; `onSuccess` writes the result into `['system-update-check']` | `saveUpdateCheckEnabled` → `PUT /api/system/update-check` |
| `UpdateCheckCard` "Check now" (`useMutation`) | no key; `onSuccess` writes the result into `['system-update-check']` | `fetchUpdateCheck(true)` → `GET /api/system/update-check?refresh=true` |
| `EffectiveConfigurationCard` | `['system-configuration']` | `fetchEffectiveConfiguration` → `GET /api/system/configuration` |
| `PurgeDryRunCard` | `['system-purge-preview', retentionDays]` | `fetchPurgePreview` → `GET /api/system/purge-preview?days=` |
| `PurgeConfirmDialog` | `useMutation` (no key) | `purgeTelemetry` → `DELETE /api/system/telemetry?days=&confirmation=` |
| `OllamaConfigurationCard` + `OllamaStatusCard` | `['system-ollama-settings']` | `fetchOllamaSettings` → `GET /api/system/ollama-settings` |
| `OllamaConfigurationCard` (`useMutation`) | no key; `onSuccess` refetches `['system-ollama-settings']` | `saveOllamaSettings` → `PUT /api/system/ollama-settings` |
| `OllamaStatusCard` (`useMutation`) | no key | `testOllamaConnection` → `POST /api/system/ollama/test-connection` |
| `OllamaConfigurationCard` (`useQuery`) | `['system-ollama-models', debouncedOllamaBaseUrl]` | `fetchOllamaModels` → `POST /api/system/ollama/models` |

## Ollama configuration section

Editable Ollama connection settings for the "Analyze trace" feature (`TraceDetailPage/CLAUDE.md`),
added as a sixth tab so this page stays the one place both the read side (Effective Configuration)
and the one piece of *write*-able runtime config live. Rendered as two stacked, full-width cards
(`OllamaConfigurationCard` above `OllamaStatusCard`, a plain `Stack`) rather than the Storage & Ingest
tab's side-by-side `row2` grid — the design handoff shows the pair top-to-bottom, not 2-up. Split into
two cards so testing a connection doesn't visually compete with editing the form, and so the
Overridden/Using default chip reads next to "is it currently reachable" rather than next to the fields
being changed.

- **The Enabled/Disabled toggle gates the whole feature, not just this form.** `OllamaConfigurationCard`
  renders a header `Switch` (`ollamaEnabled`/`onOllamaEnabledChange`) that dims and disables the port
  and model fields (nothing to edit or test while the feature is off); `OllamaStatusCard` independently
  disables its own "Test connection" button off the same `enabled` prop. **Save deliberately stays
  enabled regardless of `enabled`** — it's the only way to persist flipping the toggle off in the first
  place, so gating it on `!enabled` would make that edit impossible to commit. This is a real, persisted `ollama_settings`
  override (`EffectiveOllamaSettings.enabled`), not a client-only flag — the effective value falls back
  to `ollama.enabled` in `application.yml` (default `false` — the feature ships opt-in, since most
  installs have no local Ollama server running) exactly like `baseUrl`/`model` do. **The
  client-side dimming is a convenience, not the enforcement**: `TraceAnalysisService.regenerate` on the
  backend re-checks the effective `enabled` flag before ever calling Ollama and throws an
  `OllamaUnavailableException` (503, shown verbatim) when it's off, so a stale tab or a client that
  bypassed this dimming still cannot trigger a real analysis. Unlike `baseUrl`/`model`, `enabled` has no
  "blank clears the override" form — a toggle has no blank state — so `saveOllamaSettings` always sends
  an explicit `true`/`false`, never `null`, once the operator has touched it.
- **Leaving the tab with unsaved edits asks first.** `SettingsPage.tsx` tracks `isOllamaFormDirty`,
  set whenever the port, model, or Enabled toggle changes and cleared on a successful save or on
  discarding. `handleTabChange` intercepts a switch away from `'ollama'` while dirty: it parks the
  target tab in `pendingTab` instead of switching immediately, which is what opens
  `UnsavedOllamaChangesDialog` (its `open` prop is just `pendingTab !== null`, so there is no separate
  boolean to keep in sync). Cancel clears `pendingTab` and leaves the operator on the Ollama tab with
  their edits intact; "Leave without saving" reverts the three fields to the last-loaded
  `ollamaSettingsQuery.data` (the same values a refetch would show), resets the test/save mutations and
  the dirty flag, then completes the switch to `pendingTab`. Switching *to* the Ollama tab, or switching
  tabs while the form is clean, needs no confirmation and goes through `setActiveTab` directly.
- **Port and Model render their labels as a plain line above the field, not MUI's notched
  `label`.** The design handoff's `.fl` labels sit fully above the box, never cut into its border,
  so `OllamaConfigurationCard` leaves `TextField`'s `label` prop unset on both fields and renders a
  small `FieldLabel` (Sora, 12px, weight 600 — matching the handoff's `.fl` rule exactly) above each
  one instead. The accessible name moves to an explicit `aria-label` on `slotProps.htmlInput`
  (`'Port'` / `'Model'`, the latter merged onto whatever `Autocomplete`'s own `params.slotProps.htmlInput`
  already carries — combobox role/expanded/autocomplete attributes that must not be clobbered), so
  `getByLabelText('Port')`/`('Model')` in tests still resolve the same way they would with a real
  `label`. Both fields' typed value renders in `fontFamilies.mono` (matching the handoff's
  `.oinput input`), and **the Port value is additionally bold** (`PORT_INPUT_STYLE`) — the prefix
  adornment stays regular-weight so the two read at different visual weights.
- **Field chrome (`OLLAMA_FIELD_SX`) and the "dim" vs. "muted" text distinction are pulled from
  "Ollama Form Style Guide.html"**, a pixel-precise reference built specifically for this tab (see
  the four `.oinput`/`.ocombo-input`/`.btn`/dropdown token comments in that file, which name exact
  px/color values `Aurora Settings Mockup.html` only implies). Port and Model are both 44px tall,
  11px radius, with a border pinned to `divider` at rest **and** on hover — MUI's own default
  `OutlinedInput` border color is a hardcoded rgba, not palette-aware, so both states have to be
  overridden explicitly or a plain unfocused field would render a slightly different gray than the
  rest of the app. Only `:focus`/`.Mui-focused` gets a treatment: border → 32%-alpha
  `primary.main` plus a `0 0 0 3px` glow at 12% alpha, both via `slotProps.input.sx`. The style
  guide names a *third* text tone below `text.secondary` ("muted", `#6c6589`, used for `FieldLabel`)
  — "dim" (`#938cae`), used for the Port prefix and a non-large model's dropdown size label.
  `dimTextColor` derives it as `alpha(text.secondary, 0.7)` instead of hardcoding the hex, so it
  keeps working in dark mode, which the (light-mode-only) style guide has no token for.
- **The Model dropdown menu and its rows also match the style guide, and needed no new hover
  styling at all** — `action.hover` (`alpha(primary.main, 0.07)` in light mode) already equals the
  guide's `--hover`, so `MuiAutocomplete-option`'s default hover uses it for free. What did need
  overriding: `slotProps.paper.sx` (12px radius + `theme.custom.cardShadow`, the app's card-shadow
  token) and `slotProps.listbox.sx` (6px padding, 8px row radius, mono 13px, 9px/11px row padding).
  `renderOption` renders the name and the parameter-size annotation as two separate `<span>`s rather
  than one `"name — size"` string, so the size can carry its own color — `dimTextColor` normally,
  `severity.warning` (`#e6952b`, matching `--warn`) for a model at/above the large-model threshold —
  the same "warn in the dropdown before the Alert banner even appears" cue the style guide calls out.
- **Save and Test connection use `PRIMARY_ACTION_BUTTON_SX` (exported from `OllamaConfigurationCard`,
  imported by `OllamaStatusCard`), not `GhostButton`'s bare default.** `GhostButton`'s own sizing —
  30px tall, `text.secondary` at rest, brightening to `text.primary` only on hover — is tuned for
  the small toolbar/pager actions it was built for (`PurgeDryRunCard`'s Copy SQL/Show SQL, table
  pagers). The style guide's `.btn` is the opposite shape: 40px tall, bright `text.primary` ("ink")
  **at rest**, `theme.custom.cardShadow` always applied, and only on hover does it shift to
  `primary.main` text + a 32%-alpha `primary.main` border. One shared constant rather than two
  separate ad-hoc `sx` props, so Save and Test connection — the tab's two primary actions, on two
  different card components — can't visually drift apart.
- **`theme.custom.cardShadow`** (added to `theme.ts` alongside `progressTrack`/`rowStripe`/etc.) is
  the reusable form: `MuiPaper`'s `outlined` variant already applied `tokens.cardShadow` internally,
  but nothing non-`Paper` (a `Box`-based button, an `Autocomplete` popper's `Paper` slot) could reach
  it without duplicating the light/dark shadow values raw. Reach for `theme.custom.cardShadow` for
  any future non-`Paper` surface that needs the same shadow, rather than re-deriving it.
- **The host is hardcoded to `localhost` in the UI; only the port is editable.** Ollama always runs
  on the operator's own machine, so `OllamaConfigurationCard` never exposes an arbitrary host field —
  that would mean typing connection details bound for a possibly-external destination into a form on
  a telemetry dashboard. The card renders a `Port` field with a fixed `http://localhost:` adornment
  and reassembles the two into the `baseUrl` string `SettingsPage.tsx`'s state and
  `saveOllamaSettings`/`testOllamaConnection` still expect — `portFromBaseUrl` parses the port back
  out of that string for display, so a legacy DB override pointing at a non-localhost host (or a
  host: prefix in a different form) round-trips as an unset port field rather than crashing. No path
  segment is exposed either: `OllamaClient` (`backend/.../ollama/OllamaClient.java`) appends its own
  fixed `/api/generate` / `/api/tags` paths, so `baseUrl` never carries one.
- **Effective config = DB override if one is saved, else the `ollama.*` `application.yml` default** —
  the same "effective" framing `EffectiveConfigurationCard`/`overridden` already uses for `tuning.*`
  properties, deliberately reused rather than inventing new language for a second override mechanism
  on the same page.
- **The form seeds itself from the GET once, not on every refetch.** `SettingsPage.tsx` tracks
  `hasInitializedOllamaForm` and only copies `ollamaSettingsQuery.data` into the editable
  `ollamaBaseUrl`/`ollamaModel` state the first time it resolves — a background refetch (the page's
  Refresh button hits this query too) must not clobber unsaved typing. Uses the same render-time-diff
  idiom `PurgeDryRunCard` uses to reset its SQL disclosure on a retention-window change (an `if` at
  the top of the component body, not a `setState`-in-`useEffect`, which the project's lint rule
  rejects) rather than `useEffect`.
- **Saving a blank field clears that field's override back to default**, not "save an empty string" —
  `saveOllamaSettings` sends `null` for a blank/whitespace-only field (trimmed client-side) rather than
  `""`. `onSuccess` writes the server-normalized result straight back into the form and refetches the
  GET, so a cleared field's fallback value shows immediately.
- **"Test connection" probes whatever is currently typed, not necessarily what's saved.** Unlike the
  purge card's `retentionDays` (server-driven, always in sync with the preview query),
  `testOllamaConnection(baseUrl, model)` sends the two live form values, so a reader can check
  reachability before committing to Save. Editing either field resets any prior test result
  (`testConnectionMutation.reset()` in the two change handlers) — a stale "Reachable" banner sitting
  under freshly typed, untested values would be misleading.
- **A failed test is a normal result, not a query error.** `testOllamaConnection`'s endpoint always
  responds 200; success/failure lives in the JSON body (`{success, message}`), which is why
  `OllamaConfigurationCard` renders `testConnectionResult` (the probe's own verdict, success or
  failure, in a colored banner) and `testConnectionError` (an actual `fetch`/HTTP-layer failure —
  network down, non-2xx) as two separate slots rather than collapsing them into one.
- **The Model field is a `freeSolo` `Autocomplete`, populated by its own automatic, non-mutation
  query.** `POST /api/system/ollama/models` returns `{success, message, models: OllamaModel[]}` where each entry is
  `{name, parameterSize, parameterCountBillions}` — `models` always an array, empty on any failure
  (unreachable host, non-2xx, malformed response). `SettingsPage.tsx`'s `ollamaModelsQuery` is a
  `useQuery` keyed on `['system-ollama-models', debouncedOllamaBaseUrl]` — `ollamaBaseUrl` run through
  the shared `useDebouncedValue` (`lib/useDebouncedValue.ts`, the same hook the Logs/Traces search
  boxes use), not the raw per-keystroke form value `testOllamaConnectionMutation` reads — so typing a
  base URL fires one real request once the operator pauses rather than one per keystroke (each a
  round trip the backend forwards to Ollama's own `GET /api/tags`); it is deliberately not a mutation,
  since the requirement is "fetch automatically", not "fetch when the operator clicks something". Its
  failure is silent-degrade, not page-level: it is excluded from the page's `error` prop and from
  `handleReload`/`isReloading`, because an unfetched model list just means an empty dropdown and the
  field still works as free text — the same reasoning `testConnectionResult` already gets for a
  failed probe. `freeSolo` is why the Autocomplete is wired
  with `inputValue`/`onInputChange` rather than `value`/`onChange`: the operator's typed text is the
  thing the form tracks (a model that isn't installed yet, e.g. one about to be `ollama pull`ed, is a
  valid value to save), not a selection from the fetched list — picking a dropdown option and typing
  a fresh string both flow through the same `onModelChange` callback this way.
- **Each dropdown row is annotated with the model's parameter size** (`"name — size"`, e.g.
  `"llama3.1:latest — 8.0B"`; bare `name` when `parameterSize` is null) via `Autocomplete`'s
  `renderOption`, while `getOptionLabel` still resolves to the bare `name` — this is what keeps a
  picked option's saved value as the plain model name Ollama recognizes rather than the annotated
  display string leaking into `inputValue`. **Large models are warned about, never filtered out** —
  an explicit user decision: an operator who deliberately pulled a large model must still be able to
  select it, so nothing is hidden from the dropdown. `OllamaConfigurationCard` shows an inline
  `Alert severity="warning"` when the current `model` value matches a fetched entry (by name) whose
  `parameterCountBillions >= 13` — thresholding against the parsed numeric field, not the raw
  `parameterSize` string, which isn't reliably parseable (a MoE model's size is per-expert, e.g.
  `"8x7B"` for a 56B-parameter `mixtral`). A model typed free-solo that isn't in the fetched list
  (not yet pulled, or the list failed to load) shows no warning — there is nothing to check its size
  against, and warning on every free-typed keystroke would be noise.
- **A successful save shows a "Settings saved." confirmation** (`Alert severity="success"`), driven by
  `saveOllamaSettingsMutation.isSuccess` (`isOllamaSettingsSaved`) rather than a separate boolean —
  there is nothing to reconcile since the mutation already tracks it. It clears itself two ways: the
  moment any field is edited again (`handleOllamaBaseUrlChange`/`handleOllamaModelChange`/
  `handleOllamaEnabledChange` all call `saveOllamaSettingsMutation.reset()` alongside the existing
  `testOllamaConnectionMutation.reset()`,
  the same "don't let a stale result banner sit under freshly typed, unsaved values" rule the test
  banner already follows), and automatically after `OLLAMA_SAVE_CONFIRMATION_DISPLAY_MS` (4s) via a
  `setTimeout` that calls the same `.reset()` — `saveConfirmationTimeoutRef` holds the pending timer
  so a second Save click (or an edit) before the first confirmation finishes clears it rather than
  stacking timers or racing a stale one into resetting a newer save. Auto-dismiss resets the mutation
  itself rather than tracking a separate "banner visible" boolean in the card, so `OllamaConfigurationCard`
  stays pure props-in/JSX-out with no local state, consistent with every other card on this page.
  Mutually exclusive with `saveError` by construction (a `useMutation` is never simultaneously
  `isSuccess` and holding an `error`), but the card still guards `isSaved && !saveError` defensively
  rather than relying on that invariant silently.

## Update check

`UpdateCheckCard` sits above `SchemaBuildCard` on the Schema & Build tab (the running version is
already there) and answers "has a newer release been published than the one running?". It is the only
thing on this page — and the only thing in the application — that causes a request to a third party
(GitHub's latest-release endpoint, made by the **backend**, never the browser), which is why the card
says so in its own text and carries the switch.

- **The switch is a real, persisted setting enforced server-side, exactly like the Ollama toggle.**
  `update_check_settings` (`V36`) holds a singleton row; `UpdateCheckService` re-checks the effective
  value before any network call, so with the switch off `GET /api/system/update-check` returns without
  sending anything however it is called. The card's own dimming (Check now disabled, "Nothing is sent to
  GitHub") is a convenience, not the guarantee. Default is on (`update-check.enabled`), off for a
  deployment that sets it `false`.
- **It saves the moment it is flipped — no Save button, no dirty tracking, no tab-switch guard.** One
  boolean has none of the Ollama form's reasons for a Save step. `saveUpdateCheckEnabled` always sends an
  explicit `true`/`false`; the backend's null-clears-override form exists but nothing here uses it. The
  `PUT` returns the resulting status, so turning the check on answers in the same round trip and both
  mutations write that result straight into `['system-update-check']` with `setQueryData` instead of
  refetching. The switch and Check now are held disabled while a save is in flight.
- **A page load never bypasses the server's cache; only "Check now" does.** The plain query is cheap to
  repeat (the server reuses a success for 6 hours and a failure for 15 minutes), which is why it rides the
  page's Refresh button like the other queries. `Check now` is a `useMutation` calling
  `fetchUpdateCheck(true)`, not a `refetch()` of the query, because a refetch would re-run the query's
  own `refresh=false` function. Starting one clears the other mutation's error and vice versa, so a stale
  message never sits under a newer action.
- **A check that could not complete is a normal result, not an error.** The endpoint always answers 200;
  offline, rate-limited, private repository and "this is a development build" all arrive as
  `message` with `latestVersion: null`, and the card shows that text in place of a verdict — never
  "up to date", which would be a claim nobody verified. A real HTTP failure (the backend down) is the
  only thing that reaches the page-level `error` slot, via `updateCheckQuery.error`.
- **Versions display as `v2.8.0`, `dev` stays `dev`.** The server already strips `-SNAPSHOT` from
  `currentVersion` (a released image of `vX.Y.Z` reports `X.Y.Z-SNAPSHOT`), and the card adds the `v`
  itself; an unpackaged build's `dev` is left unprefixed rather than read as "vdev".
- **The release link is only rendered when it is `https://`.** The URL is text the server relayed from a
  third party (`html_url` in GitHub's response), so anything else renders the verdict with no link rather
  than a clickable surprise. It opens in a new tab with `rel="noopener noreferrer"`.

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
