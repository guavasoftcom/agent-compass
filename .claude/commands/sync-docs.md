---
description: Check code changes on this branch against the repo's markdown docs and update any that are stale or missing coverage.
argument-hint: [optional: path or topic to focus on]
---

You are auditing the repo's markdown documentation against the code changes on the current branch and updating whatever has drifted.

## Scope of changes to consider

1. Uncommitted changes in the working tree (`git status`, `git diff`, `git diff --staged`).
2. Commits on the current branch since it diverged from `main` (`git log main..HEAD --stat`, `git diff main...HEAD`).

If the user passed an argument, treat it as a hint to narrow the focus (a path, a feature name, or a topic). Otherwise audit broadly.

## Markdown files to consider

Audit these files; only edit the ones whose claims are actually affected by the diff:

- `README.md` — top-level project overview, quickstart, endpoints, frontend page list
- `AGENTS.md` — cross-cutting repo conventions (root)
- `backend/CLAUDE.md` — backend naming / Java style / web layer / data conventions
- `frontend/CLAUDE.md` — frontend module layout, container/presentational split, data fetching, charts/grids, routing, style/lint conventions
- `docs/dashboard-roadmap.md` — phase 0/1/2 dashboard plan; update only when a dashboard's status or shape changes
- `docs/frontend-folder-structure.md` — frontend layout reference

Skip agent / chatmode definitions under `.claude/`.

## What counts as drift

Look for, and fix:

- **Stale facts** — a doc claims something the code no longer does (renamed file, removed endpoint, changed command, replaced library, deleted tile/component).
- **Missing coverage of new public surface** — new endpoints, new top-level components/tiles, new CLI commands, new env vars, new conventions worth other contributors knowing.
- **Layout drift** — directory trees / barrel-export lists in `AGENTS.md` files that no longer match the filesystem.
- **Convention drift** — a new pattern was introduced (or an old one was abandoned) and the doc still describes the old way.
- **Broken internal references** — links to files that have moved or been deleted.

Do **not** treat as drift:

- Minor refactors that don't change documented behavior.
- New tests that follow existing test conventions.
- One-off bug fixes that don't change public surface.
- Anything already covered accurately by the existing prose.

## How to work

1. Gather the diff (uncommitted + branch commits). If the working tree isn't a git repo, or there's no `main` branch to diff against, fall back to auditing the current working-tree state plus recent conversational context for what was just changed. Only stop and tell the user there's nothing to audit when there's genuinely no signal to work from.
2. Read the candidate markdown files in full.
3. For each file, list the specific drift you found (or "no changes needed") with one-line justifications grounded in the diff. Cite file paths / commit subjects, not vague "the code changed".
4. Show the user the proposed updates as a short plan **before editing** — bulleted, per-file. Wait for approval unless the user has said to proceed without confirmation.
5. After approval, apply edits with `Edit`. Keep the existing tone, heading style, and indentation of each file. Be terse — match what's already there. Don't add changelog-style entries; rewrite the affected prose instead.
6. End with a one-line summary of which files were touched.

## Style guardrails for the edits

- Preserve the existing voice and section structure of each file.
- Don't introduce emojis unless the file already uses them.
- Don't add "as of <date>" or "recently we …" — docs describe current state, not history.
- Don't reference PRs, branch names, or the current task. Docs outlive the work.
- If a doc had a code/dir tree that's now wrong, regenerate the affected lines from `ls` output rather than guessing.
- If you're tempted to add a long new section, reconsider — most updates are sentence- or paragraph-level.
