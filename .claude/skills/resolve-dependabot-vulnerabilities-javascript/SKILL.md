---
name: resolve-dependabot-vulnerabilities-javascript
description: 'Resolve open GitHub Dependabot alerts for frontend/package.json (npm ecosystem): bump a direct dependency with yarn up -E, or pin a transitive dependency via the resolutions field plus yarn install, then validate with typecheck/lint/test/build. Never commits. Callable standalone or dispatched from /resolve-dependabot-vulnerabilities.'
argument-hint: '[package,package,...]'
---

# Resolve Dependabot Vulnerabilities — JavaScript (npm)

Callable standalone (a user can invoke this directly) or dispatched from
`/resolve-dependabot-vulnerabilities`. Fetches its own alert data — don't assume it's been
pre-filtered by a caller.

Take the raw text after the command as an optional comma-separated package-name selector (e.g.
`nanoid,tar`). Split on commas, trim each entry, drop empty entries. An empty selector means
"resolve every open npm alert."

## 1. Fetch open npm alerts

```sh
OWNER_REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api repos/$OWNER_REPO/dependabot/alerts --method GET -F state=open -F ecosystem=npm --paginate \
  -q '.[] | {number, package: .dependency.package.name, manifest: .dependency.manifest_path,
             severity: .security_advisory.severity, summary: .security_advisory.summary,
             patched_version: .security_vulnerability.first_patched_version.identifier}'
```

**`--method GET` is required.** `gh api` silently switches to POST once any `-F`/`-f` flag is
present unless the method is pinned explicitly — POSTing to this (GET-only) endpoint returns a
bare `404 Not Found`, not an auth or scope error, so it's easy to misdiagnose.

**Use `.security_vulnerability.first_patched_version`, never
`.security_advisory.vulnerabilities[...]`.** The latter is the *advisory's* full list of every
vulnerable range it covers across all major-version lines (e.g. nanoid's advisories list both a
`>=4.0.0, <5.1.6` range and a `<3.3.18` range in the same array) — indexing into it arbitrarily
picks a patched version that may not even apply to the version actually installed here. The
alert's top-level `security_vulnerability` field is the one range GitHub actually matched against
this repo's lockfile, and its `first_patched_version` is the version that clears *this* alert.

**Filter to `manifest == "frontend/yarn.lock"` only.** This repo also carries a stray
`frontend/package-lock.json` that AGENTS.md calls out as legacy and unused (Yarn Berry is
canonical — see `project_frontend_yarn_canonical`). Dependabot's dependency graph still parses it
and raises a duplicate alert against it for every package that also appears in `yarn.lock`.
Resolving those would mean editing a lockfile nothing actually installs from — skip any alert
whose `manifest` isn't `frontend/yarn.lock` and record it as
`SKIPPED (manifest is the legacy, unused frontend/package-lock.json — not a real install path)`.

If a selector was given, keep only alerts whose `package` **exactly** matches an entry (npm names
may be scoped, e.g. `@babel/core` — match the full string, no partial matching).

If the filtered list is empty, report **"No open npm alerts (matching the selector, if any) —
nothing to do."** and stop.

**Group by package name before editing anything.** The same installed package can carry more than
one open alert (different CVEs with different minimum patched versions) or resolve to more than
one version simultaneously in `yarn.lock` (two dependents pinning incompatible ranges, e.g. one on
`postcss@8.5.15` and another on `postcss@8.5.16`, each with its own line in the lockfile). Collapse
all alerts for the same package into one group and take the **highest** `patched_version` across
the group as the single target — this avoids editing the same `resolutions`/`package.json` entry
twice in the same run and re-running the full validation suite redundantly, and guarantees the one
edit clears every contributing alert at once.

## 2. For each remaining package group, in order

Read `frontend/package.json` before editing. Capture the pre-edit state you're about to change —
the current pinned version if it's a direct dependency, or "absent" if there's no existing
`resolutions` entry for it yet. For a transitive package, also check `frontend/yarn.lock` for how
many distinct resolved versions currently exist (`grep '^"<package>@npm:' yarn.lock`) — it's normal
for two dependents to have pinned incompatible ranges of the same package (e.g. one on
`postcss@8.5.15`, another on `postcss@8.5.16`); note all of them as the "old" versions, since one
`resolutions` entry collapses every instance onto the single new version.

1. **Direct dependency?** Check whether the package name is a key under `dependencies` or
   `devDependencies` in `frontend/package.json`.
   - **Yes (direct)** — mechanism = `yarn up`:
     `cd frontend && yarn up <package>@<patched_version> -E`
     (`-E` keeps this repo's exact-pin convention — no `^`/`~`; updates `package.json` and
     `yarn.lock` together.)
   - **No (transitive)** — mechanism = **`resolutions`**: edit `frontend/package.json` to add a
     top-level `"resolutions"` object if one doesn't exist yet (place it alongside
     `dependencies`/`devDependencies`), with `"<package>": "<patched_version>"`. If a resolutions
     entry for that package already exists, update its value. Then run
     `cd frontend && yarn install` to regenerate `yarn.lock` consistently. Never hand-edit
     `yarn.lock` directly.

2. **Validate**, in this order, stopping at the first failure:
   `cd frontend && yarn typecheck`, then `yarn lint`, then `yarn test --run`, then `yarn build`.

3. **On success**: mark `RESOLVED (old version -> new version, mechanism)`. Keep the edits
   (`package.json` + `yarn.lock`).

4. **On failure**: revert **only this package's change**.
   - If it was a `resolutions` edit: remove that one key from `resolutions` (or restore its prior
     value) via Edit, then re-run `yarn install` to regenerate `yarn.lock` back to a state
     consistent with whatever earlier successful edits remain applied.
   - If it was a `yarn up`: run `yarn up <package>@<old_version> -E` to put it back (or edit the
     version back and `yarn install`).
   - Only use `git checkout -- frontend/package.json frontend/yarn.lock` as a shortcut when this is
     the first and only package processed so far in this run — otherwise it would also discard
     earlier successful changes in the same run.
   - Mark `FAILED-VALIDATION-REVERTED (failing command named)`.

## 3. Report

One table, one row per alert processed:

| Package | Old Version | New Version | Mechanism | Status | Notes |
|---|---|---|---|---|---|

Statuses: `RESOLVED`, `SKIPPED (reason)`, `FAILED-VALIDATION-REVERTED (reason)`. End with a
one-line summary count. Never `git add`/`commit`/`push`. Finish by reminding the user: "Nothing was
committed — review `git diff frontend/package.json frontend/yarn.lock` and commit yourself."
