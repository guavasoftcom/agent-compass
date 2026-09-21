#!/usr/bin/env bash
#
# Agent Compass Postgres 16 -> 18 upgrade.
#
# The stack install.sh set up runs Postgres 16. Postgres cannot open a data
# directory written by a different major version, so this script dumps the
# database out of the old volume and restores it into a new one running
# Postgres 18. The old volume, the dump and the previous compose file are all
# left in place, so the upgrade can be rolled back.
#
#   curl -fsSL https://raw.githubusercontent.com/guavasoftcom/agent-compass/main/upgrade-postgres-16-to-18.sh | bash
#
# install.sh and update.sh are deliberately untouched by this: update.sh only
# recreates the app container, and a fresh install.sh already gets Postgres 18.
# Run this once on an existing install BEFORE re-running install.sh — install.sh
# re-downloads the compose file, and the new one points at an empty Postgres 18
# volume, so the dashboard would come up empty. Your data is safe in the old
# volume, and --help says how to recover.
#
# Nothing is changed until the dump has been taken and verified.
#
# See docs/local-docker-deployment.md#upgrade-postgres-16-to-18 for the manual
# equivalent.

set -euo pipefail

REPOSITORY_RAW_BASE="https://raw.githubusercontent.com/guavasoftcom/agent-compass/main"
# Overridable so a fork, a branch, or a local checkout can be used, same as install.sh.
COMPOSE_FILE_URL="${AGENT_COMPASS_COMPOSE_URL:-$REPOSITORY_RAW_BASE/docker-compose.yml}"

SOURCE_MAJOR_VERSION="16"
TARGET_MAJOR_VERSION="18"
SOURCE_IMAGE="postgres:$SOURCE_MAJOR_VERSION"
TARGET_IMAGE="postgres:$TARGET_MAJOR_VERSION"
# The volume keys docker-compose.yml declares before and after this upgrade.
SOURCE_VOLUME_KEY="postgres-data"
TARGET_VOLUME_KEY="postgres-data-18"
# Maintenance memory per restore worker: index builds dominate a restore of this
# schema, and the stock 64MB makes each one spill to disk.
RESTORE_MAINTENANCE_WORK_MEMORY="512MB"
READINESS_ATTEMPTS=150
READINESS_INTERVAL_SECONDS=2

installationDirectory="${AGENT_COMPASS_HOME:-$HOME/.agent-compass}"
projectName="agent-compass"
sourceVolumeName=""
parallelJobs="4"
startStack="true"
assumeYes="false"

usage() {
  cat <<'USAGE'
Agent Compass Postgres 16 -> 18 upgrade.

Dumps the database from the Postgres 16 volume, restores it into a new
Postgres 18 volume, and switches the stack over. The old volume is not modified
or removed.

Usage: upgrade-postgres-16-to-18.sh [options]

Options:
  --dir <path>        Directory holding docker-compose.yml (default: ~/.agent-compass,
                      or $AGENT_COMPASS_HOME).
  --project <name>    Compose project name (default: agent-compass). The volumes are
                      <name>_postgres-data (old) and <name>_postgres-data-18 (new).
  --volume <name>     Old Postgres 16 volume, if it is not <project>_postgres-data.
  --jobs <n>          Parallel dump and restore workers (default: 4).
  --no-start          Migrate the data but leave the app stopped afterwards.
  -y, --yes           Do not prompt for confirmation. Required when there is no
                      terminal to ask on (cron, CI).
  -h, --help          Show this help.

Environment:
  AGENT_COMPASS_HOME         Same as --dir.
  AGENT_COMPASS_COMPOSE_URL  Where to fetch the Postgres 18 docker-compose.yml from
                             (default: the main branch on GitHub). A file:// URL works.

If install.sh was re-run before this script, the new compose file already created
an empty Postgres 18 volume and this script will refuse to overwrite it. The app
has been writing to that volume since, so anything Claude Code sent in the
meantime is only there. If you do not want it, run `docker compose down` in the
install directory (a stopped container still holds the volume), then
`docker volume rm <project>_postgres-data-18`, and run this again.
USAGE
}

logStep() { printf '\033[1;34m==>\033[0m %s\n' "$1"; }
logInfo() { printf '    %s\n' "$1"; }
logWarn() { printf '\033[1;33mwarning:\033[0m %s\n' "$1" >&2; }
fail() { printf '\033[1;31merror:\033[0m %s\n' "$1" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dir)
      [ $# -ge 2 ] || fail "--dir needs a path"
      installationDirectory="$2"; shift 2 ;;
    --project)
      [ $# -ge 2 ] || fail "--project needs a name"
      projectName="$2"; shift 2 ;;
    --volume)
      [ $# -ge 2 ] || fail "--volume needs a volume name"
      sourceVolumeName="$2"; shift 2 ;;
    --jobs)
      [ $# -ge 2 ] || fail "--jobs needs a number"
      parallelJobs="$2"; shift 2 ;;
    --no-start) startStack="false"; shift ;;
    -y|--yes) assumeYes="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1 (try --help)" ;;
  esac
done

case "$parallelJobs" in
  ''|*[!0-9]*|0) fail "--jobs must be a positive number, got '$parallelJobs'" ;;
esac

if [ -z "$sourceVolumeName" ]; then
  sourceVolumeName="${projectName}_${SOURCE_VOLUME_KEY}"
fi
targetVolumeName="${projectName}_${TARGET_VOLUME_KEY}"

# Absolute, because the dump directory below is bind-mounted and docker reads a
# relative `-v` source as a named volume.
[ -d "$installationDirectory" ] \
  || fail "no directory at $installationDirectory - run install.sh first, or pass --dir"
installationDirectory="$(cd "$installationDirectory" && pwd)"

composeFile="$installationDirectory/docker-compose.yml"
previousComposeFile="$composeFile.pre-postgres-18"
# -p outranks the `name:` in the compose file, which lets the same script (and
# its tests) run against a differently named project.
composeCommand=(docker compose -p "$projectName" --project-directory "$installationDirectory" -f "$composeFile")

timestamp="$(date +%Y%m%d-%H%M%S)"
dumpDirectory="$installationDirectory/pg$SOURCE_MAJOR_VERSION-dump-$timestamp"
temporaryContainerName="agent-compass-postgres-upgrade-$$"
downloadedComposeFile="$composeFile.download.$$"

stackStopped="false"
composeSwapped="false"
previousComposeFileAvailable="false"
upgradeCompleted="false"

cleanUpTemporaryContainer() {
  if docker inspect "$temporaryContainerName" >/dev/null 2>&1; then
    # A graceful stop, not `rm -f`: the container holds the only writable
    # handle on the old volume and should shut Postgres down cleanly.
    docker stop -t 120 "$temporaryContainerName" >/dev/null 2>&1 || true
    docker rm -f "$temporaryContainerName" >/dev/null 2>&1 || true
  fi
}

onExit() {
  local exitStatus=$?
  cleanUpTemporaryContainer
  rm -f "$downloadedComposeFile"
  if [ "$exitStatus" -ne 0 ] && [ "$stackStopped" = "true" ] && [ "$upgradeCompleted" != "true" ]; then
    {
      printf '\n\033[1;31mThe upgrade did not finish.\033[0m Your data is safe: the Postgres %s volume\n' "$SOURCE_MAJOR_VERSION"
      printf '%s was never modified.\n\n' "$sourceVolumeName"
      printf 'To go back to the old setup:\n'
      if [ "$composeSwapped" = "true" ] && [ "$previousComposeFileAvailable" = "true" ]; then
        printf '  cp "%s" "%s"\n' "$previousComposeFile" "$composeFile"
      fi
      printf '  docker compose -p %s --project-directory "%s" up -d\n\n' "$projectName" "$installationDirectory"
      printf 'To retry the upgrade, remove the half-restored volume first (if it exists), then re-run:\n'
      printf '  docker compose -p %s --project-directory "%s" down\n' "$projectName" "$installationDirectory"
      printf '  docker volume rm %s\n' "$targetVolumeName"
    } >&2
  fi
}
trap onExit EXIT

# Value of an environment variable as compose resolves it (shell, .env, then the
# compose file's own default) — the one place that knows the ${VAR:-default} rules.
composeEnvironmentValue() {
  "${composeCommand[@]}" config 2>/dev/null \
    | sed -n "s/^[[:space:]]*$1:[[:space:]]*//p" \
    | head -n 1 \
    | sed "s/^[\"']//; s/[\"']\$//"
}

humanReadableSize() {
  # humanReadableSize <kilobytes>
  if [ "$1" -ge 1048576 ]; then
    awk -v kilobytes="$1" 'BEGIN { printf "%.1f GB", kilobytes / 1048576 }'
  else
    printf '%s MB' "$(($1 / 1024))"
  fi
}

waitUntil() {
  # waitUntil <description> <command...>: retry until the command succeeds.
  local description="$1"; shift
  local attempt=1
  while [ "$attempt" -le "$READINESS_ATTEMPTS" ]; do
    if "$@" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$READINESS_INTERVAL_SECONDS"
    attempt=$((attempt + 1))
  done
  fail "$description did not become ready in time"
}

# --- preflight -------------------------------------------------------------

logStep "Checking prerequisites"

command -v docker >/dev/null 2>&1 || fail "Docker was not found on PATH"
docker compose version >/dev/null 2>&1 \
  || fail "Docker Compose v2 is required - 'docker compose version' failed"
docker info >/dev/null 2>&1 \
  || fail "the Docker daemon is not running - start Docker Desktop and try again"
[ -f "$composeFile" ] \
  || fail "no compose file at $composeFile - run install.sh first, or pass --dir"

docker volume inspect "$sourceVolumeName" >/dev/null 2>&1 \
  || fail "volume $sourceVolumeName does not exist - check --project / --volume (see 'docker volume ls')"

# A 16 volume keeps PG_VERSION at its root; the 18 image keeps its data in a
# versioned subdirectory (<major>/docker), so look there too to recognise one.
sourceVersion="$(docker run --rm --entrypoint sh -v "$sourceVolumeName":/pgdata:ro "$SOURCE_IMAGE" \
  -c "cat /pgdata/PG_VERSION 2>/dev/null || cat /pgdata/$TARGET_MAJOR_VERSION/docker/PG_VERSION 2>/dev/null" \
  | tr -d '[:space:]' || true)"
case "$sourceVersion" in
  "$SOURCE_MAJOR_VERSION") ;;
  "$TARGET_MAJOR_VERSION") logInfo "$sourceVolumeName is already Postgres $TARGET_MAJOR_VERSION - nothing to do"; exit 0 ;;
  '') fail "$sourceVolumeName does not look like a Postgres data volume (no PG_VERSION at its root)" ;;
  *) fail "$sourceVolumeName holds Postgres $sourceVersion; this script only upgrades $SOURCE_MAJOR_VERSION to $TARGET_MAJOR_VERSION" ;;
esac
logInfo "old volume        : $sourceVolumeName (Postgres $sourceVersion)"

if docker volume inspect "$targetVolumeName" >/dev/null 2>&1; then
  fail "volume $targetVolumeName already exists, so it was either upgraded already or a run was interrupted. \
Refusing to overwrite it. If it holds nothing you want (a re-run of install.sh creates an empty one, but the app has \
been writing to it since, so check first), run 'docker compose -p $projectName --project-directory \"$installationDirectory\" down' \
(a stopped container still holds the volume), then 'docker volume rm $targetVolumeName', and run this again."
fi
logInfo "new volume        : $targetVolumeName"

# The compose file is the rollback target, so only a Postgres 16 one is worth
# keeping. After an interrupted run or a re-run of install.sh it already pins 18,
# and copying it over the backup would replace the last Postgres 16 file with a
# Postgres 18 one.
composePinsSourceImage="false"
if grep -q "image: $SOURCE_IMAGE\$" "$composeFile"; then
  composePinsSourceImage="true"
fi
if [ "$composePinsSourceImage" = "true" ] || [ -f "$previousComposeFile" ]; then
  previousComposeFileAvailable="true"
else
  logWarn "$composeFile does not pin $SOURCE_IMAGE and there is no $previousComposeFile, so no Postgres $SOURCE_MAJOR_VERSION \
compose file will be kept for a rollback (install.sh probably replaced it). The old volume and the dump are still kept."
fi

logInfo "pulling $TARGET_IMAGE now, so a network problem cannot strike half way through"
docker pull "$TARGET_IMAGE" >/dev/null || fail "could not pull $TARGET_IMAGE"

# The replacement compose file is fetched and checked BEFORE anything is stopped:
# main only carries Postgres 18 once this change is released, and finding out
# after the dump would waste a very long step.
logInfo "fetching the Postgres $TARGET_MAJOR_VERSION compose file from $COMPOSE_FILE_URL"
if ! curl -fsSL --retry 3 --retry-delay 1 -o "$downloadedComposeFile" "$COMPOSE_FILE_URL"; then
  fail "could not download $COMPOSE_FILE_URL"
fi
grep -q '^services:' "$downloadedComposeFile" || fail "downloaded file does not look like a compose file"
grep -q "image: $TARGET_IMAGE\$" "$downloadedComposeFile" \
  || fail "$COMPOSE_FILE_URL does not pin $TARGET_IMAGE - nothing was changed (set AGENT_COMPASS_COMPOSE_URL to the right file)"
grep -q "^  $TARGET_VOLUME_KEY:" "$downloadedComposeFile" \
  || fail "$COMPOSE_FILE_URL does not declare the $TARGET_VOLUME_KEY volume - nothing was changed"

postgresUser="$(composeEnvironmentValue POSTGRES_USER)"
postgresDatabase="$(composeEnvironmentValue POSTGRES_DB)"
postgresUser="${postgresUser:-postgres}"
postgresDatabase="${postgresDatabase:-coding_agent_tuning}"

dataSizeKilobytes="$(docker run --rm --entrypoint du -v "$sourceVolumeName":/pgdata:ro "$SOURCE_IMAGE" -sk /pgdata | cut -f1)"
freeKilobytes="$(df -Pk "$installationDirectory" | awk 'NR==2 {print $4}')"

logStep "Planned changes"
logInfo "database          : $postgresDatabase (user $postgresUser)"
logInfo "old data size     : $(humanReadableSize "$dataSizeKilobytes")"
logInfo "dump written to   : $dumpDirectory"
logInfo "                    (typically a third to a half of the data size; $(humanReadableSize "$freeKilobytes") free there)"
logInfo "                    the new volume needs about the old data size again, inside Docker's own disk"
logInfo "steps             : stop the stack, dump, switch to Postgres $TARGET_MAJOR_VERSION, restore, analyze, start the stack"
if [ "$previousComposeFileAvailable" = "true" ]; then
  logInfo "left untouched    : $sourceVolumeName, the dump, and $previousComposeFile"
else
  logInfo "left untouched    : $sourceVolumeName and the dump"
fi
if [ "$freeKilobytes" -lt $((dataSizeKilobytes / 3)) ]; then
  logWarn "less than a third of the data size is free where the dump goes - it may not fit"
fi

# Ask on the terminal rather than stdin: under `curl ... | bash` stdin is the
# script itself, and skipping the question there would stop the stack unasked.
if [ "$assumeYes" != "true" ]; then
  if { : </dev/tty; } 2>/dev/null; then
    printf 'Proceed? The stack is unavailable while this runs. [Y/n] '
    read -r confirmation </dev/tty
    case "$confirmation" in
      ''|y|Y|yes|YES) ;;
      *) echo "Aborted."; exit 0 ;;
    esac
  else
    fail "there is no terminal to ask for confirmation on - nothing was changed. Re-run with --yes to proceed"
  fi
fi

# --- dump from the old volume ----------------------------------------------

logStep "Stopping the stack"
stackStopped="true"
# A long timeout so Postgres can finish its shutdown checkpoint: the default 10
# seconds is a SIGKILL on a large database, and the dump would then start from
# crash recovery.
"${composeCommand[@]}" stop -t 300 app postgres

# Two postmasters on one data directory is how a database gets corrupted, and
# the volume may have been attached under a name compose does not manage.
if [ -n "$(docker ps -q --filter "volume=$sourceVolumeName")" ]; then
  fail "a running container still uses $sourceVolumeName - stop it, then run this again"
fi

logStep "Starting a temporary Postgres $SOURCE_MAJOR_VERSION on the old volume"
# Unix socket only (listen_addresses is empty): nothing can reach it from the
# network, and local connections are trust-authenticated, so no password is needed.
mkdir -p "$dumpDirectory"
docker run -d --name "$temporaryContainerName" --shm-size=1g \
  -v "$sourceVolumeName":/var/lib/postgresql/data \
  -v "$dumpDirectory":/dump \
  "$SOURCE_IMAGE" postgres -c listen_addresses= >/dev/null
waitUntil "the temporary Postgres $SOURCE_MAJOR_VERSION" \
  docker exec "$temporaryContainerName" pg_isready -U "$postgresUser" -d "$postgresDatabase"

# Exact counts taken from the source, compared with the restored copy at the
# end. Tables that do not exist yet (an install whose app never booted) skip it.
COUNT_SQL="SELECT (SELECT count(*) FROM flyway_schema_history) || ',' || (SELECT count(*) FROM log_records) || ',' \
|| (SELECT count(*) FROM spans) || ',' || (SELECT count(*) FROM metric_points)"
expectedCounts="$(docker exec "$temporaryContainerName" psql -U "$postgresUser" -d "$postgresDatabase" -tAc "$COUNT_SQL" 2>/dev/null || true)"
if [ -n "$expectedCounts" ]; then
  logInfo "rows (migrations,logs,spans,metric points): $expectedCounts"
else
  logWarn "could not count the source tables - the restored copy will not be compared"
fi

logStep "Dumping the database (this is the slow step)"
# Directory format, so workers dump tables in parallel and the restore can run in
# parallel too; lz4 because the default gzip is what makes a large dump crawl.
# toc.dat is written last, so a dump that is missing it did not finish.
docker exec "$temporaryContainerName" pg_dump -U "$postgresUser" -d "$postgresDatabase" \
  --format=directory --jobs="$parallelJobs" --compress=lz4 --file=/dump
docker exec "$temporaryContainerName" pg_restore --list /dump >/dev/null \
  || fail "the dump at $dumpDirectory could not be read back - nothing else was changed"
# The container ran pg_dump as root; hand the files back to whoever ran this script.
docker exec "$temporaryContainerName" chown -R "$(id -u):$(id -g)" /dump \
  || logWarn "could not change the dump's owner - you may need sudo to delete $dumpDirectory later"
logInfo "dump verified: $(du -sh "$dumpDirectory" | cut -f1) in $dumpDirectory"

cleanUpTemporaryContainer

# --- switch to Postgres 18 -------------------------------------------------

logStep "Switching the compose file to Postgres $TARGET_MAJOR_VERSION"
if [ "$composePinsSourceImage" = "true" ]; then
  cp -p "$composeFile" "$previousComposeFile"
  logInfo "previous compose file kept as $previousComposeFile"
elif [ "$previousComposeFileAvailable" = "true" ]; then
  logInfo "the Postgres $SOURCE_MAJOR_VERSION compose file saved earlier is still at $previousComposeFile"
fi
mv "$downloadedComposeFile" "$composeFile"
composeSwapped="true"

logStep "Starting Postgres $TARGET_MAJOR_VERSION on the new volume"
"${composeCommand[@]}" up -d postgres
newPostgresContainer="$("${composeCommand[@]}" ps -q postgres)"
[ -n "$newPostgresContainer" ] || fail "the new postgres container did not start"

# Clients join the server's network namespace and connect over loopback: that is
# trust-authenticated in the official image, and — unlike the compose healthcheck,
# which also passes during the image's init phase — it only answers once the final
# server is up.
runTargetClient() {
  docker run --rm --network "container:$newPostgresContainer" -e "PGOPTIONS=-c maintenance_work_mem=$RESTORE_MAINTENANCE_WORK_MEMORY" \
    "$@"
}
waitUntil "Postgres $TARGET_MAJOR_VERSION" \
  runTargetClient "$TARGET_IMAGE" pg_isready -h 127.0.0.1 -U "$postgresUser" -d "$postgresDatabase"

logStep "Restoring into Postgres $TARGET_MAJOR_VERSION (index rebuilds make this slow too)"
runTargetClient -v "$dumpDirectory":/dump:ro "$TARGET_IMAGE" \
  pg_restore -h 127.0.0.1 -U "$postgresUser" -d "$postgresDatabase" --no-owner --jobs="$parallelJobs" /dump

logStep "Rebuilding planner statistics"
# A dump carries none, and this schema's query plans depend on them.
runTargetClient "$TARGET_IMAGE" psql -h 127.0.0.1 -U "$postgresUser" -d "$postgresDatabase" -c 'ANALYZE'

if [ -n "$expectedCounts" ]; then
  restoredCounts="$(runTargetClient "$TARGET_IMAGE" psql -h 127.0.0.1 -U "$postgresUser" -d "$postgresDatabase" -tAc "$COUNT_SQL")"
  if [ "$restoredCounts" != "$expectedCounts" ]; then
    fail "row counts differ after the restore (was $expectedCounts, now $restoredCounts). \
Your old volume and the dump are intact; see the recovery steps below"
  fi
  logInfo "row counts match: $restoredCounts"
fi

# --- run -------------------------------------------------------------------

if [ "$startStack" = "true" ]; then
  logStep "Starting the stack"
  "${composeCommand[@]}" up -d

  applicationPort="18080"
  environmentFile="$installationDirectory/.env"
  if [ -f "$environmentFile" ] && grep -q '^APP_PORT=' "$environmentFile"; then
    applicationPort="$(grep '^APP_PORT=' "$environmentFile" | tail -n 1 | cut -d= -f2)"
  fi
  if command -v curl >/dev/null 2>&1; then
    logInfo "waiting for the dashboard on port $applicationPort"
    ready="false"
    attempt=1
    while [ "$attempt" -le 60 ]; do
      if curl -fsS -o /dev/null --max-time 2 "http://localhost:$applicationPort/api/sessions/summary?minutes=1"; then
        ready="true"
        break
      fi
      sleep 2
      attempt=$((attempt + 1))
    done
    if [ "$ready" = "true" ]; then
      logInfo "up at http://localhost:$applicationPort"
    else
      logWarn "the app did not answer within ~2 minutes; check: docker compose -p $projectName --project-directory $installationDirectory logs -f app"
    fi
  fi
fi

upgradeCompleted="true"

if [ "$startStack" != "true" ]; then
  logInfo "the app was left stopped (--no-start); start it with: docker compose -p $projectName --project-directory $installationDirectory up -d"
fi

if [ "$previousComposeFileAvailable" = "true" ]; then
  keptComposeLine="  Old compose   $previousComposeFile"
  restoreComposeLine="  cp \"$previousComposeFile\" \"$composeFile\""
else
  keptComposeLine="  Old compose   (none was kept)"
  restoreComposeLine="  # put a Postgres $SOURCE_MAJOR_VERSION docker-compose.yml back at \"$composeFile\" (none was saved)"
fi

cat <<SUMMARY

Done. Postgres is now on $TARGET_MAJOR_VERSION.

Kept for rollback, until you are happy with the result:

  Old volume    $sourceVolumeName
  Dump          $dumpDirectory
$keptComposeLine

Once you have checked the dashboard, reclaim the space with:
  docker volume rm $sourceVolumeName
  rm -rf "$dumpDirectory"

To go back to Postgres $SOURCE_MAJOR_VERSION instead (anything ingested since is not carried back):
  docker compose -p $projectName --project-directory "$installationDirectory" down
$restoreComposeLine
  docker compose -p $projectName --project-directory "$installationDirectory" up -d

SUMMARY
