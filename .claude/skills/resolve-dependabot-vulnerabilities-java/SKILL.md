---
name: resolve-dependabot-vulnerabilities-java
description: 'Resolve open GitHub Dependabot alerts for backend/pom.xml (Maven ecosystem): bump the affected dependency via its properties entry, a dependencyManagement override, or the Spring Boot BOM version, then validate with ./mvnw clean verify. Skips maven-compiler-plugin (hard-pinned) and reports it instead of touching it. Never commits. Callable standalone or dispatched from /resolve-dependabot-vulnerabilities.'
argument-hint: '[package,package,...]'
---

# Resolve Dependabot Vulnerabilities — Java (Maven)

Callable standalone (a user can invoke this directly) or dispatched from
`/resolve-dependabot-vulnerabilities`. Fetches its own alert data — don't assume it's been
pre-filtered by a caller.

Take the raw text after the command as an optional comma-separated package-name selector (e.g.
`mapstruct,commons-io`). Split on commas, trim each entry, drop empty entries. An empty selector
means "resolve every open Maven alert."

## 1. Fetch open Maven alerts

```sh
OWNER_REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api repos/$OWNER_REPO/dependabot/alerts --method GET -F state=open -F ecosystem=maven --paginate \
  -q '.[] | {number, package: .dependency.package.name, manifest: .dependency.manifest_path,
             severity: .security_advisory.severity, summary: .security_advisory.summary,
             patched_version: .security_vulnerability.first_patched_version.identifier}'
```

**`--method GET` is required.** `gh api` silently switches to POST once any `-F`/`-f` flag is
present unless the method is pinned explicitly — POSTing to this (GET-only) endpoint returns a
bare `404 Not Found`, not an auth or scope error, so it's easy to misdiagnose.

**Use `.security_vulnerability.first_patched_version`, never
`.security_advisory.vulnerabilities[...]`.** The latter is the *advisory's* full list of every
vulnerable range it covers across all major-version lines of the package — indexing into it
arbitrarily can pick a patched version that doesn't even apply to the version actually installed
here. The alert's top-level `security_vulnerability` field is the one range GitHub actually matched
against this repo's `backend/pom.xml`, and its `first_patched_version` is the version that clears
*this* alert.

Filter to `manifest == "backend/pom.xml"` (defensive — this repo has only one Maven manifest, but
don't act on an alert against a manifest that isn't the real one).

Maven package names come back as `groupId:artifactId` (e.g. `org.springframework:spring-web`), not
a bare artifact name. If a selector was given, match each entry against **both** the full
`groupId:artifactId` string and the bare artifactId after the colon, so a user typing
`spring-web` or the full coordinate both work.

If the filtered list is empty, report **"No open Maven alerts (matching the selector, if any) —
nothing to do."** and stop. (This is the expected outcome today — there are 0 open Maven alerts in
this repo.)

**Group by package (`groupId:artifactId`) before editing anything.** The same dependency can carry
more than one open alert (different CVEs with different minimum patched versions). Collapse all
alerts for the same package into one group and take the **highest** `patched_version` across the
group as the single target, so one edit clears every contributing alert.

## 2. For each remaining package group, in order

Read `backend/pom.xml` before editing anything, and capture the exact pre-edit value you're about
to change — you need it for the report, and to revert cleanly on failure.

1. **Hard-pin special case.** If the artifactId is `maven-compiler-plugin`, do **not** edit
   anything. Record it as `SKIPPED (hard-pinned to 3.13.0 — backend/CLAUDE.md: 3.14+/3.15 regress
   Lombok+MapStruct annotation processing; needs manual review)`. Move to the next alert.

2. **Decide the mechanism** by inspecting how the artifact's version is currently controlled in
   `backend/pom.xml`:
   - **Property-driven** — the dependency's `<version>` is `${xxx.version}` and that property is
     declared under `<properties>` (e.g. `lombok.version`, `mapstruct.version`,
     `otel.proto.version`, `springdoc.version`, `testcontainers.version`). Mechanism =
     **property-bump**: edit the property's value to the alert's `patched_version` (exact pin, no
     ranges — matches this repo's pinning style).
   - **BOM-managed, no explicit version anywhere in pom.xml** (most `spring-boot-starter-*`,
     `flyway`) — its version comes solely from the imported `spring-boot-dependencies` BOM in
     `<dependencyManagement>`. Mechanism = **dependencyManagement override**: add (or update, if
     already present) an explicit `<dependency>` entry for that exact `groupId:artifactId` under
     this pom's own `<dependencyManagement><dependencies>`, with
     `<version>{patched_version}</version>`. A project's own `dependencyManagement` entries always
     take precedence over versions arriving through an imported BOM, regardless of declaration
     order — nothing about the BOM import itself needs to change.
   - **Fallback — broad Spring Boot bump**: only use this when the alert is against Spring Boot
     itself, or several BOM-managed artifacts are flagged together pointing at the same root
     cause. Bump the `spring.boot.version` property instead of stacking many individual overrides.
     Default to the isolated per-artifact override for a single alert — smaller blast radius.

3. Make the edit to `backend/pom.xml` with the Edit tool.

4. **Validate**: `cd backend && ./mvnw clean verify` (includes Testcontainers integration tests —
   Docker must be running).

5. **On success**: mark `RESOLVED (old version -> new version, mechanism used)`. Keep the edit.

6. **On failure**: revert **only this dependency's change** — restore the exact pre-edit value you
   captured in step 2 via the Edit tool (the property's old value, or removing the
   `dependencyManagement` override entry you added). Do not use `git checkout -- backend/pom.xml`
   if any earlier dependency in this same run already succeeded and is still applied — that would
   wipe those too. A file-level `git checkout --` is only safe as a shortcut when this is the first
   and only edit made to the file so far in the run. Mark
   `FAILED-VALIDATION-REVERTED (failing command/test named)`.

## 3. Report

One table, one row per alert processed:

| Package | Old Version | New Version | Mechanism | Status | Notes |
|---|---|---|---|---|---|

Statuses: `RESOLVED`, `SKIPPED (reason)`, `FAILED-VALIDATION-REVERTED (reason)`. End with a
one-line summary count (e.g. "3 resolved, 1 skipped, 0 failed"). Never `git add`/`commit`/`push` —
stop after the report and leave the working tree for the user to review.
