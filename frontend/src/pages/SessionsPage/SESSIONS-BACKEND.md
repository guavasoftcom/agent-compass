# Sessions page — backend requirements

The rethemed **Sessions** page keeps its two existing endpoints — `GET /api/sessions` (the
paged grid) and `GET /api/sessions/summary` (the KPI cards) — and adds **one new on-demand
endpoint** for the prompt timeline (`GET /api/sessions/{id}/prompts`). Together the table
refresh needs a few **additive** fields on the two existing responses plus the new endpoint.
**No fixtures.**

| Change in the UI | Needs |
|---|---|
| **Removed** the *Session length* column | nothing — `wallSeconds` is no longer rendered (leave it on the row or drop it) |
| **New** *Tokens* column (per-session total, sortable) + **hover breakdown** | `tokens` + `tokenBreakdown` on each session row |
| **New** *Last activity* column (relative "time ago", **default sort**) | reuses existing `endTimestamp` — must be **sortable** |
| **New** *Terminal* column (interactive / non-interactive badge) | `terminalType` on each session row |
| **New** *Prompt* column (first-prompt preview + `+N` pill) | `firstUserPrompt` + `userPromptCount` on each session row |
| **New** row-expand → *prompt timeline* panel (per-turn model · cost · tool chips · trace link) | new `GET /api/sessions/{id}/prompts` endpoint |
| *Total sessions* card now shows a **trend sparkline** | `sessionsTrend` (per-bucket new-session counts) on the summary |

`terminalType` comes straight off the `claude_code.session.count` points you already ingest
(the `terminal.type` attribute); `tokens` is a windowed roll-up of `claude_code.token.usage`
joined on `session.id`.

---

## `GET /api/sessions` — add fields per row

Same request as today (`from`/`to` or preset window, `page`, `size`, `sort`, `direction`).
Add `tokens`, `terminalType`, `firstUserPrompt`, and `userPromptCount` to each row:

```jsonc
{
  "items": [
    {
      "sessionId": "a67f8c25-ed9c-461c-92eb-afd704e94c03",
      "startTimestamp": "2026-05-22T20:51:59.925Z",
      "endTimestamp":   "2026-05-22T21:34:18.000Z",  // NOW = Last activity column + default sort;
                                                    //   should track latest captured activity
      "costUsd": 23.51,
      "tokens": 5400000,                 // NEW — raw integer, client formats to "5.4M"
      "tokenBreakdown": {                // NEW — four-way split for the hover tooltip;
        "input": 61200,                  //        MUST sum to `tokens`
        "output": 148500,
        "cacheCreation": 402300,
        "cacheRead": 4788000
      },
      "toolCallCount": 230,
      "denialCount": 0,
      "activeTimeSeconds": 2276,
      "wallSeconds": 4810,               // no longer rendered; keep or drop
      "terminalType": "non-interactive", // NEW — "interactive" | "non-interactive"
      "firstUserPrompt": "Refactor the Aurora theme overlay so it applies cleanly…", // NEW
      "userPromptCount": 7               // NEW — total user prompts in the session
    }
    // … page of rows …
  ],
  "totalCount": 56
}
```

**How to compute each new field (per `session.id`, within the window):**

- **`terminalType`** — from the `terminal.type` attribute on that session's
  `claude_code.session.count` points. Map to the two-value enum the UI expects: anything that
  isn't `"interactive"` → `"non-interactive"`.
- **`tokens`** — **sum of `value_double`** for `claude_code.token.usage` points whose
  `attributes->>'session.id'` equals this session, over the window. Sum **all** token `type`s
  (`input`, `output`, `cache_read`, `cache_creation`); cache reads dominate, which is expected.
  Return a **raw number** — the grid formats `M`/`K`, matching the existing `costUsd` style.
- **`tokenBreakdown`** *(NEW)* — the same window roll-up **split by the `type` attribute** into
  `{ input, output, cacheCreation, cacheRead }` (map `cache_read`→`cacheRead`,
  `cache_creation`→`cacheCreation`). The four values **must sum to `tokens`** — the grid shows
  `tokens` as the sortable cell and reveals this breakdown on hover, and it must reconcile with
  the per-turn cards (see below). The tooltip also shows a **"Working" subtotal**
  (`input + output + cacheCreation`) above cache read — computed client-side, no field needed.
  Raw integers; any missing `type` is `0`, never null.
- **`firstUserPrompt`** — the session's **first meaningful user prompt**, whitespace-collapsed
  and truncated to **≤200 chars** server-side. **Null** when prompt capture is disabled
  (`OTEL_LOG_USER_PROMPTS`) or the session has no user-authored prompt — this can be null
  **even when `userPromptCount` > 0** (capture disabled but prompts still counted). The grid
  renders an em-dash for null and still shows the `+N` pill from `userPromptCount`.
- **`userPromptCount`** — total count of user prompts in the session. Drives the muted `+N`
  pill after the preview (`userPromptCount − 1` = "and N more"). Independent of
  `firstUserPrompt` being null.

> **Reset-aware count still applies.** `session.count` is re-emitted **once per minute per
> session** with `value_double = 1`, so never `SUM` the column. Group by **distinct
> `session.id`** for every per-session field above (and for the trend below).

**Sorting.** New sortable columns: **`tokens`** (`sort=tokens&direction=desc|asc`) and
**`endTimestamp`** (surfaced as the *Last activity* column and now the **default sort**,
`sort=endTimestamp&direction=desc`). `Terminal`, `Prompt`, and `Denials` are **not** sortable in
the UI, so they need no sort support. Other existing sortable fields (`startTimestamp`,
`costUsd`, `activeTimeSeconds`, `costPerActiveMinuteUsd`) are unchanged.

> **Later addition — `cacheEfficiency` sort.** The *Cache eff.* column
> (`cacheRead / (input + cacheCreation + cacheRead)`, derived client-side from `tokenBreakdown`)
> is sortable server-side via `sort=cacheEfficiency&direction=desc|asc`. The backend's `ORDER BY`
> uses the identical ratio and orders sessions with no input-side tokens `NULLS LAST` (they render
> as "—"). No new response field — the row already carries `tokenBreakdown`.

> **Default sort changed** from `costUsd desc` to **`endTimestamp desc`** (most recently active
> first) — recency is the operational landing state; cost remains one click away on its column.
> `endTimestamp` should reflect the session's **latest captured activity** (max event time for
> the `session.id`), not just a fixed session-close time, so live sessions sort to the top.

---

## `GET /api/sessions/{id}/prompts` — NEW endpoint (prompt timeline)

Fetched **on demand** when the user expands a session row — the full, untruncated per-turn
timeline for one session. **Not window-scoped** (no `from`/`to`), no paging beyond the path
segment; ascending by time, cap ~500 rows. Fires only while a row is open and is **not**
polled; re-expanding refetches past the global 30s `staleTime` so a live session's growing
timeline stays current.

> **Whole-session timeline vs. the dashboard window.** Because this endpoint returns the
> **entire** session (not window-scoped), a resumed session can include turns that started
> **before** the selected dashboard window. The panel renders those out-of-window turns
> **dimmed** with a "selected window starts / ends" hairline divider at each boundary
> crossing — so the backend does **not** filter by window here; return every turn and let the
> client compare each `timestamp` against the active window bounds.

```jsonc
[
  {
    "timestamp": "2026-05-22T20:52:04.120Z",
    "prompt": "Refactor the Aurora theme overlay so it applies cleanly over the base frontend",
    "traceId": "7ed9599a863b4c1e9f2a…",  // null for prompts that predate tracing (~35%) — omit the link
    "model": "claude-sonnet-4-5",         // NEW — model that served this turn
    "costUsd": 0.80,                       // NEW — cost attributed to this turn
    "tokens": {                            // NEW — four-way token split for this turn
      "input": 1840,                       //        (client sums input+output+cacheCreation
      "output": 3120,                      //         as the "working" figure, shows cacheRead
      "cacheCreation": 18400,              //         separately, and reconciles the session
      "cacheRead": 214000                  //         row total = SUM of its turns)
    },
    "tools": [                             // NEW — tool calls this turn triggered
      { "name": "Read", "count": 4 },
      { "name": "Edit", "count": 2 },
      { "name": "Bash", "count": 1 }
    ]
  }
  // … one per user turn, ascending by time, ≤500 …
]
```

**Field semantics:**

- **`timestamp`** — ISO-8601 of the user prompt / turn start.
- **`prompt`** — the turn's user prompt text (**full**, not truncated like the grid's
  `firstUserPrompt`). **Null** for pre-capture events (`prompt_text` wasn't recorded) — keep
  the row (it still counts toward `userPromptCount`); the panel renders an italic
  "(prompt text not captured)" placeholder rather than dropping it.
- **`traceId`** — the trace whose root span is this prompt's `claude_code.interaction`. **Null**
  for prompts from sessions that predate tracing (~35% of existing data) — the panel simply
  omits the "View trace" link, no disabled placeholder.
- **`model`** *(NEW)* — the model that served the turn (`gen_ai.request.model` / the
  `claude_code.token.usage` `model` attribute for the turn's interaction). The UI keys the
  model chip's label + accent dot on the leading tier token, so `"claude-sonnet-4-5"`,
  `"sonnet"`, etc. all resolve (Opus → violet, Sonnet → cyan, Haiku/other → muted). May be
  null (chip omitted).
- **`costUsd`** *(NEW)* — cost attributed to this turn. Raw number; the UI formats `$0.00`. May
  be null (cost omitted). When the turn carries a `traceId`, this is that trace's own cost —
  the summed `cost_usd` of the `api_request` logs stamped with the trace id (the `trace_costs`
  view, `V14`), which is exactly what the Traces pages show for the same trace, so the two
  surfaces never disagree — **for the one turn the backend bills it to.** Several turns in a row
  can share a trace (e.g. a bare slash command immediately followed by its real prompt before
  Claude Code closes the interaction span); the backend attributes the trace's cost to the
  earliest of those turns only, and every later turn sharing the same `traceId` renders `null`
  here rather than repeating the figure — so summing `costUsd` down the timeline still equals the
  session's real spend instead of double-counting a shared trace. Turns with no trace (or whose
  requests predate trace-id correlation) keep the older attribution: the `claude_code.cost.usage`
  points whose timestamp falls inside the turn's interval. The difference from the trace-id path
  is only in *bucketing* — both count the same requests — but a request completing after the next
  prompt was typed gets billed to the wrong turn under time bucketing, which is why the trace id
  wins when it exists.
- **`tokens`** *(NEW)* — the turn's token usage split by kind:
  `{ input, output, cacheCreation, cacheRead }` (map `cache_read`→`cacheRead`,
  `cache_creation`→`cacheCreation`), summed from the turn's `claude_code.token.usage` points
  grouped by `type`. The card shows **input + output + cacheCreation + cacheRead** as one
  combined total (the Aurora sync replaced the earlier "working tokens" figure plus muted
  "· N cached" secondary), with the full four-way split on hover. The field contract below is
  unchanged either way. **The session row's `tokenBreakdown` must equal the sum
  of its turns' `tokens`** so the grid figure and the timeline reconcile. Raw integers; missing
  kinds are `0`. May be null as a whole (the token line is then omitted).
- **`tools`** *(NEW)* — the tool calls the turn triggered, as `{ name, count }` objects
  (count = invocations of that tool in the turn), desc by count. Derive from the turn's
  `tool_result` / tool-decision events (attribute `tool_name`) grouped within the interaction.
  Empty array / null → the panel shows "No tool calls". The UI shows the first 5 + a `+N`
  overflow chip.

> **`model` / `costUsd` / `tokens` / `tools` are additive and optional.** The page reads them
> as optional fields directly on the canonical `SessionPromptRow` type (`api/types.ts`) and
> renders each independently, so the endpoint can ship `{ timestamp, prompt, traceId }` first
> (the chips simply don't appear) and add the per-turn fields later with **no frontend change**.

---

## `GET /api/sessions/summary` — add the sessions trend

Same window request. Add a `sessionsTrend` array (per-bucket new-session counts) to the
existing `SessionKpis` payload; the Total-sessions card draws it as a line sparkline:

```jsonc
{
  "totalSessions": 56,
  "medianCostUsd": 2.41,
  "p95CostUsd": 18.74,
  "medianCostPerActiveMinuteUsd": 0.214,
  "sessionsTrend": [1,0,1,2,1,2,3,2,1,3,4,3,2,1,2,4,3,2,3,4,3,2,3,4]  // NEW
}
```

- Bucket the window into ~24 even slices and count **distinct `session.id`** whose session
  **opened** in each slice (use the `start_timestamp` of the session's `session.count` points).
  Order oldest → newest. ~12–48 points renders well; an empty array hides the line.

---

## Frontend seam (types + fetcher)

The page reads `row.tokens` / `row.terminalType` / `row.firstUserPrompt` /
`row.userPromptCount` and `kpis.sessionsTrend`, so extend your `api.ts` types:

```ts
export interface SessionSummaryRow {
  sessionId: string;
  costUsd: number;
  activeTimeSeconds: number;
  startTimestamp: string;
  endTimestamp: string;
  wallSeconds: number;            // now unused by the grid; safe to keep
  toolCallCount: number;
  denialCount: number;
  tokens: number;                 // NEW
  tokenBreakdown: {               // NEW — sums to `tokens`; drives the hover breakdown
    input: number;
    output: number;
    cacheCreation: number;
    cacheRead: number;
  };
  terminalType: 'interactive' | 'non-interactive'; // NEW
  firstUserPrompt: string | null; // NEW — ≤200-char preview; null when capture disabled
  userPromptCount: number;        // NEW — drives the +N pill (count − 1)
}

// NEW — one row of GET /api/sessions/{id}/prompts. The base three fields are
// required; model / costUsd / tools are additive (read optionally by the panel).
export interface SessionPromptRow {
  timestamp: string;
  prompt: string | null;
  traceId: string | null;
  model?: string | null;                            // NEW
  costUsd?: number | null;                          // NEW
  tokens?: {                                        // NEW — four-way per-turn split
    input: number;
    output: number;
    cacheCreation: number;
    cacheRead: number;
  } | null;
  tools?: { name: string; count: number }[] | null; // NEW
}

export interface SessionKpis {
  totalSessions: number;
  medianCostUsd: number;
  p95CostUsd: number;
  medianCostPerActiveMinuteUsd: number;
  sessionsTrend: number[];        // NEW — per-bucket new-session counts
}
```

Add the fetcher the container calls on row-expand:

```ts
export const fetchSessionPrompts = (sessionId: string): Promise<SessionPromptRow[]> =>
  getJson(`/api/sessions/${encodeURIComponent(sessionId)}/prompts`);
```

## Files in this package

```
SessionsPage/
├── SessionsPage.tsx                    container — window wiring, queries (summary, table
│                                       page, on-demand prompt timeline), sort/pagination/
│                                       expansion state
├── SessionsPageView.tsx                view — KPI cards + hand-built sortable table
│                                       (Prompt column, clickable rows, custom pager)
└── components/
    └── PromptTimelinePanel/            component folder w/ index.ts barrel (mirrors the
        ├── index.ts                    codebase's per-component folder layout)
        └── PromptTimelinePanel.tsx     Aurora prompt-timeline panel (rail + per-turn cards:
                                        model chip · cost · token usage w/ breakdown tooltip ·
                                        tool chips · View-trace link; wraps long prompt text and
                                        dims out-of-window turns with boundary dividers).
                                        Exports TokenBreakdownTitle, TokenBreakdownTooltip, and
                                        TokenUsage (reused by the grid's Tokens cell); reads the
                                        canonical SessionPromptRow/SessionTokenBreakdown types
                                        from api/types.ts rather than declaring its own

Aurora Sessions Mockup.html             standalone design reference
```
