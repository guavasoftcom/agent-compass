# Agent Compass

OTLP/HTTP telemetry sink + Postgres store + markdown tuning report + React/MUI dashboard for coding-agent self-tuning.

End-to-end flow:

1. Coding agents (Claude Code, GitHub Copilot, etc.) push **OTLP/HTTP protobuf** directly to this backend.
2. Backend persists every log record, metric data point, and span to Postgres (`jsonb` for attribute maps).
3. Backend aggregates the stored telemetry and exposes:
   - A **markdown report** (`GET /api/report`) for paste-into-agent self-tuning.
   - **JSON endpoints** that power a React + Material UI dashboard.
4. The user reviews the dashboard, copies the markdown report, and pastes it into their coding agent so it can revise its own `AGENTS.md` / skills / prompts.

## Repository layout

- `backend/` — Spring Boot 3.5 (Java 21), Spring Data JPA, `opentelemetry-proto`, Postgres.
- `frontend/` — React + Vite + Material UI (`@mui/x-data-grid`, `@mui/x-charts`), TanStack Query, React Router.

## Prerequisites

- JDK 21+ (the backend compiles with `--release 21`)
- No Maven install required — use the bundled wrapper (`./mvnw` / `./mvnw.cmd`), pinned to Maven 3.9.9
- Node 20+ and Yarn (Berry — Yarn 4)
- Docker (for the Postgres compose service and for running the Testcontainers integration test)

## Run

The backend has `spring-boot-docker-compose` on the classpath, so `./mvnw spring-boot:run` will automatically start the Postgres service declared in [backend/docker-compose.yml](backend/docker-compose.yml), wire its `JdbcConnectionDetails` into the app via `@ServiceConnection`, and stop the container when the app shuts down.

```sh
# Terminal 1 — backend (port 8080). Postgres comes along for the ride.
cd backend
./mvnw spring-boot:run

# Terminal 2 — frontend dev server (port 5173, proxies /api → :8080)
cd frontend
yarn install
yarn dev
```

Open <http://localhost:5173> for the dashboard, or <http://localhost:8080/swagger-ui.html> for the OpenAPI / Swagger UI.

Flyway creates and migrates the schema on startup (`backend/src/main/resources/db/migration/`); Hibernate runs with `ddl-auto=validate` and fails fast if the entity model and DB drift.

If you'd rather use an existing Postgres (no Docker), set `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` env vars before starting the backend — see [backend/.env.example](backend/.env.example). The compose support backs off when explicit datasource properties are present.

## Pointing your coding agent at the backend

For any OTel-instrumented agent, set the standard OTLP env vars to send protobuf-over-HTTP to this backend:

```sh
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:8080
# Optional: speed up debugging
export OTEL_METRIC_EXPORT_INTERVAL=10000
```

Claude Code emits its tool-decision counter as `claude_code.code_edit_tool.decision` with a `tool` attribute. The defaults in `application.yml` and the report endpoint assume this. If your agent emits a different metric name or attribute key, override via:

```properties
tuning.tool-decision-metric=your.tool.metric.name
tuning.tool-attribute=tool_name
```

## Endpoints

### OTLP ingest

- `POST /v1/logs`, `POST /v1/metrics`, `POST /v1/traces` — accept `application/x-protobuf` OTLP export requests and persist log records, metric data points, and spans into `log_records` / `metric_points` / `spans`.

### Dashboard JSON

All under `/api`, consumed by the React dashboard. Most accept a time window as either `?minutes=` or `?startTimestamp=&endTimestamp=` — see the Swagger UI for full parameter lists.

- `GET /api/logs` — log records, cursor-paged (`before`/`after`, for the Stream / live-tail view) or offset-paged (`page`/`size`, for the Table view); plus `/api/logs/histogram` (severity histogram), `/api/logs/facets` (filter-rail counts), and `/api/logs/attributes` / `/attribute-keys` / `/attribute-values` autocomplete.
- `GET /api/metrics` — metric explorer, with `/series`, `/catalog`, `/cost`, `/distribution`, and `/attributes`.
- `GET /api/tool-activity/...` — `calls`, `calls/timeseries`, `calls/latency`, `failure-rates`, `denials`, `repeats`, `skill-usage`, `subagent-usage`, `hook-executions`.
- `GET /api/traces` — trace list; `/api/traces/{traceId}` span detail and `/api/traces/{traceId}/logs` cross-signal log linkage.
- `GET /api/sessions` — session list, with `/summary` and `/token-usage`.

### Report

- `GET /api/report?minutes=1440` → `text/markdown`. Paste straight into a coding-agent chat.

### OpenAPI / Swagger

- `GET /swagger-ui.html` — interactive Swagger UI.
- `GET /v3/api-docs` — raw OpenAPI 3 JSON.

## Frontend pages

- **Tool Usage** — tabbed section: call mix and latency, reliability (failure rates, denials, repeats), skills & subagents.
- **Token Usage** — token composition, per-model breakdown, and cost.
- **Sessions** — session list with summary KPIs and per-session token usage.
- **Logs** — structured-event explorer: severity histogram with bar-click zoom, faceted filtering, full-text search, and a live-tailable Stream or paged Table body.
- **Metrics** — metric catalog and series explorer over raw `metric_points`.
- **Traces** — trace list with per-trace span detail and inline logs.
- **Report** — renders the report as monospace text with a one-click "Copy markdown" button.

## Tests

The backend ships with Testcontainers-backed integration tests that spin up a real Postgres, push hand-built OTLP export requests to the `/v1/*` ingest endpoints, and assert the `/api/*` aggregations. Requires Docker running.

```sh
cd backend
./mvnw verify
```

## Why this stack

- OTLP/HTTP directly into Spring Boot keeps the system **vendor-neutral** — no SigNoz, no Aspire, no proprietary query API to lock against. OTLP is the CNCF standard.
- Postgres + `jsonb` fits the telemetry shape: stable numeric spine, open-ended attribute bag, SQL aggregations for report generation.
- Material UI (`@mui/x-data-grid` + `@mui/x-charts`) covers both grid and chart needs without pulling in a second visualization library.
- Lombok keeps the entity/DTO boilerplate down; MapStruct (`spring` component model) generates `MetricPoint` → `EventRowDto` translation; `lombok-mapstruct-binding` keeps the two annotation processors happy together.
- springdoc-openapi auto-derives the OpenAPI schema from the controllers — `@Tag`/`@Operation` annotations on each handler shape the Swagger UI without a separate spec file.
- Testcontainers gives the integration test a real Postgres (with `jsonb`) so the test exercises the same JPA + native SQL aggregation path that production uses; `@ServiceConnection` wires the container into Spring Boot's datasource automatically.
