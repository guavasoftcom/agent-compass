#!/usr/bin/env bash
#
# Agent Compass updater.
#
# Pulls the latest released image for the stack install.sh set up, and recreates
# only the app container — Postgres and its data volume are left untouched.
#
#   curl -fsSL https://raw.githubusercontent.com/guavasoftcom/agent-compass/main/update.sh | bash
#
# Safe to re-run: if the image tag hasn't changed, pull/up are no-ops.
#
# See docs/local-docker-deployment.md#upgrade-to-a-newer-release for the
# manual equivalent and how to move off :latest onto a pinned release tag.

set -euo pipefail

installationDirectory="${AGENT_COMPASS_HOME:-$HOME/.agent-compass}"
composeFile=""

usage() {
  cat <<'USAGE'
Agent Compass updater.

Usage: update.sh [options]

Options:
  --dir <path>   Directory holding docker-compose.yml (default: ~/.agent-compass,
                 or $AGENT_COMPASS_HOME).
  --file <path>  Compose file to use directly, instead of --dir/docker-compose.yml.
  -h, --help     Show this help.

Environment:
  AGENT_COMPASS_HOME    Same as --dir.
  AGENT_COMPASS_IMAGE   Same override install.sh and docker-compose.yml honor -
                        pin a specific tag here instead of tracking :latest.

Examples:
  update.sh                                          # pull + recreate app
  AGENT_COMPASS_IMAGE=ghcr.io/guavasoftcom/agent-compass:v1.2.0 update.sh
USAGE
}

logStep() { printf '\033[1;34m==>\033[0m %s\n' "$1"; }
logInfo() { printf '    %s\n' "$1"; }
fail() { printf '\033[1;31merror:\033[0m %s\n' "$1" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dir)
      [ $# -ge 2 ] || fail "--dir needs a path"
      installationDirectory="$2"; shift 2 ;;
    --file)
      [ $# -ge 2 ] || fail "--file needs a path"
      composeFile="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1 (try --help)" ;;
  esac
done

if [ -z "$composeFile" ]; then
  composeFile="$installationDirectory/docker-compose.yml"
fi

[ -f "$composeFile" ] \
  || fail "no compose file at $composeFile - run install.sh first, or pass --dir/--file"

command -v docker >/dev/null 2>&1 || fail "Docker was not found on PATH"
docker compose version >/dev/null 2>&1 \
  || fail "Docker Compose v2 is required - 'docker compose version' failed"
docker info >/dev/null 2>&1 \
  || fail "the Docker daemon is not running - start Docker Desktop and try again"

composeCommand=(docker compose --project-directory "$(dirname "$composeFile")" -f "$composeFile")

logStep "Pulling the latest app image"
if [ -n "${AGENT_COMPASS_IMAGE:-}" ]; then
  logInfo "AGENT_COMPASS_IMAGE=$AGENT_COMPASS_IMAGE"
fi
"${composeCommand[@]}" pull app

logStep "Recreating the app container"
"${composeCommand[@]}" up -d app

logStep "Done"
logInfo "watch it come up with: docker compose --project-directory $(dirname "$composeFile") logs -f app"
