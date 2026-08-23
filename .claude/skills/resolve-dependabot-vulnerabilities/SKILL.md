---
name: resolve-dependabot-vulnerabilities
description: 'Fetch open GitHub Dependabot vulnerability alerts via the gh CLI and dispatch them to the java or javascript resolver sub-skill (or both) to patch affected dependencies and validate the fix. Use when asked to resolve, fix, patch, or clear Dependabot alerts, CVEs, or vulnerable dependencies in this repo.'
argument-hint: '[java|javascript|js] [package,package,...]'
---

# Resolve Dependabot Vulnerabilities

Thin argument parser and dispatcher. Does **not** call `gh` itself — each sub-skill fetches and
filters its own ecosystem's alerts, so it also works when invoked directly by the user, skipping
this orchestrator entirely.

## Parse arguments

Take the raw text after `/resolve-dependabot-vulnerabilities` (may be empty).

1. Empty text -> `ecosystems = [java, javascript]`, `selector = ""` (resolve everything).
2. Otherwise split off the first whitespace-delimited token and inspect it case-insensitively:
   - `java` -> `ecosystems = [java]`; `selector` = the remaining text after that token, trimmed.
   - `javascript` or `js` -> `ecosystems = [javascript]`; `selector` = the remaining text after
     that token, trimmed.
   - anything else -> no language was given; `ecosystems = [java, javascript]` and the **entire**
     raw text is the selector (a comma-separated package-name list, e.g. `nanoid,tar`).
3. Normalize the selector: split on commas, trim each entry, drop empty entries. Forward it as-is
   (even if it ends up empty, meaning "all alerts in scope") to whichever sub-skill(s) run.

## Dispatch

Invoke **sequentially**, never in parallel — each sub-skill may modify the working tree and runs
its own validation suite, so let one finish and report before starting the next:

- if `java` in ecosystems: `Skill({skill: "resolve-dependabot-vulnerabilities-java", args: selector})`
- if `javascript` in ecosystems: `Skill({skill: "resolve-dependabot-vulnerabilities-javascript", args: selector})`

## Report

Combine whichever sub-skill report(s) ran into one final summary for the user — concatenate their
per-dependency tables under a `## Java` / `## JavaScript` heading as applicable. Never `git add`,
`git commit`, `git push`, or open a PR — the sub-skills already stop short of that; just present
the combined report and stop.
