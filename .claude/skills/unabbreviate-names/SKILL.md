---
name: unabbreviate-names
description: 'Review defined names (variables, constants, functions, parameters, types, properties) for abbreviations and report each one with its meaning and a suggested spelled-out rename. Report-first; applies renames only on request. Use when asked to find/clean up abbreviated names, enforce expressive naming, or "un-abbreviate" code.'
argument-hint: '[file path | file:startLine-endLine | pasted code]'
---

# Un-abbreviate names

Find abbreviated identifiers and report them with full-name suggestions. This enforces the
project's **Expressive names** rule (see [frontend/CLAUDE.md](../../../frontend/CLAUDE.md) and
the repo-wide backend rule): names should be full, intent-revealing words — `formatGranularity`
not `granLabel`, `serviceName` not `svc`. Applies to both `frontend/` (TS/TSX) and `backend/`
(Java).

This is a **review tool first**. Produce the report, then rename only what the user approves.

## Scope (what to read)

Resolve the target in this order — stop at the first that matches:
1. **An argument passed with the command.** The text after `/unabbreviate-names` may be any of:
   - A **file path** (e.g. `frontend/src/api.ts`) — Read and review the whole file.
   - A **path with a line range** (`path:42-80`, `path#L42-L80`, or `path:42`) — Read and review
     only those lines, using surrounding context to infer meaning.
   - **Pasted code** (multiple lines, snippet, or an identifier) — review that text directly; no file
     to read. If it's a bare identifier, treat it as "what would you call this?" and answer for it.
   When several paths/snippets are given, review each. If a path doesn't resolve, say so and fall
   through to the next applicable rule rather than guessing.
2. An IDE selection, if one is attached and no argument was given — review only those lines.
3. Otherwise the file named in the user's message (or the currently open file).
4. Otherwise the changed files on the branch (`git diff --name-only main...`); if that's large,
   list the files and ask which to review rather than dumping everything.

Within the target, examine every **defined** name: `const`/`let`/`var`, function and arrow-function
names, parameters, destructured locals, type/interface/enum names and their members, class fields,
and module-level constants. Infer each name's meaning from how it is used (its initializer, its
call sites, the type it holds) so the suggestion is accurate, not generic.

## What counts as an abbreviation (flag these)

- Truncations / dropped vowels: `cfg`, `btn`, `msg`, `calc`, `fmt`, `attr`, `dur`, `req`, `res`,
  `err`, `errs`, `cnt`, `ct`, `desc`, `repo` (when it means repository in a var), `svc`.
- Cryptic shorthand: `lp`, `erate`, `latPct`, `okH`/`erH`, `tdSx`.
- Math/letter shorthand used as a real value (not a loop index): capital `N`, `n` (when it's a
  count, not an index), `v`, `d`, `h`, `x`/`y` only when they aren't genuine coordinates.
- Vague single words that hide intent: `data`/`raw`/`tmp`/`val` when a specific name fits
  (`rawP95Values`, `pendingDraft`).

For each, suggest the fullest natural name (`errors`, `errorRate`, `bucketCount`, `serviceName`,
`formatAxisLabel`, `isSelected`, `dotColor`). When a name pairs with another, suggest them as a
set so they stay consistent (`rawP95Values` ↔ `smoothedP95Values`).

## Carve-outs (do NOT flag)

These are sanctioned by the conventions — leave them:
- Standard index-loop variables `i`, `j`, `k`.
- Generic type parameters `T`, `K`, `V`, etc.
- Single-expression lambda/callback params, including the idiomatic MUI theme arg
  `sx={{ color: (t) => t.palette… }}` and the event arg `(e) => …`, and `e` in `catch`.
- Established, universally-clearer acronyms: `id`, `url`, `uri`, `api`, `http`, `html`, `css`,
  `json`, `sql`, `db`, `uuid`, `iso` (for ISO timestamps), domain percentiles `p50`/`p95`.
  (If the user explicitly wants these spelled out too, do it — their call overrides this list.)

When unsure whether something is "abbreviated enough" to flag, include it but mark it **(borderline)**
so the user decides.

## Report format

Group by file (for pasted code with no file, use a single "snippet" group and a line number within
the snippet as the location). One table:

| Name | Location | Kind | Means | Suggested |
|------|----------|------|-------|-----------|
| `svc` | `TraceFacetRailView.tsx:80` | local | service name from `serviceForValue` | `serviceName` |
| `ct` | `TraceHistogramView.tsx:79` | property | per-series count | `count` |

End with a one-line summary (e.g. "7 abbreviations across 2 files; 2 borderline"). Then ask whether
to apply all, some, or none. Do not edit during the report.

## Applying a rename (only when asked)

- Rename **every** occurrence in scope: declaration + all use sites. For an exported name, search
  the whole repo (`grep -rn`) and update all importers and any docs/CLAUDE.md references.
- **Substring safety — the main hazard.** Never blind `replace_all` on:
  - short tokens (`n`, `v`, `N`, `b`) — they match inside other identifiers; edit each site with
    enough surrounding context instead.
  - a token that is a substring of a longer word in the file. Example: replacing `mono` →
    `monospace` also corrupts the existing word `monospace` into `monospacespace`, and the comment
    `// smoothed` into `// smoothedP95Valuesed`. Check with `grep -n` first; if the token appears
    inside larger words or strings, do targeted edits.
- After editing, run the project's checks and confirm clean:
  - frontend: `cd frontend && yarn typecheck && yarn lint`
  - backend: `cd backend && mvn -q compile` (or the project's build)
- Keep paired names consistent and update the matching prop/type so the behavior/view contracts
  still line up.

## Notes

- Suggestions are proposals — when the user picks a different name (e.g. `dotType` over `dotKind`),
  use theirs.
- This skill does not change logic, only names. If a name is abbreviated *and* the surrounding code
  could be simplified, mention it separately; don't bundle a refactor into a rename.
