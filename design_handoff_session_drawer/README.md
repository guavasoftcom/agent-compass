# Handoff: Session Detail Drawer

## Overview
A right-side slide-in drawer on the Sessions table (Aurora / Agent Compass activity dashboard). Clicking a session row opens a drawer showing that session's full prompt timeline, replacing the previous inline row-expansion pattern.

## About the Design Files
The bundled HTML file is a **design reference built in HTML/CSS/vanilla JS** — a working prototype showing the intended look and behavior, not production code to copy directly. Recreate this drawer in the target codebase's existing environment (this project's frontend is React + MUI v9) using its established components, theme tokens, and data-fetching patterns, rather than porting the HTML/JS verbatim.

## Fidelity
**High-fidelity.** Colors, spacing, typography, and interaction timing below are final; implement pixel-for-pixel where the target stack allows.

## Screens / Views

### Sessions table (context)
Unchanged except: rows no longer show an inline expand caret. A session row's whole `<tr>` is clickable; the active row gets a highlight state (see Interactions).

### Session Detail Drawer
**Purpose:** Inspect a single session's prompt-by-prompt timeline — cost, tokens, model, tools called, prompt text — without losing table scroll/sort/page state.

**Layout**
- Fixed panel, anchored top/right/bottom of viewport: `top:0; right:0; bottom:0`.
- Width `560px`, `max-width: 92vw` (so it never overflows on narrow viewports).
- `display:flex; flex-direction:column` — header fixed height, body fills remaining space and scrolls independently.
- Backdrop overlay behind it: full-viewport fixed, `rgba(12,8,24,.45)`.
- Stacking: overlay `z-index:40`, drawer `z-index:41` (above the app's `.rail`/`.main`, below nothing else in this page).

**Header** (`.drawerhd`)
- `display:flex; align-items:flex-start; justify-content:space-between; gap:14px; padding:16px 22px`
- Bottom border: `1px solid var(--border)`
- Left side (flex:1, column, gap 4px):
  - Row 1 — session id: `Session <session-uuid>`, font `Space Grotesk` monospace, `12px`, label in `--muted`, the id itself in `--ink` at weight 600.
  - Row 2 — a single metadata line, `11.5px`, color `--dim`, items separated by a "·" glyph (rendered via `::after{content:"·";margin-left:8px;color:var(--border)}` on every item except the last), wrapping allowed:
    1. Session start timestamp (e.g. `Aug 9 at 10:36 AM`)
    2. Total cost (e.g. `$2.38`)
    3. `active {relative time}` (e.g. `active 52m ago`) — `title` attribute holds the full absolute timestamp
    4. Token total (e.g. `4.8M tok`, hover reveals input / output / cache-creation / cache-read breakdown in a tooltip) + cache-efficiency percentage badge (color-coded: green ≥85%, default 60–85%, amber <60%) + the word "cache"
- Right side: close button, `32×32px`, `border-radius:9px`, `border:1px solid var(--border)`, `background:var(--surface)`, `×` glyph in `--muted`; hover → `color:var(--primary)`, `border-color:var(--selected-ring)`.

**Body** (`.drawerbody`)
- `flex:1; overflow-y:auto` — independent scroll region.
- Contains the session's **prompt timeline**: a vertical list of "turn" cards connected by a left-hand rail (a 2px gradient line with a dot per card), each card showing:
  - Timestamp, model chip (color-coded dot: Opus = primary purple, Sonnet = cyan `#13b6e6`, Haiku = dim gray), turn cost, token count (hover tooltip with breakdown)
  - "View trace →" pill link when the turn has a trace id (opens the tracing view — wire to the real trace URL in production)
  - Full prompt text (or an italic "prompt text not captured" placeholder)
  - Tool-call chips (tool name + call count), or "No tool calls" placeholder
  - Turns outside the dashboard's selected time window render at `opacity:.45` with a labeled horizontal divider ("selected window starts/ends") at the boundary crossing — the timeline always shows the *whole* session even if the table's window filter is narrower.
- On open, the body auto-scrolls to the bottom (most recent turn).

## Interactions & Behavior
- **Open:** click anywhere on a session `<tr>`. Sets a single `expandedId` state to that session's id.
- **Row highlight:** the open session's row gets a highlighted background (same visual treatment as row hover) for as long as the drawer is open.
- **Close:** click the × button, click the backdrop overlay, or press `Escape`. Clears `expandedId`.
- **Toggle:** clicking the already-open row's `<tr>` again closes the drawer (acts as a toggle, not just open).
- **Transition:** drawer slides in via `transform: translateX(100%) → translateX(0)`, `.26s cubic-bezier(.22,.8,.24,1)`. Overlay cross-fades via opacity, `.2s`.
- Switching between sessions (closing one row, opening another) does not disturb the table's current sort, page, or scroll position — this is the core reason for using a drawer over the old inline-expansion pattern.

## State Management
- `expandedId: string | null` — id of the session whose drawer is open (also drives row highlight).
- Session data fetched/derived per row already includes: `sessionId`, `startTimestamp`, `lastActivity`, `costUsd`, `tokens` (total) + `tokenBreakdown` (input/output/cacheCreation/cacheRead), `cacheEfficiency`. The prompt timeline itself is separate per-session data (array of turns) — in the prototype this is pre-generated; in production this should be **fetched on demand when a row is expanded** (avoid loading every session's full timeline up front).

## Design Tokens
Referencing this app's existing CSS variables (light / dark):
- `--primary`: `#7c4dff` / `#8b5cff`
- `--page` (drawer background base): `#f4f2fb` / `#0b0a12`
- `--ink` (primary text): `#1c1830` / `#eee9fb`
- `--muted`: `#6c6589` / `#9a93b6`
- `--dim`: `#938cae` / `#7b7397`
- `--border`: `rgba(28,24,48,.09)` / `rgba(255,255,255,.09)`
- `--surface`: `#ffffff` / `#1b1828`
- `--selected-ring`: `rgba(124,77,255,.32)` / `rgba(139,92,255,.4)`
- `--ok` / `--warn` / `--bad` (cache-efficiency badge): `#1f9d6b`/`#e6952b`/`#e5484d` (light), `#34d399`/`#f0b54e`/`#ff6b6f` (dark)
- Typography: `Sora` (headings, labels, chips — weights 400–800) + `Space Grotesk` (body text, monospace-style ids/timestamps — weights 400–700), both via Google Fonts.
- Border radius: `9px` (close button), `13px` (timeline cards), `999px` (pills/chips).
- Drawer shadow: `-28px 0 60px rgba(20,12,50,.3)`.

## Assets
No image assets — all icons are inline SVG (stroke-based, `stroke-width:1.7–2.5`), no icon font/library dependency. Fonts loaded from Google Fonts (`Sora`, `Space Grotesk`).

## Files
- `Aurora Sessions Mockup.html` (included in this folder) — full working prototype. The drawer implementation lives in the `<style>` block (`.drawer`, `.drawer-overlay`, `.drawerhd*` rules) and in the `<script>` block (`renderDrawer()`, `drawerHeader()`, `closeDrawer()`, and the `timelinePanel()` function it reuses for the body content).
