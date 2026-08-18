# Deploying Agent Compass locally with Docker

Runs the released image — the Spring Boot API and the dashboard SPA together in one container — alongside its own Postgres. This is the "use it" path; for the dev-server workflow (`./mvnw spring-boot:run` + `yarn dev`) see the [README](../README.md#run).

Everything lives on **one port**: the dashboard, the `/api` JSON endpoints, and the `/v1/*` OTLP ingest all answer on `18080`. The frontend is served by the backend, so the browser calls the API on its own origin — nothing needs to know a backend hostname.

The container listens on 8080 internally, but it's published on **18080** to stay clear of the crowded defaults — 8080 is the dev backend's port, and 80/8080 are the first thing any other local service claims. Set `APP_PORT` to move it.

## Prerequisites

- Docker with Compose v2 (`docker compose version`)
- Nothing else — no JDK, no Node, unless you're building the image yourself

## Start it

[install.sh](../install.sh) does the whole path in one command — fetches the compose file, points Claude Code at the stack, brings it up:

```sh
git clone https://github.com/guavasoftcom/agent-compass.git
cd agent-compass && ./install.sh
```

Once the repository is public, the same thing without the clone:

```sh
curl -fsSL https://raw.githubusercontent.com/guavasoftcom/agent-compass/main/install.sh | bash
```

It checks that Docker and Claude Code are installed before changing anything, downloads `docker-compose.yml` into `~/.agent-compass`, merges the env block from [_Point Claude Code at it_](#point-claude-code-at-it) into `~/.claude/settings.json` — backing the original up first and printing the command that restores it — then pulls and starts the stack and waits for the dashboard to answer. Re-running it is safe: it rewrites only the keys it owns and never duplicates the hook.

`./install.sh --help` lists the flags. The ones that matter most: `--port` to move off 18080, `--project` to scope the telemetry settings to the current repository instead of your user settings, `--with-hook` to add the connectivity guard described below, `--no-start` / `--skip-settings` to do only half the job.

Or drive compose yourself, from a checkout or any directory holding the compose file:

```sh
docker compose pull
docker compose up -d
```

Open <http://localhost:18080> for the dashboard, or <http://localhost:18080/swagger-ui.html> for the OpenAPI UI. Flyway migrates the schema on first start, so the initial boot takes a few seconds longer than later ones.

Watch it come up:

```sh
docker compose logs -f app
```

Stop it, keeping your telemetry data:

```sh
docker compose down
```

## Upgrade to a newer release

The default image reference is `:latest`, so upgrading is a pull plus a recreate:

```sh
docker compose pull app
docker compose up -d app
```

`pull` fetches the newer image; `up -d` notices the image changed and recreates only the `app` container (Postgres and the data volume are untouched). Flyway applies any new migrations on the first boot of the new version, so give it a few seconds before refreshing the dashboard.

If you pinned a release tag through `AGENT_COMPASS_IMAGE` (see _Configuration_), `pull` will just re-fetch the pinned version — update the tag first:

```sh
AGENT_COMPASS_IMAGE=ghcr.io/guavasoftcom/agent-compass:v1.1.0 docker compose up -d
```

(compose pulls a missing image on `up` by itself). Put the variable in a `.env` file next to the compose file rather than the shell if you want the pin to survive future `docker compose` invocations.

Superseded images accumulate on disk; `docker image prune` clears the untagged leftovers when you care.

## Point Claude Code at it

Claude Code emits telemetry only when you turn it on, and each signal — metrics, logs (events), traces — has its own exporter switch. The dashboard uses all three: metrics drive Tokens/Insights, logs drive Tool Activity and the log explorer, traces drive the Traces pages. Turning on only metrics leaves most of the UI empty.

**One endpoint, one destination.** `OTEL_EXPORTER_OTLP_ENDPOINT` decides which backend receives your telemetry, and there is only one value — you can't feed both stacks at once. The compose stack listens on `http://localhost:18080`; the dev backend (`./mvnw spring-boot:run`) listens on `http://localhost:8080`. Those are separate databases, so telemetry recorded against one is not visible in the other. Switching which one you're feeding means editing this value and starting a new Claude Code session; if you moved the container with `APP_PORT`, match that instead.

### The full configuration

Put these in the `env` block of `~/.claude/settings.json` so every session picks them up without touching your shell profile. Use `.claude/settings.json` inside a project instead to scope it to that repo. This is the complete set the dashboard makes use of — every page has something that depends on one of them:

```json
{
  "env": {
    "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
    "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA": "1",
    "CLAUDE_CODE_OTEL_CONTENT_MAX_LENGTH": "1000000000",

    "OTEL_METRICS_EXPORTER": "otlp",
    "OTEL_LOGS_EXPORTER": "otlp",
    "OTEL_TRACES_EXPORTER": "otlp",

    "OTEL_EXPORTER_OTLP_PROTOCOL": "http/protobuf",
    "OTEL_EXPORTER_OTLP_ENDPOINT": "http://localhost:18080",
    "OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE": "cumulative",

    "OTEL_LOG_ASSISTANT_RESPONSES": "1",
    "OTEL_LOG_USER_PROMPTS": "1",
    "OTEL_LOG_TOOL_DETAILS": "1",
    "OTEL_LOG_TOOL_CONTENT": "1",
    "OTEL_LOG_RAW_API_BODIES": "1",

    "OTEL_METRICS_INCLUDE_SESSION_ID": "1",
    "OTEL_METRICS_INCLUDE_ACCOUNT_UUID": "false",
    "OTEL_METRICS_INCLUDE_ENTRYPOINT": "true",
    "OTEL_METRICS_INCLUDE_RESOURCE_ATTRIBUTES": "true",

    "OTEL_METRIC_EXPORT_INTERVAL": "60000",
    "OTEL_LOGS_EXPORT_INTERVAL": "60000",
    "OTEL_TRACES_EXPORT_INTERVAL": "60000"
  }
}
```

For a single shell instead, `export` the same names and values.

### What each one feeds

| Variable                                                       | What breaks or degrades without it                                                                                                                                                                                                                                      |
| -------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `CLAUDE_CODE_ENABLE_TELEMETRY=1`                               | Master switch. Nothing is emitted without it, whatever else is set.                                                                                                                                                                                                     |
| `CLAUDE_CODE_ENHANCED_TELEMETRY_BETA=1`                        | Opts into the richer event set beyond the stable baseline. Leave it on — the dashboard reads several of the extra events.                                                                                                                                               |
| `CLAUDE_CODE_OTEL_CONTENT_MAX_LENGTH`                          | Maximum size (in bytes) of content payloads in telemetry. Set high to capture full tool inputs, responses, and API bodies without truncation.                                                                                                                            |
| `OTEL_METRICS_EXPORTER=otlp`                                   | Token, cost, session, and lines-of-code metrics → Tokens, Sessions, Insights.                                                                                                                                                                                           |
| `OTEL_LOGS_EXPORTER=otlp`                                      | Tool calls, permission decisions, and hook executions → Tool Activity, Logs, and the tuning report.                                                                                                                                                                     |
| `OTEL_TRACES_EXPORTER=otlp`                                    | Spans → Traces and the per-trace waterfall.                                                                                                                                                                                                                             |
| `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`                    | The wire format the `/v1/*` ingest endpoints accept. gRPC is not served.                                                                                                                                                                                                |
| `OTEL_EXPORTER_OTLP_ENDPOINT`                                  | Base URL; Claude Code appends `/v1/metrics`, `/v1/logs`, `/v1/traces`.                                                                                                                                                                                                  |
| `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=cumulative` | **Required — see below.** Ingest assumes cumulative counters.                                                                                                                                                                                                           |
| `OTEL_LOG_ASSISTANT_RESPONSES=1`                               | Assistant response text on response events. Enables full message content capture in logs and traces.                                                                                                                                                                    |
| `OTEL_LOG_USER_PROMPTS=1`                                      | Prompt text on `user_prompt` events. Without it, Sessions still counts turns but every prompt reads null, and the Traces explorer's prompt column is empty.                                                                                                             |
| `OTEL_LOG_TOOL_DETAILS` / `OTEL_LOG_TOOL_CONTENT`              | The `tool_input` attribute on tool events. This is load-bearing for more than it sounds: bash anti-pattern detection, redundant-file-read and path near-miss analysis in the tuning report, and the Skills & Agents page all read identifiers out of `tool_input` JSON. |
| `OTEL_LOG_RAW_API_BODIES=1`                                    | `api_request_body` / `api_response_body` events, surfaced as debug rows with expandable bodies in the Logs explorer.                                                                                                                                                    |
| `OTEL_METRICS_INCLUDE_SESSION_ID=1`                            | The `session.id` metric attribute. Nearly every aggregation in this backend groups by it, so turning it off to save cardinality would flatten Sessions, per-session token rollups, and most of Tool Activity.                                                           |
| `OTEL_METRICS_INCLUDE_ACCOUNT_UUID`                            | Account UUID on metrics. Enables multi-account / multi-user filtering and analysis.                                                                                                                                                                                     |
| `OTEL_METRICS_INCLUDE_ENTRYPOINT`                              | Entrypoint (CLI, extension, IDE, web) on metrics. Enables per-interface usage analysis.                                                                                                                                                                                |
| `OTEL_METRICS_INCLUDE_RESOURCE_ATTRIBUTES`                     | Resource-level attributes (hostname, process ID, runtime version) on metrics. Useful for debugging deployment and environment issues.                                                                                                                                   |
| `OTEL_*_EXPORT_INTERVAL`                                       | Export batching, in ms. At 60000 a short session can end before its first export — drop these to `10000` while debugging ingest, then put them back.                                                                                                                    |

### Two settings that are load-bearing here

**`OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE` must be `cumulative`.** Claude Code's `claude_code.*` counters are cumulative and re-emitted per stream, and this backend depends on that: [`MetricPointRepository`](../backend/src/main/java/com/guavasoft/agentcompass/repository/MetricPointRepository.java) precomputes a reset-aware per-row increment at ingest (`value_delta`, added in `V11`) as `current − previous` per stream, falling back to `current` when the counter resets. Every token, cost, and active-time rollup then reads `SUM(value_delta)`. Point a _delta_-temporality exporter at it and each point is already an increment, so that subtraction differences a series of increments and the numbers come out wrong — with no error to tell you.

**`OTEL_LOG_TOOL_DETAILS` / `OTEL_LOG_TOOL_CONTENT` are not just cosmetic.** `tool_input` isn't only shown in the UI; it's parsed. Skill and subagent identifiers are read from `skill.name` / `subagent_type` **or** from fields inside the `tool_input` JSON, and the report's bash-command and file-path analysis reads `command` and `file_path` out of the same blob. With these off, the Skills & Agents page and several report sections have nothing to work from.

### Optional additions

- `OTEL_METRICS_INCLUDE_VERSION=1` — stamps the Claude Code version on metrics, which makes it possible to line a behavior change up against an upgrade.
- `OTEL_RESOURCE_ATTRIBUTES=key=value,key2=value2` — your own dimensions (team, machine, experiment). They land in the jsonb payload and are filterable in the Logs and Traces explorers.

### Optional: Require backend to be running

To prevent accidentally running sessions that won't be captured (because the telemetry backend is down), add a hook to `~/.claude/settings.json` that checks backend connectivity before allowing a session to start.

`./install.sh --with-hook` writes this hook for you (matching whatever `--port` you chose), so take the manual route below only if you didn't use the installer — adding both leaves you with two copies that each run on every prompt:

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "endpoint=\"${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:18080}\"; curl -s -o /dev/null --max-time 2 \"$endpoint/v1/logs\" || echo '{\"decision\":\"block\",\"reason\":\"Telemetry backend is unreachable (OTLP ingest at '\"$endpoint\"') — this session is not being captured. Start Agent Compass (docker compose up -d or cd backend && ./mvnw spring-boot:run), then resubmit.\"}'",
            "timeout": 5,
            "statusMessage": "Checking telemetry backend connectivity"
          }
        ]
      }
    ]
  }
}
```

This hook runs before each prompt and checks if the backend's `/v1/logs` endpoint responds. It respects your `OTEL_EXPORTER_OTLP_ENDPOINT` setting, so you can use it with either the docker-compose stack (`:18080`) or the dev backend (`:8080`). If you move the container with `APP_PORT`, the hook will follow automatically.

⚠️ **Note:** If Agent Compass is not running when the hook executes, Claude Code will silently block the prompt without displaying an error message. This is a limitation of the current hook implementation. If your prompts seem to hang or silently fail to execute, first verify that Agent Compass is running with `docker compose ps` or `curl http://localhost:18080` (or your configured `APP_PORT`). Then resubmit your prompt.

### Verify it's flowing

Start a fresh Claude Code session, run a couple of commands, then wait for one export interval and check:

```sh
curl -s "http://localhost:18080/api/sessions/summary?minutes=60"
curl -s "http://localhost:18080/api/tool-activity/calls?minutes=60" | head -c 300
```

Non-empty results mean ingest is working — the dashboard at <http://localhost:18080> will show the same data. If they stay empty, see _Troubleshooting_ below.

### Other OTel-instrumented agents

Any agent that speaks OTLP works the same way — set `OTEL_EXPORTER_OTLP_PROTOCOL` and `OTEL_EXPORTER_OTLP_ENDPOINT` as above. The Tokens, Sessions, and Tool Activity pages assume Claude Code's metric and attribute names; if your agent uses different ones, override `tuning.tool-decision-metric` and `tuning.tool-attribute` on the backend (see the [README](../README.md#pointing-your-coding-agent-at-the-backend)).

## Configuration

[docker-compose.yml](../docker-compose.yml) reads these from your environment (or a `.env` file next to it); all have working defaults.

| Variable                                              | Default                                         | Purpose                                                                                                    |
| ----------------------------------------------------- | ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `APP_PORT`                                            | `18080`                                         | Host port for the dashboard, `/api`, and OTLP ingest. Kept off 80/8080 so it can run beside a dev backend. |
| `AGENT_COMPASS_IMAGE`                                 | `ghcr.io/guavasoftcom/agent-compass:latest`     | Image to run. Pin a release tag (`:v0.1.0`) or point at a locally built image.                             |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | `postgres` / `postgres` / `coding_agent_tuning` | Database credentials, applied to both services.                                                            |
| `JAVA_OPTS`                                           | empty                                           | Extra JVM flags, e.g. `-Xmx1g`.                                                                            |

Postgres is intentionally **not** published to the host — the app reaches it over the compose network. To attach `psql` or a GUI client, uncomment the `ports` block on the `postgres` service (it defaults to `5433` so it won't collide with a dev Postgres on 5432).

## Data, and how it relates to the dev stack

This stack is isolated from development on purpose. Its compose project is `agent-compass`, so its data lives in the `agent-compass_postgres-data` volume, while `backend/docker-compose.yml` — the stack `./mvnw spring-boot:run` brings up — owns `coding-agent-tuning_postgres-data`. Neither can drop the other's data, but it also means **the dashboard here starts empty even if your dev database is full**.

To read the dev data from this stack instead, stop the dev stack first (one Postgres per volume) and declare its volume as external:

```yaml
volumes:
  postgres-data:
    external: true
    name: coding-agent-tuning_postgres-data
```

`docker compose down -v` deletes the volume of whichever stack you run it in — with the snippet above in place, that would be your development database.

## Running an image you built yourself

The [Dockerfile](../Dockerfile) does no building of its own: it copies in a jar and a Vite bundle that already exist. Build both first, then hand the image name to compose.

```sh
cd frontend && yarn install && yarn build && cd ..
cd backend && ./mvnw clean package -DskipTests && cd ..

docker build -t agent-compass:local .
AGENT_COMPASS_IMAGE=agent-compass:local docker compose up -d
```

`docker build` with no `--build-arg` picks up the Dockerfile defaults (`backend/target/*.jar` and `frontend/dist`). If `backend/target` holds more than one jar — say, a stale version from before a bump — the glob is ambiguous and the build fails; `./mvnw clean package -DskipTests` clears it.

The image is published by [.github/workflows/release.yml](../.github/workflows/release.yml), which builds the same two artifacts and tags the result `:v<version>` and `:latest`.

## Troubleshooting

**`Bind for 0.0.0.0:18080 failed: port is already allocated`** — something else claimed the port. Run this stack elsewhere: `APP_PORT=19090 docker compose up -d`.

**App container restarts with `Driver claims to not accept jdbcUrl`** — `SPRING_DATASOURCE_URL` reached the container empty. Check that whatever sets it (your shell, a `.env`) isn't exporting a blank value; an empty variable overrides the compose default rather than falling back to it.

**Dashboard loads but every panel is empty** — the app is running against its own fresh database. Either send it telemetry (see above), or attach the dev volume as described in _Data, and how it relates to the dev stack_.

**Telemetry enabled but nothing arrives** — work through these in order:

1. **Check which backend you're feeding.** `OTEL_EXPORTER_OTLP_ENDPOINT` must match `APP_PORT` (18080 by default), with no `/v1/...` suffix — Claude Code appends the signal path itself. Pointing at `:8080` sends everything to the dev backend instead, where it lands in a different database and never appears here.
2. **Wait one export interval.** At `OTEL_METRIC_EXPORT_INTERVAL=60000` a session that ends quickly may exit before its first export. Drop it to `10000` while testing.
3. **Restart Claude Code.** The variables are read at startup; a session already running when you set them keeps the old configuration.
4. **Confirm the endpoint answers:** `curl -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/x-protobuf' --data-binary '' http://localhost:18080/v1/metrics` should return `400` (route exists, empty body rejected). A `404` means you're pointed at the wrong port or path; a connection refused means the stack isn't up.

**Metrics show up but Tool Activity and Traces stay empty** — only `OTEL_METRICS_EXPORTER` is set. Tool calls, permission decisions, and hooks arrive as _logs_, and the Traces pages need _spans_; set `OTEL_LOGS_EXPORTER=otlp` and `OTEL_TRACES_EXPORTER=otlp` too.

**Session prompt timeline shows turns but no prompt text** — `OTEL_LOG_USER_PROMPTS` is unset. Prompt content is opt-in; set it to `1` and start a new session.

**Token or cost totals look implausible** — check `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE` is `cumulative`. Delta temporality produces no error, just wrong arithmetic (see _Two settings that are load-bearing here_). Rows already ingested under the wrong setting stay wrong; their `value_delta` was computed at ingest time.

**`no such image` on `docker compose up`** — the released image hasn't been published yet, or you're on a private package. Build locally as shown above, or `docker login ghcr.io` first.
