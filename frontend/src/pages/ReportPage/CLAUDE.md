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
# Report page

Renders the markdown tuning report for a chosen time window and provides a one-click
"Copy markdown" button so the output can be pasted directly into a coding-agent chat for
self-tuning. Backend counterpart: `ReportController` → `ReportService`
(`backend/.../controller/ReportController.java`).

## Files

```
ReportPage/
├── ReportPage.tsx      container — window context, report query, copy handler
├── ReportPageView.tsx  view — PageLayout + monospace pre block
├── ReportPageView.test.tsx  vitest coverage for the view (renderWithProviders, prop fixtures)
└── index.ts
```

## Who calls which API

| Container (`useQuery`)      | Query key                  | Fetcher → endpoint |
|-----------------------------|----------------------------|--------------------|
| `ReportPage` (`useQuery`)   | `['report', selectionKey]` | `fetchReportMarkdown(selection)` → `GET /api/report?…` (returns `text/markdown`) |

`fetchReportMarkdown` lives in `api/endpoints.ts` and uses `getText` (not `getJson`) because the
response body is `text/markdown`, not JSON. `windowQueryParams(selection)` serialises the window
the same way as every other endpoint.

Auto-refresh is intentionally absent — `PageActions` receives `hideAutoRefresh`. The report is
generated on demand; polling it would be noisy and wasteful.

## Gotchas

- The raw markdown is displayed verbatim in a `<Typography component="pre">` styled with
  `fontFamilies.mono` — it is **not** rendered as HTML. The copy button writes `data` (the raw
  string) to the clipboard; both the display and the copy are intentionally the same plaintext.
- `selectionKey` follows the same `preset:<minutes>` / `custom:<start>:<end>` pattern as other
  pages, plus a trailing `:repository:<repositoryUrl ?? 'all'>` segment (see below), so the query
  cache is keyed on the selection and will not re-fetch on unrelated renders.
- `refetchInterval` is not set, so the query never polls — consistent with the `hideAutoRefresh`
  flag on `PageActions`.

## Repository attribution (2026-09)

This is the last page in the repository-attribution rollout (`.design-docs/repository-attribution-
plan.md`, Phase 4) — `CostPage` is the template. `ReportPage` reads `repositoryUrl`/
`setRepositoryUrl` from `useWindowContext()`, appends `:repository:<repositoryUrl ?? 'all'>` to
`selectionKey`, and calls `fetchReportMarkdown({ ...selection, repositoryUrl })` — the
`repositoryUrl` field lives on `WindowSelection` itself (`api/types.ts`), so `windowQueryParams`
(shared by every fetcher, including `fetchReportMarkdown`'s `getText` call) stays the single choke
point for turning it into a query param without a second fetcher argument. `repositoryUrl`/
`onRepositoryUrlChange` are threaded down to `ReportPageView`, which passes them straight through to
`PageActions` so `RepositorySelector` renders beside `WindowSelector`. The backend's `GET
/api/report` endpoint accepts the same `repositoryUrl` param the other windowed endpoints do, so a
report generated with a repository selected reflects only that repository's telemetry.
