# Handoff: Reliability Tab — 3 Card Redesigns

## Overview
Reworks three cards on the Tool Usage → Reliability tab to hold up against real production data (the original mockup used a small curated dataset that hid problems only visible at real scale/skew). No changes to the stat strip cards or the "Calls"/"Skills & Agents"/"Denials" tabs.

## About the Design Files
`Aurora Reliability Mockup v2.html` is an **HTML/CSS design reference**, not production code to ship as-is. Recreate this behavior in the target codebase's existing stack (React/MUI) using its component and state patterns — port the layout, values, and thresholds described below, not the raw markup.

## Fidelity
**High-fidelity.** Colors, spacing, typography are final; implement using the codebase's existing design tokens where equivalents exist.

## Changes

### 1. "Tools by failure rate" — collapse the zero-failure long tail
Real tool inventories run 10–15+ tools where most have zero failures; showing every tool as an equal-weight bar buried the 2–3 tools that actually matter and left every bar near-invisible (rates ranging 0–5.5% vs. the old mock's 0–24% scale).
- Only tools **with at least one failure** render as ranked bars, sorted by rate descending.
- Bar width is scaled to the **highest rate in the current dataset** (`width% = rate / maxRate * 100`), not a fixed denominator — so the worst offender always fills the track and relative differences stay visible regardless of how low absolute rates run.
- All zero-failure tools collapse into a `<details>` disclosure: `"N tools with no failures · M calls"`. Closed by default; expands to a compact 2-column grid of name + call count.

### 2. "Reliability mix" — stacked bar replaces the donut
A donut/ring chart fails at realistic pass/fail skew (97–99% success is typical) — the failure slice shrinks to a barely-visible sliver or single pixel dot.
- Replaced with a big fail-rate number (e.g. "2.2%") + a slim horizontal two-segment stacked bar (`ok` / `bad`, proportional via `flex-grow`).
- The `bad` segment has a `min-width` floor (4px) so it never fully disappears at very low fail rates.
- The two-row legend (Succeeded / Failed, count + %) beneath is unchanged in content, just re-paired with the new bar.

### 3. "Same-tool repeats per session" — scope column and chip thresholds tuned to real paths/values
- **Scope column is now two lines per cell**: filename bold on top, directory path dimmed below, each independently ellipsis-truncated (standard end-truncation, left-aligned) — real paths share a long common project-root prefix that used to swallow the one thing that actually varies (the filename) when truncated as a single line. Full path is still available via native `title` tooltip on hover.
- Non-file scopes get distinct treatment: shell commands (e.g. `docker exec`) get a dim `$` prefix; mangled sandbox tmp paths (e.g. `/private/tmp/claude-501/...`) collapse to an italic `sandbox tmp · claude-501/` label instead of the raw unreadable path.
- **Chip color thresholds re-bucketed to the real value range**: hot ≥10, warm 6–9, cool <6 (was hot ≥7 / warm ≥4 / cool below — tuned to the old mock's 3–7 range, which meant real data clustering at 4–14 never hit the "cool" tier and lost all gradation).
- Rows that occurred in only **one session** get a small "spike" tag next to the session count, flagging a possible one-off outlier vs. a chronic cross-session pattern.
- Table uses `table-layout:fixed` with an explicit `<colgroup>` — needed for the two-line scope cell's ellipsis truncation to have a stable width to truncate against.
- **Implementation note:** the scope `<td>` must stay a normal table-cell (do not set `display:flex` on it) — doing so breaks the table's row-height stretching, so single-line rows (commands, root files) don't stretch to match two-line rows and their bottom borders misalign.

## Design Tokens
No new colors. Reuses `--ok`, `--bad`, `--warn`, `--muted`, `--dim`, `--ink`, `--border`, `--track`.

## Files
- `Aurora Reliability Mockup v2.html` — `.frow`/`.zerowrap`/`.zerolist` CSS + markup (card 1), `.rmix`/`.rmix-bar` CSS + markup (card 2), `td.scope`/`.chip`/`.spiketag` CSS + table markup (card 3).
