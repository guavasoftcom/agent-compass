#!/usr/bin/env bash
#
# Agent Compass installer.
#
# Downloads the released docker-compose stack, points Claude Code's telemetry at
# it by merging the required env block into settings.json, and starts it.
#
#   curl -fsSL https://raw.githubusercontent.com/guavasoftcom/agent-compass/main/install.sh | bash
#
# Safe to re-run: the compose file is re-downloaded, only the keys this script
# owns are touched in settings.json (everything else is preserved, and a
# timestamped backup is written), and the connectivity hook is never duplicated.
#
# See docs/local-docker-deployment.md for what each setting feeds.

set -euo pipefail

REPOSITORY_RAW_BASE="https://raw.githubusercontent.com/guavasoftcom/agent-compass/main"
# Overridable so a fork, a branch, or a local checkout can be installed from.
COMPOSE_FILE_URL="${AGENT_COMPASS_COMPOSE_URL:-$REPOSITORY_RAW_BASE/docker-compose.yml}"

installationDirectory="${AGENT_COMPASS_HOME:-$HOME/.agent-compass}"
applicationPort="18080"
settingsFile=""
useProjectSettings="false"
installConnectivityHook="false"
startStack="true"
assumeYes="false"

usage() {
  cat <<'USAGE'
Agent Compass installer.

Usage: install.sh [options]

Options:
  --dir <path>        Where to keep docker-compose.yml (default: ~/.agent-compass,
                      or $AGENT_COMPASS_HOME).
  --port <port>       Host port for the dashboard, /api and OTLP ingest (default: 18080).
  --settings <path>   settings.json to update (default: ~/.claude/settings.json).
  --project           Update ./.claude/settings.json instead, scoping telemetry to
                      the current repository.
  --with-hook         Also install the UserPromptSubmit hook that blocks a prompt
                      when the telemetry backend is unreachable.
  --no-start          Configure everything but do not run docker compose.
  --skip-settings     Only download and start the stack; leave settings.json alone.
  --skip-checks       Do not fail when Claude Code or Docker is missing.
  -y, --yes           Do not prompt for confirmation.
  -h, --help          Show this help.

Environment:
  AGENT_COMPASS_HOME        Same as --dir.
  AGENT_COMPASS_COMPOSE_URL Where to fetch docker-compose.yml from (default: the
                            main branch on GitHub). Useful for forks and branches.

Examples:
  install.sh                          # install, configure globally, start
  install.sh --port 19090 --with-hook
  install.sh --project --no-start     # configure this repo only
USAGE
}

logStep() { printf '\033[1;34m==>\033[0m %s\n' "$1"; }
logInfo() { printf '    %s\n' "$1"; }
logWarn() { printf '\033[1;33mwarning:\033[0m %s\n' "$1" >&2; }
fail() { printf '\033[1;31merror:\033[0m %s\n' "$1" >&2; exit 1; }

updateSettings="true"
skipPrerequisiteChecks="false"

while [ $# -gt 0 ]; do
  case "$1" in
    --dir)
      [ $# -ge 2 ] || fail "--dir needs a path"
      installationDirectory="$2"; shift 2 ;;
    --port)
      [ $# -ge 2 ] || fail "--port needs a port number"
      applicationPort="$2"; shift 2 ;;
    --settings)
      [ $# -ge 2 ] || fail "--settings needs a path"
      settingsFile="$2"; shift 2 ;;
    --project) useProjectSettings="true"; shift ;;
    --with-hook) installConnectivityHook="true"; shift ;;
    --no-start) startStack="false"; shift ;;
    --skip-settings) updateSettings="false"; shift ;;
    --skip-checks) skipPrerequisiteChecks="true"; shift ;;
    -y|--yes) assumeYes="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1 (try --help)" ;;
  esac
done

case "$applicationPort" in
  ''|*[!0-9]*) fail "--port must be a number, got '$applicationPort'" ;;
esac
[ "$applicationPort" -ge 1 ] && [ "$applicationPort" -le 65535 ] \
  || fail "--port must be between 1 and 65535, got '$applicationPort'"

if [ -z "$settingsFile" ]; then
  if [ "$useProjectSettings" = "true" ]; then
    settingsFile="$PWD/.claude/settings.json"
  else
    settingsFile="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/settings.json"
  fi
fi

otlpEndpoint="http://localhost:$applicationPort"

# --- preflight -------------------------------------------------------------

logStep "Checking prerequisites"

command -v curl >/dev/null 2>&1 || fail "curl is required"

pythonBinary=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1; then
    pythonBinary="$candidate"
    break
  fi
done
if [ "$updateSettings" = "true" ] && [ -z "$pythonBinary" ]; then
  fail "python3 is required to merge settings.json (or re-run with --skip-settings)"
fi

# A missing prerequisite is fatal by default: without Docker there is nothing to
# start, and without Claude Code the settings this script writes have no reader.
# --skip-checks downgrades both to warnings for unattended or partial installs.
requirementMissing() {
  if [ "$skipPrerequisiteChecks" = "true" ]; then
    logWarn "$1"
  else
    fail "$1 (or re-run with --skip-checks)"
  fi
}

# Claude Code may be installed without landing on PATH — the native installer
# puts it in ~/.claude/local, and npm -g installs vary by prefix.
claudeBinary=""
if command -v claude >/dev/null 2>&1; then
  claudeBinary="$(command -v claude)"
else
  for candidate in \
    "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/local/claude" \
    "$HOME/.local/bin/claude" \
    "/usr/local/bin/claude" \
    "/opt/homebrew/bin/claude"
  do
    if [ -x "$candidate" ]; then
      claudeBinary="$candidate"
      break
    fi
  done
fi

if [ -n "$claudeBinary" ]; then
  claudeVersion="$("$claudeBinary" --version 2>/dev/null | head -n 1)"
  logInfo "claude            : ${claudeVersion:-found} ($claudeBinary)"
else
  requirementMissing "Claude Code was not found on PATH — install it from https://claude.com/product/claude-code"
  logInfo "claude            : not found"
fi

if command -v docker >/dev/null 2>&1; then
  dockerVersion="$(docker --version 2>/dev/null | head -n 1)"
  logInfo "docker            : ${dockerVersion:-found}"

  if docker compose version >/dev/null 2>&1; then
    logInfo "docker compose    : $(docker compose version --short 2>/dev/null || echo v2)"
  else
    requirementMissing "Docker Compose v2 is required — 'docker compose version' failed (the old 'docker-compose' binary is not enough)"
  fi

  # Only the actual start needs a live daemon; configuring can happen offline.
  if [ "$startStack" = "true" ] && ! docker info >/dev/null 2>&1; then
    requirementMissing "the Docker daemon is not running — start Docker Desktop and try again"
    startStack="false"
    logWarn "skipping the start step; run 'docker compose up -d' in $installationDirectory once Docker is up"
  fi
elif [ "$startStack" = "true" ]; then
  requirementMissing "Docker was not found on PATH — install Docker Desktop from https://docs.docker.com/get-docker/"
  logInfo "docker            : not found"
  startStack="false"
else
  # --no-start was asked for, so a missing Docker blocks nothing right now.
  logWarn "Docker was not found on PATH — you will need it to run the stack later"
fi

logStep "Planned changes"
logInfo "install directory : $installationDirectory"
logInfo "port              : $applicationPort"
if [ "$updateSettings" = "true" ]; then
  logInfo "settings file     : $settingsFile"
  logInfo "connectivity hook : $installConnectivityHook"
else
  logInfo "settings file     : (skipped)"
fi

if [ "$assumeYes" != "true" ] && [ -t 0 ]; then
  printf 'Proceed? [Y/n] '
  read -r confirmation
  case "$confirmation" in
    ''|y|Y|yes|YES) ;;
    *) echo "Aborted."; exit 0 ;;
  esac
fi

# --- compose file ----------------------------------------------------------

logStep "Downloading docker-compose.yml"

mkdir -p "$installationDirectory"
composeFile="$installationDirectory/docker-compose.yml"
temporaryComposeFile="$composeFile.download.$$"

logInfo "source: $COMPOSE_FILE_URL"
if ! curl -fsSL --retry 3 --retry-delay 1 -o "$temporaryComposeFile" "$COMPOSE_FILE_URL"; then
  rm -f "$temporaryComposeFile"
  fail "could not download $COMPOSE_FILE_URL (a 404 here means the repository is not public yet — \
set AGENT_COMPASS_COMPOSE_URL to another source, or copy docker-compose.yml into $installationDirectory by hand)"
fi
[ -s "$temporaryComposeFile" ] || { rm -f "$temporaryComposeFile"; fail "downloaded compose file is empty"; }
grep -q '^services:' "$temporaryComposeFile" \
  || { rm -f "$temporaryComposeFile"; fail "downloaded file does not look like a compose file"; }

mv "$temporaryComposeFile" "$composeFile"
logInfo "wrote $composeFile"

# APP_PORT lives in a .env next to the compose file so plain `docker compose up`
# in that directory keeps using the same port later.
environmentFile="$installationDirectory/.env"
if [ -f "$environmentFile" ] && grep -q '^APP_PORT=' "$environmentFile"; then
  # Rewrite the existing assignment, leave every other line untouched.
  temporaryEnvironmentFile="$environmentFile.tmp.$$"
  sed "s|^APP_PORT=.*|APP_PORT=$applicationPort|" "$environmentFile" > "$temporaryEnvironmentFile"
  mv "$temporaryEnvironmentFile" "$environmentFile"
else
  printf 'APP_PORT=%s\n' "$applicationPort" >> "$environmentFile"
fi
logInfo "wrote $environmentFile (APP_PORT=$applicationPort)"

# --- Claude Code settings --------------------------------------------------

settingsBackupFile=""

if [ "$updateSettings" = "true" ]; then
  logStep "Updating $settingsFile"

  mkdir -p "$(dirname "$settingsFile")"

  # Copy the untouched original aside first, so a bad merge — or simply not
  # liking the result — is a one-command undo.
  if [ -s "$settingsFile" ]; then
    settingsBackupFile="$settingsFile.backup-$(date +%Y%m%d-%H%M%S)"
    if [ -e "$settingsBackupFile" ]; then
      settingsBackupFile="$settingsBackupFile-$$"
    fi
    cp -p "$settingsFile" "$settingsBackupFile" \
      || fail "could not back up $settingsFile — nothing was changed"
    logInfo "backup written: $settingsBackupFile"
    logInfo "restore it any time with:"
    logInfo "  cp \"$settingsBackupFile\" \"$settingsFile\""
  else
    logInfo "no existing settings file — creating a new one"
  fi

  # The merge only writes on success, so a failure leaves the original in place
  # and the backup is just clutter.
  if ! AGENT_COMPASS_SETTINGS_FILE="$settingsFile" \
  AGENT_COMPASS_ENDPOINT="$otlpEndpoint" \
  AGENT_COMPASS_WITH_HOOK="$installConnectivityHook" \
  "$pythonBinary" <<'PYTHON'
import json
import os
import sys

settings_path = os.environ["AGENT_COMPASS_SETTINGS_FILE"]
endpoint = os.environ["AGENT_COMPASS_ENDPOINT"]
install_hook = os.environ["AGENT_COMPASS_WITH_HOOK"] == "true"

# Marker so a re-run recognises its own hook instead of appending a second copy.
HOOK_MARKER = "agent-compass-connectivity"
HOOK_COMMAND = (
    ": " + HOOK_MARKER + "; "
    'endpoint="${OTEL_EXPORTER_OTLP_ENDPOINT:-' + endpoint + '}"; '
    'curl -s -o /dev/null --max-time 2 "$endpoint/v1/logs" || '
    '''echo '{"decision":"block","reason":"Telemetry backend is unreachable (OTLP ingest at '"$endpoint"') '''
    '''- this session is not being captured. Start Agent Compass (docker compose up -d), then resubmit."}' '''
).strip()

telemetry_environment = {
    "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
    "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA": "1",
    "CLAUDE_CODE_OTEL_CONTENT_MAX_LENGTH": "1000000000",
    "OTEL_METRICS_EXPORTER": "otlp",
    "OTEL_LOGS_EXPORTER": "otlp",
    "OTEL_TRACES_EXPORTER": "otlp",
    "OTEL_EXPORTER_OTLP_PROTOCOL": "http/protobuf",
    "OTEL_EXPORTER_OTLP_ENDPOINT": endpoint,
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
    "OTEL_TRACES_EXPORT_INTERVAL": "60000",
}

settings = {}
if os.path.exists(settings_path) and os.path.getsize(settings_path) > 0:
    with open(settings_path, encoding="utf-8") as handle:
        raw = handle.read()
    try:
        settings = json.loads(raw)
    except json.JSONDecodeError as error:
        sys.exit(
            "%s is not valid JSON (%s) - fix or move it, then re-run" % (settings_path, error)
        )
    if not isinstance(settings, dict):
        sys.exit("%s must contain a JSON object" % settings_path)

environment_block = settings.setdefault("env", {})
if not isinstance(environment_block, dict):
    sys.exit('"env" in %s is not an object - fix it, then re-run' % settings_path)

overwritten = sorted(
    key for key, value in telemetry_environment.items()
    if key in environment_block and environment_block[key] != value
)
environment_block.update(telemetry_environment)

hook_status = "not requested"
if install_hook:
    hooks = settings.setdefault("hooks", {})
    if not isinstance(hooks, dict):
        sys.exit('"hooks" in %s is not an object - fix it, then re-run' % settings_path)
    matchers = hooks.setdefault("UserPromptSubmit", [])
    if not isinstance(matchers, list):
        sys.exit('"hooks.UserPromptSubmit" in %s is not an array - fix it, then re-run' % settings_path)

    existing = None
    for matcher in matchers:
        if not isinstance(matcher, dict):
            continue
        for hook in matcher.get("hooks", []) or []:
            if isinstance(hook, dict) and HOOK_MARKER in str(hook.get("command", "")):
                existing = hook
                break
        if existing:
            break

    if existing is not None:
        existing["command"] = HOOK_COMMAND
        hook_status = "updated (endpoint refreshed)"
    else:
        matchers.append({
            "hooks": [{
                "type": "command",
                "command": HOOK_COMMAND,
                "timeout": 5,
                "statusMessage": "Checking telemetry backend connectivity",
            }]
        })
        hook_status = "installed"

with open(settings_path, "w", encoding="utf-8") as handle:
    json.dump(settings, handle, indent=2)
    handle.write("\n")

print("    telemetry env keys written: %d" % len(telemetry_environment))
if overwritten:
    print("    changed existing values: %s" % ", ".join(overwritten))
print("    connectivity hook: %s" % hook_status)
PYTHON
  then
    if [ -n "$settingsBackupFile" ]; then
      rm -f "$settingsBackupFile"
      logInfo "your settings were left untouched; the backup was removed again"
    fi
    exit 1
  fi
fi

# --- run -------------------------------------------------------------------

composeCommand=(docker compose --project-directory "$installationDirectory" -f "$composeFile")

if [ "$startStack" = "true" ]; then
  logStep "Pulling images"
  "${composeCommand[@]}" pull

  logStep "Starting the stack"
  "${composeCommand[@]}" up -d

  logStep "Waiting for the dashboard to answer on port $applicationPort"
  ready="false"
  attempt=1
  while [ "$attempt" -le 60 ]; do
    if curl -fsS -o /dev/null --max-time 2 "$otlpEndpoint/api/sessions/summary?minutes=1"; then
      ready="true"
      break
    fi
    sleep 2
    attempt=$((attempt + 1))
  done

  if [ "$ready" = "true" ]; then
    logInfo "up at $otlpEndpoint"
  else
    logWarn "the app did not answer within ~2 minutes (Flyway migrations can be slow on a first boot)"
    logWarn "check with: docker compose --project-directory $installationDirectory logs -f app"
  fi
fi

# --- next steps ------------------------------------------------------------

cat <<SUMMARY

Done.

  Dashboard   $otlpEndpoint
  OpenAPI UI  $otlpEndpoint/swagger-ui.html
  Stack       $composeFile

Manage it with:
  docker compose --project-directory $installationDirectory logs -f app
  docker compose --project-directory $installationDirectory down
  docker compose --project-directory $installationDirectory pull app && \\
    docker compose --project-directory $installationDirectory up -d app

SUMMARY

if [ "$updateSettings" = "true" ]; then
  echo "Restart Claude Code (env vars are read at startup) and telemetry will start flowing."
  echo "Data appears after one export interval - up to 60s."

  if [ -n "$settingsBackupFile" ]; then
    cat <<BACKUP

Your previous $settingsFile was backed up before any change:

  $settingsBackupFile

Restore it with:
  cp "$settingsBackupFile" "$settingsFile"
BACKUP
  fi
fi
