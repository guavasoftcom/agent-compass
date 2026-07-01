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
  pages, so the query cache is keyed on the selection and will not re-fetch on unrelated renders.
- `refetchInterval` is not set, so the query never polls — consistent with the `hideAutoRefresh`
  flag on `PageActions`.
