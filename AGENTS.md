# AGENTS.md

Guidance for coding agents working in this repo. See [README.md](README.md) for end-to-end product context.

## What this project is

OTLP/HTTP telemetry sink → Postgres (`jsonb`) → markdown tuning report + React/MUI dashboard. Agents push their own telemetry here (e.g. `claude_code.code_edit_tool.decision`); the report and dashboard surface tool-usage patterns the agent can use to revise its own prompts/skills.

## Repository layout

- `backend/` — Spring Boot 4.1 on Java 21. Package root: `com.guavasoft.agentcompass`. See [backend/CLAUDE.md](backend/CLAUDE.md) for naming, web-layer, and data conventions.
  - `otlp/` — the OTLP ingest slice (own `controller` / `mapper` / `service` sub-packages); controllers expose `POST /v1/logs`, `/v1/metrics`, `/v1/traces`.
  - `entity/`, `repository/` — JPA + `jsonb` storage in `log_records`, `metric_points`, `spans`.
  - `service/`, `controller/` — aggregation, dashboard JSON, markdown report. Per-domain controllers (`LogsController`, `MetricsController`, `SessionController`, `ToolActivityController`, `TracesController`, `ReportController`) serve the React app under `/api`.
  - `model/` — record DTOs (Lombok `@Data @Builder` on older shapes); MapStruct mappers in `mapper/` (`spring` component model) handle entity → DTO.
  - `config/` — `TuningProperties` (overridable event/attribute/metric names), `OpenApiConfig`, etc.
- `frontend/` — React 19 + Vite 8 + MUI 9 (charts and tables are hand-built SVG/CSS — no `@mui/x-charts` / `@mui/x-data-grid` / `@mui/x-tree-view`), TanStack Query v5, React Router 7. Package manager is Yarn Berry, pinned by the `packageManager` field in `frontend/package.json` and resolved through Corepack — don't install Yarn globally, and a stray `package-lock.json` is legacy, so never `npm install`. See [frontend/CLAUDE.md](frontend/CLAUDE.md) for conventions; each `frontend/src/pages/<Name>Page/` folder also has its own `CLAUDE.md` covering that page's files, data flow, and gotchas.

## Run / build / test

Use the Maven wrapper and Yarn — do not invoke a system `mvn` or `npm`.

```sh
# Backend (port 8080). spring-boot-docker-compose auto-starts Postgres from backend/docker-compose.yml.
cd backend && ./mvnw spring-boot:run

# Backend tests (includes Testcontainers integration tests — Docker must be running).
cd backend && ./mvnw verify

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

`.github/workflows/pull-request.yml` runs all of the above on every pull request (`./mvnw verify`
on JDK 21, frontend lint/typecheck/test/build on Node 22). It runs `yarn test --run` rather than
`yarn test:coverage` — the suite doesn't meet the coverage thresholds yet.

## Conventions

- **Java 21**, `--release 21`. Don't lower the source level.
- **Lombok + MapStruct** are both on the annotation processor path; `lombok-mapstruct-binding` keeps them compatible. When adding a new mapper, use `@Mapper(componentModel = "spring")`.
- **Schema lives in Flyway migrations** (`backend/src/main/resources/db/migration/`); Hibernate runs with `ddl-auto=validate`. Every schema change is a new `V{n}__*.sql` migration.
- **Attribute payloads are `jsonb`** — use the existing converter pattern in `entity/` rather than flattening into columns.
- **OpenAPI** is auto-derived. Annotate new endpoints with `@Tag` / `@Operation` so Swagger UI stays useful.
- **Frontend data fetching** goes through TanStack Query; don't introduce a second data layer (Redux, SWR, etc.).
- **Charts and tables** are hand-built SVG/CSS (no `@mui/x-charts` / `@mui/x-data-grid`) — extend the existing bespoke components; don't add a visualization library.

## Configuration the agent should know

All dashboard aggregations are driven by event/attribute/metric names defined on
`TuningProperties` (`tuning.*` in `application.yml`). Defaults match Claude Code's emission
shape; override for other agents.

- `tuning.tool-event-name` (default `tool_result`) — `event.name` value marking a tool invocation.
- `tuning.tool-attribute` (default `tool_name`) — attribute key carrying the tool name.
- `tuning.tool-span-scope` / `tuning.tool-span-name` — OTLP instrumentation scope + span name for per-tool latency.
- `tuning.tool-execution-span-name` / `tuning.llm-request-span-name` plus `tuning.tool-call-id-attribute` / `tuning.request-id-attribute` — the leaf spans that time a single tool run or LLM request, and the attribute keys their logs share with them. Trace-detail log correlation re-points logs off the coarse interaction-root span onto the exact leaf. `tuning.api-request-event-name` / `api-request-body-event-name` / `prompt-id-attribute` / `event-sequence-attribute` recover the request id for the request-payload log, which carries only a turn-level prompt id.
- `tuning.user-prompt-event-name` (default `user_prompt`) and `tuning.prompt-attribute` (default `prompt`) — the once-per-turn log carrying the raw prompt text; drive the Sessions grid's prompt column and the per-session prompt timeline.
- `tuning.token-usage-metric` (default `claude_code.token.usage`) and `tuning.token-type-attribute` (default `type`) — drive the Tokens & cache page.
- `tuning.skill-tool-name` / `tuning.skill-name-attribute` and `tuning.subagent-tool-name` / `tuning.subagent-type-attribute` — drive the Skills & agents page (the inner attribute is looked up flat first, then under `tool_input`).
- `tuning.cost-usage-metric` (default `claude_code.cost.usage`) and `tuning.active-time-metric` (default `claude_code.active_time.total`) — drive the Sessions page. Claude Code emits these (and token usage) as cumulative counters split per stream (full attribute set); ingest precomputes reset-aware per-row increments into `metric_points.value_delta`, so all rollups are plain `SUM(value_delta)` — see the data conventions in [backend/CLAUDE.md](backend/CLAUDE.md).
- `tuning.bash-antipattern-replacements` (Bash command prefix → dedicated-tool replacement named in the tuning report's suggestions) and `tuning.externally-determined-tools` (tools whose latency/result size the agent can't tune — subagents, web fetches — excluded from the report's oversized and slow-and-large offender lists).
- Derived log severity (`tuning.error-event-names` / `warn-event-names` / `debug-event-names` and the signal attribute keys) — documents the classification rules baked into the `derive_log_severity()` SQL function; the function holds the literals, so changing these lists means a new Flyway migration that redefines it.

To bypass the bundled compose Postgres, set `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` — see [backend/.env.example](backend/.env.example).

## Pointing an OTel-instrumented agent at this backend

```sh
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:8080
export OTEL_METRIC_EXPORT_INTERVAL=10000   # optional, speeds up debugging
```

## Things to avoid

- Don't add vendor-specific telemetry backends (SigNoz, Aspire, Datadog SDK, etc.) — the whole point is staying on plain OTLP.
- Don't introduce a separate OpenAPI spec file; the springdoc auto-derivation is authoritative.
- Don't commit `.env` (only `.env.example`).
