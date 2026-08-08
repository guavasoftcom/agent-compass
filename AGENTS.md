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

Several of those properties are **mirrored as literals in Flyway SQL** — the `span_costs` /
`trace_costs` views (`V14`), the `LEFT JOIN LATERAL` predicates in `SpanRepository` that re-run those
views' filter against `log_records` for pushdown, and the `derive_log_severity()` function (`V6`).
Overriding `api-request-cost-attribute`, `api-request-event-name`, or the severity lists therefore
means a new migration redefining the views/function *and* updating the lateral predicates, or the
pages read from the wrong rows. Token/cost/active-time counters are cumulative per stream and ingest
precomputes reset-aware increments into `metric_points.value_delta`,
so every rollup is a plain `SUM(value_delta)` — details in
[backend/CLAUDE.md](backend/CLAUDE.md).

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
