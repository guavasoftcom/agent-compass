# AGENTS.md

Guidance for coding agents working in this repo. See [README.md](README.md) for end-to-end product
context: layout, endpoints, prerequisites, and how to point an OTel-instrumented agent (including
Claude Code) at the backend.

## What this project is

OTLP/HTTP telemetry sink → Postgres (`jsonb`) → markdown tuning report + React/MUI dashboard. Agents
push their own telemetry here (e.g. `claude_code.code_edit_tool.decision`); the report and dashboard
surface tool-usage patterns the agent can use to revise its own prompts/skills.

## Repository layout

- `backend/` — Spring Boot 4.1 on Java 21, package root `com.guavasoft.agentcompass`. Conventions,
  module layout, and the data-model gotchas that bite hardest: [backend/CLAUDE.md](backend/CLAUDE.md).
- `frontend/` — React 19 + Vite 8 + MUI 9, TanStack Query v5, React Router 7.
  Conventions: [frontend/CLAUDE.md](frontend/CLAUDE.md). Each `frontend/src/pages/<Name>Page/` also
  has its own `CLAUDE.md` covering that page's files, data flow, and gotchas.
- Root `Dockerfile` / `docker-compose.yml` — the released image and the stack that runs it locally.
  The Dockerfile builds nothing: it copies in a prebuilt jar and Vite bundle. See
  [docs/local-docker-deployment.md](docs/local-docker-deployment.md).

## Run / build / test

Use the Maven wrapper and Yarn — never a system `mvn`, and never `npm install` (Yarn Berry is pinned
by `packageManager` and resolved through Corepack; the stray `package-lock.json` is legacy).

```sh
# Backend (port 8080). spring-boot-docker-compose auto-starts Postgres from backend/docker-compose.yml.
cd backend && ./mvnw spring-boot:run

# Backend tests (includes Testcontainers integration tests — Docker must be running).
cd backend && ./mvnw verify

# Executable jar (spring-boot:repackage is bound to package). `clean` matters: a
# stale jar from an earlier version leaves two in target/, which the release
# workflow's single-jar resolver rejects.
cd backend && ./mvnw clean package -DskipTests

# Frontend dev (port 5173, /api and /v1 proxied to :8080).
cd frontend && yarn install && yarn dev

# Frontend production build / typecheck / lint.
cd frontend && yarn build
cd frontend && yarn typecheck
cd frontend && yarn lint

# Frontend tests (Vitest; bare `yarn test` is watch mode).
cd frontend && yarn test --run
cd frontend && yarn test:coverage   # enforces the 80% thresholds in vite.config.js
```

CI runs all of the above on every pull request, and releases are cut by manual dispatch from `main`
with a semver bump — see `.github/workflows/pull-request.yml` and `.github/workflows/release.yml`.
Two things those files won't tell you: CI runs `yarn test --run` rather than `yarn test:coverage`
because the suite doesn't meet the thresholds yet, and the release workflow builds the frontend and
jar itself, so the SPA is never copied into `backend/src/main/resources` — build output stays out of
the backend source tree.

## Conventions

- **Java 21**, `--release 21`. Don't lower the source level.
- **Lombok + MapStruct** are both on the annotation processor path; `lombok-mapstruct-binding` keeps
  them compatible.
- **Schema lives in Flyway migrations** (`backend/src/main/resources/db/migration/`); Hibernate runs
  with `ddl-auto=validate`. Every schema change is a new `V{n}__*.sql` migration.
- **OpenAPI** is auto-derived. Annotate new endpoints with `@Tag` / `@Operation` so Swagger UI stays
  useful.
- **Charts and tables are hand-built SVG/CSS** — no `@mui/x-charts` / `@mui/x-data-grid` /
  `@mui/x-tree-view`. Extend the existing bespoke components; don't add a visualization library.

## Configuration the agent should know

Every dashboard aggregation is driven by event/attribute/metric names on `TuningProperties`
(`tuning.*` in `application.yml`); defaults match Claude Code's emission shape, and the javadoc on
[TuningProperties.java](backend/src/main/java/com/guavasoft/agentcompass/config/TuningProperties.java)
is the authoritative per-property reference.

The authoritative, machine-readable list of which properties are mirrored is
[TuningPropertyCatalog.java](backend/src/main/java/com/guavasoft/agentcompass/config/TuningPropertyCatalog.java),
surfaced at runtime by `GET /api/system/configuration` and on the dashboard's Settings page. It
classifies all 52 properties three ways and a reflection test fails the build if a newly added
property is left unclassified. Prefer it over the prose below, which names the seven worst offenders
but is not exhaustive: the real count is **17 across six migrations**, and it includes three
(`tool-decision-event-name`, `api-request-body-event-name`, `hook-execution-event-name`) whose
defaults `V6` hardcodes for its own reasons — overriding those does not invalidate the SQL, but the
property and the function then describe different events.

Several of those properties are **mirrored as literals in Flyway SQL** — the `span_costs` /
`trace_costs` views (`V14`), the `LEFT JOIN LATERAL` predicates in `SpanRepository` that re-run those
views' filter against `log_records` for pushdown, the `derive_log_severity()` function (`V6`), and the
`span_efforts` view plus its partial index (`V15`). Overriding `api-request-cost-attribute`,
`api-request-event-name`, `api-request-effort-attribute`, `request-id-attribute`, `llm-request-span-name`,
or the severity lists therefore means a new migration redefining the views/function *and* updating the
lateral predicates, or the pages read from the wrong rows. The Logs-page facet/histogram/cursor/offset
queries add two more: `log_records.event_name` (`V16`, generated from the `event.name` literal every
one of those queries already hardcoded) and `log_records.tool_name` (`V17`, generated from
`tool-attribute`'s default of `"tool_name"`) are `STORED` generated columns added purely to avoid
detoasting `attributes` per row — same fix V8 already applied to `derived_severity`, same reasoning
in the section comment atop `LogRecordRepository`'s Logs-page query block. **Always filter on
`event_name`, never on `attributes ->> 'event.name'`:** V16 also *dropped* the old expression index
and rebuilt `idx_log_records_event_name_ts` on the column, so the raw extraction now has no index at
all and falls back to a timestamp-only scan that detoasts every row in the window to evaluate the
filter. V16/V17 migrated only the Logs-page queries and left that trap behind everything else — it
cost the tuning report ~740 ms on each of its 12 statements (a 9-second report). `V19` finishes the
job across `LogRecordRepository`, `SpanRepository`, the `span_costs` / `trace_costs` / `span_efforts`
views, and the `idx_log_records_request_id` predicate. That last one is why a *migration* was needed
and not just a find-and-replace: the index is **partial** on the event name, and Postgres's
predicate-implication prover matches expressions structurally, so it cannot tell that `event_name`
and the expression it is generated from are the same value — rewriting a query to the column while
the predicate still named the expression silently dropped the index. **Any new partial index keyed on
an event name must write its predicate against `event_name`.** Overriding `tool-attribute`
away from `"tool_name"` now means a migration that drops and re-adds `tool_name` against the new key,
or the Logs page's tool facet/filter/histogram silently read the wrong attribute. Token/cost/active-time
counters are cumulative per stream and ingest precomputes reset-aware increments into
`metric_points.value_delta`, so every rollup is a plain `SUM(value_delta)` — details in
[backend/CLAUDE.md](backend/CLAUDE.md).

Context-window footprint is aggregated **twice, on purpose**: `LogRecordRepository`'s
`aggregateToolContextFootprintInRange` feeds the dashboard card and counts every tool, while
`aggregateTunableToolContextFootprintInRange` feeds the markdown report's "Context footprint"
section and drops externally determined tools (`Agent`, `WebSearch`, `WebFetch`) and image reads —
the same exclusions the oversized-result list uses, because the report's header tells readers not
to write rules against those. Neither query is the other's filtered view; changing one is not
automatically a reason to change the other, and their totals are supposed to differ.

MCP tool calls are named differently on the two signals that carry them, which is why
`TuningProperties` carries five `mcp-*` properties instead of reusing `tool-attribute`: on
`log_records` every MCP server's calls share the single constant `mcp_tool` (`tool-name`), with
real server/tool identity in the `tool_parameters` JSON-string attribute (`mcp-parameters-attribute`,
`mcp-server-name-attribute`, `mcp-tool-name-attribute`); on `spans` the raw name is the prefixed
form `mcp__<server>__<tool>` (`mcp-span-tool-prefix`), parsed with `starts_with()`/`split_part()`
rather than `LIKE` (a bare `_` is the `LIKE` single-character wildcard). All five stay
`NOT_MIRRORED` — no generated column is needed since every MCP query is already gated by the
indexed `tool_name` column. `/api/tool-activity/mcp-usage` and the report's "MCP servers" section
read the log side (execution-only `duration_ms`, not span duration, which includes time blocked on
user approval); every other log-backed tool aggregation now splits the collapsed `mcp_tool` bucket
back out to `mcp:<server>` rows the same way.

Spend is measurable two ways — those cumulative counters, and the exact per-call figures on
`api_request` log records — and **the two do not reconcile**: on real data they disagree by tens
of percent in both directions, dominated by cache-read tokens. Every figure names its source
rather than blending them (see `SessionPrompt.attribution`). Read the two-pipelines note in
[backend/CLAUDE.md](backend/CLAUDE.md) before adding any new token or cost aggregation.

To bypass the bundled compose Postgres, set `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` —
see [backend/.env.example](backend/.env.example).

## Things to avoid

- Don't add vendor-specific telemetry backends (SigNoz, Aspire, Datadog SDK, etc.) — the whole point
  is staying on plain OTLP.
- Don't introduce a separate OpenAPI spec file; the springdoc auto-derivation is authoritative.
- Don't commit `.env` (only `.env.example`).
