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

- `backend/` — Spring Boot 4.1 (Java 21), Spring Data JPA, `opentelemetry-proto`, Postgres.
- `frontend/` — React + Vite + Material UI (charts and tables are hand-built SVG/CSS — no `@mui/x-charts` or `@mui/x-data-grid`), TanStack Query, React Router.

## Prerequisites

- JDK 21+ (the backend compiles with `--release 21`)
- No Maven install required — use the bundled wrapper (`./mvnw` / `./mvnw.cmd`), pinned to Maven 3.9.9
- Node 20+ (CI builds on 22) and Yarn Berry — the exact version is pinned by the `packageManager` field in [frontend/package.json](frontend/package.json), so `corepack enable` is enough; no global Yarn install needed
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

To run the released image instead of the dev servers — API and dashboard in one container, on one port — see [docs/local-docker-deployment.md](docs/local-docker-deployment.md) and the [docker-compose.yml](docker-compose.yml) beside it.

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

For Claude Code specifically, telemetry is off until you enable it, and metrics, logs, and traces each have their own exporter switch — [docs/local-docker-deployment.md](docs/local-docker-deployment.md#point-claude-code-at-it) has the full set (including the `~/.claude/settings.json` form), what each one feeds in the dashboard, and how to verify data is flowing.

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
- `GET /api/metrics` — raw metric points, offset-paged (`page`/`size`, size clamped to 500, `totalCount` in the body); plus `/series`, `/catalog`, `/cost`, `/distribution`, and `/attributes`.
- `GET /api/tool-activity/...` — `calls`, `calls/timeseries`, `calls/latency`, `failure-rates`, `denials`, `repeats`, `skill-usage`, `subagent-usage`, `hook-executions`. `skill-usage` and `subagent-usage` rows carry a `byModel` split of their call count.
- `GET /api/traces` — trace list, cursor-paged (`before`/`after`, for the Stream / live-tail view) or offset-paged (`page`/`size`, for the Table view); plus `/api/traces/histogram` (throughput + p95 overlay), `/api/traces/facets` (filter-rail counts), `/api/traces/{traceId}` span detail, `/api/traces/{traceId}/summary` (one trace's aggregate row, including the user prompt that initiated it), and `/api/traces/{traceId}/logs` cross-signal log linkage.
- `GET /api/sessions` — session list, with `/summary`, `/token-usage`, and `/{sessionId}/prompts` (per-session prompt timeline with per-turn model / cost / token / tool rollups).

### Report

- `GET /api/report?minutes=1440` → `text/markdown`. Paste straight into a coding-agent chat.

### OpenAPI / Swagger

- `GET /swagger-ui.html` — interactive Swagger UI.
- `GET /v3/api-docs` — raw OpenAPI 3 JSON.

## Frontend pages

- **Tool Usage** — tabbed section: call mix and latency, reliability (failure rates, denials, repeats), skills & subagents.
- **Token Usage** — token composition, per-model breakdown, and cost.
- **Sessions** — session list with summary KPIs, per-session token usage, cache efficiency, and a first-prompt preview per row; clicking a row opens a detail drawer with its per-turn prompt timeline (model, cost, token breakdown, tool calls).
- **Logs** — structured-event explorer: severity histogram with bar-click zoom, faceted filtering, full-text search, and a live-tailable Stream or paged Table body.
- **Metrics** — metric catalog and series explorer over raw `metric_points`.
- **Traces** — distributed-trace explorer: throughput histogram with p95 overlay and bar-click zoom, faceted filtering, full-text search, and a live-tailable Stream or paged Table body. Rows carry the trace's model spend and its initiating prompt, and are sortable by cost; they expand to an inline span summary, with a full per-trace span detail (waterfall, with cost attributed per span) and cross-signal logs.
- **Report** — renders the report as monospace text with a one-click "Copy markdown" button.

## Tests

The backend ships with Testcontainers-backed integration tests that spin up a real Postgres, push hand-built OTLP export requests to the `/v1/*` ingest endpoints, and assert the `/api/*` aggregations. Requires Docker running. The frontend has a Vitest suite with tests colocated next to the modules they cover.

```sh
# Backend — checkstyle + unit + Testcontainers integration tests.
cd backend && ./mvnw verify

# Frontend — vitest (bare `yarn test` is watch mode; test:coverage enforces the 80% thresholds).
cd frontend && yarn test --run
cd frontend && yarn test:coverage
```

[.github/workflows/pull-request.yml](.github/workflows/pull-request.yml) runs the same checks on every pull request — `./mvnw verify` on JDK 21, plus frontend lint / typecheck / test / build on Node 22.

## Release: Docker image

[.github/workflows/release.yml](.github/workflows/release.yml) cuts a release and publishes a single image to GHCR that serves both halves of the app — the Spring Boot API plus the built frontend — on port 8080 inside the container. Run it manually from the Actions tab (`patch` / `minor` / `major`) on `main`; it re-runs the pull-request gates, bumps `frontend/package.json` + `backend/pom.xml`, commits, tags `v<version>`, pushes the image as `:<tag>` and `:latest`, and creates the GitHub Release.

It commits, tags, and releases with the job-scoped `GITHUB_TOKEN` — no secrets to create. The one setup step is a `Release` environment restricted to `main` (Settings → Environments), which is what stops the workflow being dispatched from another branch. Note that `GITHUB_TOKEN` can't push to a protected `main`: if you add branch protection later, the workflow header explains the switch to a GitHub App token.

The workflow does all the building — `yarn build`, then `./mvnw package` — and the [Dockerfile](Dockerfile) only copies the resulting artifacts in. The frontend bundle ships as plain files at `/app/static` beside the jar (`SPRING_WEB_RESOURCES_STATIC_LOCATIONS` points Spring at them) rather than being folded into `backend/src/main/resources`, so build output never lands in the backend source tree. [SinglePageApplicationConfig](backend/src/main/java/com/guavasoft/agentcompass/config/SinglePageApplicationConfig.java) adds the history-mode fallback so React Router deep links like `/traces/{traceId}` resolve to `index.html`, while unmatched `/api/**` paths still 404.

No backend URL is baked into the bundle: the frontend fetches relative `/api/...` paths and the backend serves the SPA, so the API is always on the page's own origin. The workflow fails the build if a `localhost:8080` / `localhost:5173` reference ever appears in `dist/`, which would point users' browsers at their own machine.

Postgres is not part of the image — pass its connection details at run time ([docs/local-docker-deployment.md](docs/local-docker-deployment.md) wraps this in a compose stack):

```sh
docker run --rm -p 18080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/coding_agent_tuning \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=postgres \
  ghcr.io/guavasoftcom/agent-compass:latest
```

To build the same image locally, produce the artifacts first (the Dockerfile deliberately doesn't):

```sh
cd frontend && yarn build && cd ../backend && ./mvnw clean package -DskipTests && cd ..
docker build -t agent-compass:local .
```

The Dockerfile defaults to `backend/target/*.jar` and `frontend/dist`, so no `--build-arg` is needed — and `clean` keeps the glob unambiguous once version bumps leave older jars behind.

## Why this stack

- OTLP/HTTP directly into Spring Boot keeps the system **vendor-neutral** — no SigNoz, no Aspire, no proprietary query API to lock against. OTLP is the CNCF standard.
- Postgres + `jsonb` fits the telemetry shape: stable numeric spine, open-ended attribute bag, SQL aggregations for report generation.
- Material UI provides the component primitives; charts and tables are hand-built SVG/CSS (the Aurora retheme dropped `@mui/x-charts` / `@mui/x-data-grid`) so visuals match the rest of the UI exactly — no second visualization library.
- Lombok keeps the entity/DTO boilerplate down; MapStruct (`spring` component model) generates `MetricPoint` → `EventRowDto` translation; `lombok-mapstruct-binding` keeps the two annotation processors happy together.
- springdoc-openapi auto-derives the OpenAPI schema from the controllers — `@Tag`/`@Operation` annotations on each handler shape the Swagger UI without a separate spec file.
- Testcontainers gives the integration test a real Postgres (with `jsonb`) so the test exercises the same JPA + native SQL aggregation path that production uses; `@ServiceConnection` wires the container into Spring Boot's datasource automatically.
