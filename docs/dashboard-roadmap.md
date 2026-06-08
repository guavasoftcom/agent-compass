# Dashboard roadmap

Expand the agent-tuning telemetry dashboard with aggregations that map directly to
actionable AGENTS.md / skills / `settings.json` changes. Every view follows the
existing Tool Calls pattern: a thin native-SQL aggregation in a repository, a
service method returning a typed DTO, a controller endpoint, and a
`PageLayout`-based React page reusing the shared `StatCard` / `Grid` / `PieChart` /
ranked-list components.

## North-star metrics

The dashboards exist to drive AGENTS.md tuning. Whether tuning is working is
measured by three trends over a window of weeks:

- **Tool failure rate trending down** — agents are using tools correctly.
- **Cache-read ratio trending up** — context placement is efficient.
- **Median cost per session trending flat or down** — efficiency gains aren't being
  eaten by scope creep.

Every Phase 1 dashboard should be readable as either an absolute number ("how am
I doing right now?") and a trend ("is my last AGENTS.md edit moving the
needle?"). A small "compare to previous window" affordance is captured as a
Phase 2 candidate (`F-compare`) so the feedback loop closes once Phase 1 lands.

## Phase 0 — Prerequisites (do these first)

These don't ship a feature on their own, but every Phase 1 dashboard is slower
or speculative without them.

### P0.1 — GIN indexes on jsonb attribute columns

Every Phase 1 query filters on `attributes ->> 'key' = value`. Without GIN
indexes those are seq scans. Ship a migration **before** the first dashboard:

```sql
-- V2__attribute_indexes.sql
CREATE INDEX idx_log_records_attributes_gin
  ON log_records USING gin (attributes jsonb_path_ops);
CREATE INDEX idx_metric_points_attributes_gin
  ON metric_points USING gin (attributes jsonb_path_ops);
```

Optional second pass: targeted btree indexes on the specific paths we filter on
(`attributes ->> 'event.name'`, `attributes ->> 'tool_name'`, `attributes ->>
'type'`, `attributes ->> 'session.id'`) once query plans show the GIN isn't
enough.

### P0.2 — Data-discovery sanity check per feature

Several Phase 1 features (#3 skills/subagents, #5 sessions) reference attribute
keys that are educated guesses, not confirmed shapes. Before each chunk, run a
short discovery query against the live DB:

```sql
-- Example for skill usage:
SELECT DISTINCT jsonb_object_keys(attributes)
FROM log_records
WHERE attributes ->> 'tool_name' = 'Skill'
  AND timestamp >= NOW() - INTERVAL '7 days';
```

Confirm the expected key (`skill_name`, `subagent_type`, `session.id`, etc.)
actually exists with the expected shape. Adjust the SQL or `TuningProperties`
defaults to match.

### P0.3 — Shared scaffolding lift

The "lift once a third page uses it" rule from the original draft was deferred
too long: every Phase 1 page wants the `WINDOWS` selector and most want the
`StatCard` KPI tile. Lift both with the **first** Phase 1 PR, not after the
fact:

- Move `WINDOWS` → `frontend/src/constants.js` (or similar shared module).
- Move `StatCard` → `frontend/src/components/StatCard.jsx`.
- Update `ToolCallsPage` + `ReportPage` to import from the new locations.

## Phase 1 — Immediate (5 dashboards)

Each card below lists: data source (table + filter), the shape of the
aggregation, the service / controller / page footprint, and the tuning insight
it surfaces. Order is rough effort, smallest first.

### 1. Tool failure rate

| | |
|---|---|
| **Effort** | S — same SQL shape as Tool Calls, adds one `success` filter. |
| **Data source** | `log_records` where `attributes ->> 'event.name' = :eventName` |
| **Group by** | `attributes ->> 'tool_name'`, `attributes ->> 'success'` (string `'true'` / `'false'`) |
| **Aggregation** | `COUNT(*)` split by success/failure → derive `failure_rate = failures / total` |
| **Semantics** | Counts only tools that actually emitted `tool_result`. Tools denied at the hook layer never appear here — that needs F2. Plan label clearly as "Tool execution failure rate" so users don't misread it as total exposure. |
| **Service** | `LogQueryService.aggregateToolFailureRates(int hours): List<ToolFailureRate>` |
| **Controller** | `GET /api/metrics/tool-failure-rates?hours=24` |
| **Page** | New nav entry "Tool reliability" (`/tool-reliability`); KPI strip (`overall failure rate`, `most-failing tool`, etc.) + ranked list of tools by failure rate. |
| **Tuning insight** | Any tool with >20–30% failure rate is a prime target for an AGENTS.md sentence about how to use it (e.g. "Run `grep` before `Read` to narrow the path"). |

### 2. Cache read ratio

| | |
|---|---|
| **Effort** | S — one chart over an existing metric, group by an existing attribute. |
| **Data source** | `metric_points` where `metric_name = 'claude_code.token.usage'` |
| **Group by** | `attributes ->> 'type'` (values: `input`, `output`, `cacheCreation`, `cacheRead`); also bucket by `timestamp` into 5-minute or 1-hour intervals for the trend line. |
| **Aggregation** | `SUM(COALESCE(value_long, value_double, 0))` per `(type, bucket)`. Derive ratio `cacheRead / (cacheCreation + cacheRead)`. |
| **Semantics** | Ratio interpretation: `≥ 0.7` is healthy (most prompts reuse cache), `0.4–0.7` is mixed, `< 0.4` means we're paying full freight on every turn. Show both the current ratio and a sparkline so users see the trend. |
| **Service** | `MetricQueryService.aggregateTokenUsage(int hours): TokenUsageSummary` |
| **Controller** | `GET /api/metrics/token-usage?hours=24` |
| **Page** | New nav entry "Tokens & cache" (`/tokens`); KPI cards for total in/out/cacheCreate/cacheRead + the current ratio, a line chart over time, and a derived ratio gauge with the threshold bands above. |
| **Tuning insight** | A low ratio means the cache isn't being reused — context placement (AGENTS.md location/length, skill imports) is wasting per-turn spend. |

### 3. Skill & subagent usage

| | |
|---|---|
| **Effort** | S after data discovery (P0.2) — Tool Calls aggregation filtered to two tool names. |
| **Data source** | `log_records` where `attributes ->> 'event.name' = 'tool_result'` and `attributes ->> 'tool_name' IN ('Skill', 'Task')` |
| **Group by** | For Skill: `attributes ->> 'skill_name'` *(speculative — confirm via P0.2)*. For Task: `attributes ->> 'subagent_type'` *(speculative — confirm via P0.2; may live under a different key)*. Two queries, two views. |
| **Aggregation** | `COUNT(*)` per group. |
| **Service** | `LogQueryService.aggregateSkillUsage(int hours): List<ToolCallCount>` and `.aggregateSubagentUsage(int hours): List<ToolCallCount>` |
| **Controller** | `GET /api/metrics/skill-usage?hours=24` and `GET /api/metrics/subagent-usage?hours=24` |
| **Page** | New nav entry "Skills & agents" (`/skills-agents`); two stacked sections, one for skills, one for subagents, each reusing the Tool Calls layout. |
| **Tuning insight** | Skills that never trigger have descriptions that don't match what the agent looks for. Subagents that get called for trivial work belong as inline tool sequences. |

### 4. Same-tool repeats per session

| | |
|---|---|
| **Effort** | M — window function plus a secondary group key to keep results actionable. |
| **Data source** | `log_records` where `attributes ->> 'event.name' = 'tool_result'` |
| **Group by** | `attributes ->> 'session.id'`, `attributes ->> 'tool_name'`, **and a scoping attribute** (`attributes ->> 'file_path'` for Edit/Write, `attributes ->> 'command'` for Bash — falling back to "no scope"). Then derive consecutive-run lengths using `LAG()` partitioned by session+scope and ordered by `timestamp`. |
| **Aggregation** | Longest consecutive run per `(session, tool, scope)`. Roll up: median and max run length per `(tool, scope)`. |
| **Why scoping matters** | Without it, "8 Edit calls in a row" might be legitimate edits to 8 different files. With `file_path` scoping, "8 Edits to the same file in a row" is a real signal of hunting. |
| **Service** | `LogQueryService.toolRepeatStats(int hours): List<ToolRepeatStat>` |
| **Controller** | `GET /api/metrics/tool-repeats?hours=24` |
| **Page** | Add a section to the Tool Calls page (no new nav entry) — a small table showing `tool / scope / median run / max run`. Sort desc by max run. |
| **Tuning insight** | Long Edit-Edit-Edit chains on the *same file* = the agent is hunting. AGENTS.md can encourage "read the file once, plan all changes, then write in a single pass." |

### 5. Cost & duration per session

| | |
|---|---|
| **Effort** | **M** (was S — corrected). Multiple metrics joined by session + an open question on `active_time.total` semantics + a DataGrid page rather than a ranked list. |
| **Data source** | `metric_points` where `metric_name IN ('claude_code.cost.usage', 'claude_code.active_time.total')` |
| **Group by** | `attributes ->> 'session.id'` (skip rows where this is null — out of scope for session view). |
| **Aggregation** | `SUM(value_double)` per session per metric, joined via CTE or in-memory. Augment with `MIN/MAX(timestamp)` for session start/end. |
| **Open question on `active_time.total`** | Sampled value `93.651` (seconds) was emitted as a single data point per session — but it might be cumulative (re-emitted with growing totals) or per-emission (emitted as deltas). Confirm via P0.2 before deciding whether to `SUM` or `MAX` it. If cumulative, use `MAX(value_double) PER session` instead of SUM. |
| **Service** | `MetricQueryService.sessionsSummary(int hours): List<SessionSummary>` |
| **Controller** | `GET /api/metrics/sessions?hours=24` |
| **Page** | New nav entry "Sessions" (`/sessions`); KPI strip (`total sessions`, `median cost/session`, `p95 cost/session`, `median duration`) + DataGrid of sessions sortable by cost / duration. `session.id` shown as a truncated monospace ID. |
| **Tuning insight** | The most expensive sessions are the most leveraged tuning targets. Inspecting their tool sequences + prompts informs prompt revisions and skill additions. |

## Phase 2 — Future potential features

Captured here for prioritization later. Each is shaped like the Phase 1 cards
but skipped for now either because the data isn't yet collected, the SQL is
heavier than a single GROUP BY, or the work requires more than a query change.

| # | Feature | Why not now | Notes |
|---|---|---|---|
| F1 | **Tool latency distribution (p50 / p95)** | **Already shipped** on the Tool Calls page as `ToolLatencyCard` (stacked p50 + p95-gap bars). Leave this row as a pointer; revisit if we want a distribution view beyond the per-tool bar (e.g., histogram or boxplot). | Source: `log_records.attributes ->> 'duration_ms'` on `tool_result`. Surfaces slow tools that the agent serial-waits on. |
| F2 | **Permission denials / hook rejections** | Need to first confirm Claude Code emits a stable `hook_execution_complete` shape with a `denied`/`approved` outcome. | Source: `log_records` where `event.name LIKE 'hook_execution_%'`. Feeds straight into `.claude/settings.json` allowlist suggestions. Pairs with #1 to give a complete failure picture. |
| F3 | **AskUserQuestion frequency** | Low priority — useful but rarely the highest-leverage signal. | Source: `log_records` where `tool_name = 'AskUserQuestion'`. High count = agent asking when it could decide; zero on ambiguous tasks = guessing. |
| F4 | **Edit accept/reject pattern + revert-within-N** | **Not just SQL.** Detecting a "revert" requires comparing `old_string` / `new_string` content between two Edit calls — either string equality or inverse. That's an application-layer scan (probably a Spring scheduled job or an on-demand service method that walks recent edits), not a single query. | Source: pair `code_edit_tool.decision` rows with subsequent inverse Edit calls within e.g. 10 min, then compare the diff. Closest proxy for "the agent's instinct was wrong." |
| F5 | **First-attempt task success** | Needs an explicit "task complete" event from the user (slash command, or a quiet-for-N-min heuristic). Requires emitter changes or a new convention. | Without it, can only approximate via session length until silence. |
| F6 | **Build / test correlation** | **Adds a new data model**, not just OTLP integration. A "build event" is its own DTO + table (id, branch, sha, status, started_at, finished_at, diff_context). Plus the integration to populate it from CI. | When the agent edits a file and CI fails within N minutes, link them. High value for "always run tests before declaring done" tuning. |
| F7 | **File hotspots & blast radius** | Some events already carry `file_path` (`code_edit_tool.decision`), others don't (`Bash`). Coverage gaps would skew the heatmap. Cleanup work omitted from original estimate. | Per-file edit heatmap. Useful for spotting "the agent keeps editing the wrong layer." Pairs with #4 (same-tool repeats scoped by file). |
| F8 | **Slash-command invocation distribution** | Confirm whether Claude Code emits `event.name = slash_command_used` or similar. | If yes, simple Tool Calls–style aggregation. If no, requires emitter change. |
| F9 | **Plan-mode usage and outcome** | Needs a `plan_mode_entered` / `plan_mode_exited` signal. May already exist; needs confirmation. | Compare cost / duration / edit-accept-rate between plan-mode and direct sessions. Quantifies whether plan mode pays off for your workflows. |
| F10 | **Conversation signals (prompt length distribution, correction frequency)** | Prompt text and turn structure aren't currently captured in our OTLP ingest. Adding them implies new instrumentation in the emitter or scraping `user_prompt` log bodies. | Distribution of `user_prompt.body` lengths, frequency of "actually", "no, instead", etc. patterns. Hard to make precise without explicit signals. |
| F-compare | **Compare-to-previous-window mode** | Closes the feedback loop on AGENTS.md edits: "did last week's tuning move the metric?" Worth doing once at least two Phase 1 dashboards are in place. | Add an optional `?since=…&compare=previous` to each aggregation endpoint, render a delta % next to each KPI tile. |
| F11 | **Tool co-occurrence / sequence** | Heatmap or small Sankey of "tool A → tool B" transitions ordered by `timestamp` within a session. Requires `LAG()` over `(session.id, timestamp)` and a pair count — not a single GROUP BY. Volume per pair may be sparse on short windows; needs a `min count` filter to stay readable. | Source: `log_records` where `event.name = 'tool_result'`, grouped by `(session.id)` and ordered by `timestamp`. Reveals canonical workflows ("read → grep → edit") and weird patterns ("ls × 12 in a row"). Pairs naturally with #4 (same-tool repeats). |
| F12 | **Tool argument / response size distribution** | Bytes in/out per tool — catches tools returning megabytes that bloat context. Currently we don't reliably capture argument/response size on every `tool_result` event; needs P0.2-style discovery to confirm which attribute carries it (`request_bytes` / `response_bytes` / a body length) before SQL can be written. May require emitter changes if the data isn't there. | Source candidate: `log_records.attributes ->> 'response_bytes'` (or similar) on `tool_result`. Pairs with #2 cache-read ratio — bloated tool responses are a top driver of cache-creation cost. |
| F13 | **Time-to-first-tool-call per session** | Cheap latency proxy for reasoning overhead — how long the agent thinks before acting. Requires a session-start signal (first event tagged with `session.id`) and the `MIN(timestamp)` of the first `tool_result` in that session, then a histogram or median. Depends on the same `session.id` plumbing as #5. | Source: `log_records` grouped by `session.id`, taking `MIN(timestamp) FILTER (WHERE event.name = 'tool_result') − MIN(timestamp)` per session. Long times = agent over-deliberating; near-zero on every session = jumping in without a plan. |

## Cross-cutting concerns

### Performance & caching

- TanStack Query already configures `staleTime: 30_000ms` on the frontend, so
  the same page hit within 30 seconds won't refetch.
- Backend re-runs the SQL on every API call. For Phase 1, with the GIN indexes
  from P0.1, this is fine. If any specific query exceeds ~200ms locally, add
  Spring `@Cacheable` (Caffeine) on the service method keyed by
  `(hours, time-bucket)`. Don't preemptively cache — measure first.
- The "compare to previous window" feature (F-compare) doubles the read load on
  each page. If it lands, that's the trigger to introduce caching even on
  fast-by-themselves queries.

### Navigation scalability

Phase 1 adds 4 nav entries (Tool reliability, Tokens & cache, Skills & agents,
Sessions) on top of the existing 5. Past ~7 items the flat sidebar starts to
read as a list rather than a hierarchy. Two options once the count gets there:

- Group under collapsible sections (e.g., **Activity** = Tool Calls + Tool
  reliability + Skills & agents; **Resources** = Tokens & cache + Sessions;
  **Raw data** = Metrics + Logs + Traces; **Output** = Report).
- Or keep flat but visually group with subtle dividers and section labels.

Defer the decision until at least 3 Phase 1 dashboards are live so the actual
layout pressure is visible.

### Empty / error / loading states

Every new page reuses `PageLayout`'s built-in error slot (`<Alert>` rendered
from the `error` prop) and the existing pattern for empty states (a centered
`<Typography color="text.secondary">No data in this window.</Typography>`
inside the chart/grid area). No new conventions to invent.

### Testing convention

Every new endpoint ships with:

1. A `@WebMvcTest` dispatch test added to `DashboardControllerTest` — mock the
   service, verify the URL routes to the right method with the right args, and
   assert the response shape.
2. A repository-level smoke test using the existing Testcontainers integration
   test setup — ingest a handful of representative rows, call the aggregation,
   and assert the grouped counts.

Established in the existing `DashboardControllerTest` + `OtlpIngestIntegrationTest`
pattern. Don't ship a new dashboard endpoint without both.

### OpenAPI

Every new endpoint annotates with `@Operation` + `@ApiResponses` + per-param
`@Parameter` so springdoc continues to auto-derive the spec. Same convention as
the existing controllers — no extra setup.

## Recommended ordering

Updated to account for prerequisites and the corrected #5 sizing:

1. **P0 — Prerequisites**. Ship the GIN index migration (P0.1), do the data
   discovery for skill/subagent/session attributes (P0.2), and lift `WINDOWS`
   + `StatCard` into shared modules (P0.3). Single PR.
2. **Tool failure rate (#1)** — smallest delta from existing Tool Calls page;
   warms us up.
3. **Cache read ratio (#2)** — pure metric aggregation, introduces the
   bucketed-time-series pattern.
4. **Skill & subagent usage (#3)** — two more flavors of the same group-by
   pattern as #1; quick win once P0.2 confirms attribute names.
5. **Cost & duration per session (#5)** — introduces the `SessionSummary` shape
   and the session-as-entity concept that #4 also benefits from.
6. **Same-tool repeats per session (#4)** — only one with a window-function
   query; tackle last when the patterns are well-grooved and the `session.id`
   handling is settled.

Each chunk ships as its own PR-sized unit: backend repo query + service method
+ controller endpoint + dispatch & smoke tests; then frontend api helper + page
+ nav entry. Each chunk should be roughly the size of the Tool Calls
migration (~150 lines per dashboard, mostly boilerplate matching existing
components).
