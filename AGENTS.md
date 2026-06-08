# AGENTS.md

Guidance for coding agents working in this repo. See [README.md](README.md) for end-to-end product context.

## What this project is

OTLP/HTTP telemetry sink → Postgres (`jsonb`) → markdown tuning report + React/MUI dashboard. Agents push their own telemetry here (e.g. `claude_code.code_edit_tool.decision`); the report and dashboard surface tool-usage patterns the agent can use to revise its own prompts/skills.

## Repository layout

- `backend/` — Spring Boot 3.5 on Java 21. Package root: `com.guavasoft.telemetry`. See [backend/CLAUDE.md](backend/CLAUDE.md) for naming, web-layer, and data conventions.
  - `ingest/` — OTLP protobuf mappers for all three signals; controllers expose `POST /v1/logs`, `/v1/metrics`, `/v1/traces`.
  - `entity/`, `repository/` — JPA + `jsonb` storage in `log_records`, `metric_points`, `spans`.
  - `service/`, `controller/` — aggregation, dashboard JSON, markdown report. `DashboardController` is the single REST surface for the React app.
  - `model/` — record DTOs (Lombok `@Data @Builder` on older shapes); MapStruct (`spring` component model) handles entity → DTO.
  - `config/` — `TuningProperties` (overridable event/attribute/metric names), `OpenApiConfig`, etc.
- `frontend/` — React 19 + Vite 5 + MUI 9 (`@mui/x-data-grid` / `@mui/x-charts` / `@mui/x-tree-view`), TanStack Query v5, React Router 6. Package manager is npm (a legacy `yarn.lock` exists but scripts and CI use npm).

## Run / build / test

Use the Maven wrapper and npm — do not invoke a system `mvn` or `yarn`.

```sh
# Backend (port 8080). spring-boot-docker-compose auto-starts Postgres from backend/docker-compose.yml.
cd backend && ./mvnw spring-boot:run

# Backend tests (includes a Testcontainers integration test — Docker must be running).
cd backend && ./mvnw verify

# Frontend dev (port 5173, /api and /v1 proxied to :8080).
cd frontend && npm install && npm run dev

# Frontend production build / typecheck / lint.
cd frontend && npm run build
cd frontend && npm run typecheck
cd frontend && npm run lint
```

## Conventions

- **Java 21**, `--release 21`. Don't lower the source level.
- **Lombok + MapStruct** are both on the annotation processor path; `lombok-mapstruct-binding` keeps them compatible. When adding a new mapper, use `@Mapper(componentModel = "spring")`.
- **Schema is Hibernate-managed** (`ddl-auto=update`). Don't add Flyway/Liquibase without discussion.
- **Attribute payloads are `jsonb`** — use the existing converter pattern in `entity/` rather than flattening into columns.
- **OpenAPI** is auto-derived. Annotate new endpoints with `@Tag` / `@Operation` so Swagger UI stays useful.
- **Frontend data fetching** goes through TanStack Query; don't introduce a second data layer (Redux, SWR, etc.).
- **Charts and grids** stay on `@mui/x-charts` / `@mui/x-data-grid` — don't add a second visualization library.

## Configuration the agent should know

All dashboard aggregations are driven by event/attribute/metric names defined on
`TuningProperties` (`tuning.*` in `application.yml`). Defaults match Claude Code's emission
shape; override for other agents.

- `tuning.tool-event-name` (default `tool_result`) — `event.name` value marking a tool invocation.
- `tuning.tool-attribute` (default `tool_name`) — attribute key carrying the tool name.
- `tuning.tool-span-scope` / `tuning.tool-span-name` — OTLP instrumentation scope + span name for per-tool latency.
- `tuning.token-usage-metric` (default `claude_code.token.usage`) and `tuning.token-type-attribute` (default `type`) — drive the Tokens & cache page.
- `tuning.skill-tool-name` / `tuning.skill-name-attribute` and `tuning.subagent-tool-name` / `tuning.subagent-type-attribute` — drive the Skills & agents page (the inner attribute is looked up flat first, then under `tool_input`).
- `tuning.cost-usage-metric` (default `claude_code.cost.usage`) and `tuning.active-time-metric` (default `claude_code.active_time.total`) — drive the Sessions page; both are aggregated as `SUM over streams of MAX per (session, model, query_source)` because Claude Code emits them as cumulative gauges split by stream.

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
