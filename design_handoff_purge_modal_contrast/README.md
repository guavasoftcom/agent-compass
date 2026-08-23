# Handoff: Fix washed-out contrast on the Purge confirmation modal (and other dialogs)

## Overview
The implemented `PurgeConfirmDialog` renders noticeably lighter/flatter than the design — surfaces read as a muted gray-purple instead of a rich dark panel, the warning box border is faint, and the "Total" line's panel is barely distinguishable from the body. Compare the attached `frontend-current.png` (real app, washed out) against `mockup-reference.png` (target look). This is almost certainly a **theme/elevation issue affecting dialogs broadly**, not a one-off on this component — check every `Dialog`/`Paper`-based modal in the app, not just this one.

## Most likely root cause
MUI's dark-mode `Paper` applies a default **elevation overlay** — a semi-transparent white gradient layered on top of `background.paper` that lightens with elevation. A `Dialog`'s `Paper` (default `elevation={24}`) gets a strong overlay, which is almost certainly why the whole modal reads lighter/grayer than the app's other dark surfaces (which likely use `elevation={0}`/`variant="outlined"` and don't get this treatment — e.g. the page's regular cards). Fix:
```jsx
<Dialog PaperProps={{ elevation: 0, sx: { backgroundImage: 'none', backgroundColor: 'background.paper' } }} ...>
```
(or the `slotProps.paper` equivalent per this project's MUI v9 convention). If the dialog is composed from a custom styled `Paper` instead of MUI's `Dialog`, check for the same default `backgroundImage` elevation overlay there.

## Second likely cause: over-transparent alpha values
If colors were built with `alpha(theme.palette.X.main, N)` at too-low an `N`, tinted panels (the warning box, the "Total" banner) will blend into the dark background instead of standing apart. Target values, taken directly from the mockup (hex/alpha, map to the closest theme-token equivalent):

| Element | Background | Border |
|---|---|---|
| Dialog surface | solid `#1b1828`-equivalent (`background.paper`, **no elevation overlay**) | `rgba(255,255,255,.09)` (`divider`) |
| Warning box ("Run this while no agent is exporting") | `alpha(warning.main, 0.08)` | `alpha(warning.main, 0.35)` — this border needs to be visibly amber, not a hairline |
| "Total: …" panel | a distinct raised/tinted panel — `action.hover` or `alpha(common.white, 0.04)` on a **solid, non-transparent dialog background** so it still shows up (if the dialog surface itself is already washed out, this panel disappears into it — fix the dialog surface first) |
| Per-table color dots | full-saturation `colorForIndex(0/1/2)` — small size (9px) means desaturating them for "subtlety" makes them unreadable |

## Text contrast
Body copy should be `text.secondary`/`text.primary` at full opacity, not a dimmed/muted override — the reference has clearly legible bullet text at near-white on the dark surface; if it's currently using a lower-emphasis color token or an extra opacity wrapper, remove it.

## Checklist for the developer
1. Set `elevation={0}` (or equivalent) + `backgroundImage: 'none'` on every `Dialog`/modal `Paper` in the app — audit beyond just this one dialog.
2. Verify `background.paper` (dark mode) resolves to the same dark value used elsewhere in the app (cards, drawers) — the dialog shouldn't be a different, lighter surface color than everything else.
3. Re-check every `alpha(...)` call in this dialog against the table above; bump any that are noticeably fainter than spec.
4. Screenshot the fixed dialog next to `mockup-reference.png` before calling this done — the goal is a visual match, not just "close enough."

## Files
- `mockup-reference.png` — target look (from the Aurora mockup).
- `frontend-current.png` — the washed-out result to fix (as attached by the user).
- `Aurora Settings Mockup.html` — source of truth for the exact CSS variables/values referenced above.
