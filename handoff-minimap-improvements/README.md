# Handoff — Waterfall minimap improvements

Trace detail page (`frontend/src/pages/TraceDetailPage/`), the minimap above the
span waterfall (`TraceDetailHeader`/`WaterfallToolbar` area — the brush/zoom control).
Reference implementation: `Aurora Trace Detail Mockup.html` + `trace-detail.js` in this
folder (open the HTML directly, no build step).

## What changes

**1. Drag-to-select on bare track**

Today (`Minimap.tsx` or equivalent), a user can only move/resize the existing brush —
zooming into an arbitrary sub-range takes two aimed drags (pull the left handle in,
then find and pull the right handle in), each starting from a full-width brush.

Pressing on bare track (not the brush or its handles) now starts a fresh range
selection anchored at the press point: press at 4s, drag to 6s, release — one gesture.
Handles and the move-drag on an existing brush are unchanged.

```
mousedown on brush        → mode 'move'  (existing)
mousedown on .bh.l / .bh.r → mode 'l' / 'r' (existing)
mousedown on bare mmtrack  → mode 'create', anchored at the press point
```

**2. Zoomed-range indicator**

Nothing showed when the view was a sub-range vs. the full trace, and no readout gave
the exact window. The hint line under the legend now switches modes:

- Full view: `drag to select · drag edges to resize`
- Zoomed: a small monospace **pill** — `2.10s–6.40s` bold + `of 12.30s` in a smaller
  uppercase tail — next to plain `dbl-click resets` text.

Went through a few passes (bold text → color+weight → dedicated pill) before landing
on the pill: matches the chip visual language already used for token/cost figures
elsewhere on the page, and separates "the number that matters" from "the affordance
reminder" instead of competing for the same weight/color.

**3. Dim-outside instead of tint-inside**

The brush interior was a 13%-opacity purple fill sitting on top of the ticks — it
dulled exactly the region the user is looking at. Now the brush interior is
transparent (bordered only) and two `.mmdim` overlays cover the **excluded**
regions left/right of it. Standard range-brush idiom; ticks inside the selection
render at full contrast.

**4. Error ticks can't be hidden by z-order**

Ticks were drawn in span array order, so a same-position `ok` tick painted after an
`error` tick could cover it — on the one view whose job is surfacing errors. Fixed
two ways:
- Error ticks are always drawn **last** (sorted to the end), so they're never
  underneath another tick at the same x-position.
- They get a red **ring** (`box-shadow: 0 0 0 1.5px`) instead of just relying on
  color, so they're identifiable even at 3px height. (Tried a full-height 34px red
  block first — visually overwhelming against the thin ok/model/tool ticks, dropped
  in favor of the same-size ring.)

## Suggested React shape

```tsx
// Minimap.tsx
type DragMode = 'move' | 'l' | 'r' | 'create';

function handleTrackMouseDown(e: React.MouseEvent) {
  const rect = trackRef.current!.getBoundingClientRect();
  const t = clamp(((e.clientX - rect.left) / rect.width) * total, 0, total);
  startDrag({ mode: 'create', anchor: t, x: e.clientX, w: rect.width });
}
// in the move handler's 'create' branch:
// vs = Math.min(anchor, pointerTime); ve = Math.max(anchor, pointerTime); clamp to MIN width
```

- `ticks` render list: `[...spans].sort((a, b) => (a.status === 'error' ? 1 : 0) - (b.status === 'error' ? 1 : 0))`.
- Two `<div className="mmdim">` elements sized from `viewStart`/`viewEnd`, rendered
  behind the brush border but above the tick layer (`z-index` order: ticks < dim < brush).
- Zoom-state pill: render only when `viewStart > 0 || viewEnd < total`; format both
  ends with the existing duration formatter.

## Docs to update

`frontend/src/pages/TraceDetailPage/CLAUDE.md` — minimap section: note the four
behaviors above (create-drag, zoom pill, dim-outside, error-ring) so a future pass
doesn't reintroduce the tint-inside fill or let ok ticks cover errors again.

## Not in scope

Keyboard control of the brush, wheel-zoom, and hover tooltips on individual ticks
were discussed but not built — flagged as follow-ups if wanted.
