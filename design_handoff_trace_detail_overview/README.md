# Handoff: Trace Detail — Overview Card Cleanup

## Overview
Reworks the Overview card at the top of Trace Detail (stat strip, token composition, and metadata footer) to reduce redundancy and improve scannability. Also fixes the span-row hover tooltip for Agent (subagent launch) spans. No changes to the span waterfall or inspector drawer.

## About the Design Files
The bundled files (`Aurora Trace Detail Mockup.html` + `trace-detail.js`) are an **HTML/CSS/JS design reference**, not production code to ship as-is. Recreate this behavior in the target codebase's existing stack using its component and state patterns — port the layout, values, and interaction logic described below, not the raw markup.

## Fidelity
**High-fidelity.** Colors, spacing, typography are final; implement using the codebase's existing design tokens where equivalents exist.

## Changes

### 1. Trace ID / Session moved into the page header
Previously rendered as two full-width rows at the bottom of the metadata footer (raw hash strings competing for space with the stat strip). Now shown as compact copy-to-clipboard chips next to the "Trace detail" page title, matching the `Traces ›` breadcrumb row.
- Chip anatomy: uppercase label (`TRACE` / `SESSION`, 9.5px, dim), monospace value (11.5px, muted, truncates with ellipsis past 150px), small copy icon.
- Click anywhere on the chip copies the full (untruncated) value to the clipboard; the value briefly flashes "copied!" (1.2s) before reverting.
- Hover: border brightens to the selected-ring color; title attribute shows the full value for accessibility/long-hash cases.

### 2. Metadata footer simplified + evenly spaced
Footer now only carries **Root span**, **Services**, and **Started** (IDs moved to the header per above). The three items are distributed with `justify-content: space-between` across the card width instead of clustering left with a fixed gap — with only three short items, left-clustering left large dead space on wide viewports.

### 3. Token composition — single log-scaled bar list
Replaced the old two-track layout (a linear "full rate" stacked bar + a separate "cache read" bar + a duplicate 4-column legend grid below repeating the same numbers) with **one unified list**: one row per token category (Cache read, Input, Cache creation, Output), each row = color swatch + label (+ rate tag) + horizontal bar + value + share%.
- **Bars are log-scaled**, not linear: `width% = max(4%, log10(v+1) / log10(max+1) * 100)`. Cache read routinely runs 10–100× the other three categories — on a linear scale it paints solid and the other three bars vanish into a hairline. This mirrors the existing rule for the Token Usage "over time" chart (log Y-axis for the same order-of-magnitude spread — see project conventions, don't revert either to linear).
- Rows are sorted by magnitude, descending.
- Cache read carries a `0.1×` rate tag inline (billed at a tenth of the other categories' rate) instead of a separate section label/track.
- A caption line under the list states the scale is logarithmic and why, so the bars aren't mistaken for proportional.
- The "88% cached" headline chip and the top-line total/call-count/cost caption are unchanged.

### 4. Span-row tooltip: Agent spans show `subagent_type`
The hover tooltip on a tool chip (`.chip.tool`) picks the first populated key from an ordered list (`full_command`, `command`, `file_path`, `pattern`, `query`, `url`) to show what the tool was asked to do. For spans where `tool_name === 'Agent'`, that list isn't useful — the diagnostic value is *which subagent ran*. The tooltip now checks for `subagent_type` first when `tool_name` is `'Agent'`, and only falls back to the normal key list if `subagent_type` is absent. Every other tool's tooltip is unaffected.

## Design Tokens
No new colors. Reuses `--primary`, `--muted`, `--dim`, `--ink`, `--border`, `--selected-ring`, `--track`, and the existing token category colors (`#7c4dff` cache read / `#1aa7dd` input / `#e84bc0` cache creation / `#22b08a` output).

## Files
- `Aurora Trace Detail Mockup.html` — header chip markup (`#tracechip`/`#sessionchip` in `.crumb`), `.tidchip` CSS, `.metafoot` CSS, `.tokcard .toklist`/`.tokrow` CSS.
- `trace-detail.js` — `idChip()` + `renderHeader()` (header chips), `renderMetaFoot()` (footer), `renderTokCard()` (token list), `toolTipHtml()` (Agent tooltip key selection).
