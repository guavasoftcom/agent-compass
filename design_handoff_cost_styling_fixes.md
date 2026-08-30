# Cost Page Styling Fixes Handoff

## Overview
The local React implementation differs from the Aurora mockup in table styling and visualization approach. This handoff documents the specific CSS and component changes needed to match the Aurora design.

## Changes Required

### 1. Cost Drivers Card (`CostDriversCard.tsx`)

**Issue:** Current implementation uses monochromatic styling with small colored squares; mockup shows vibrant colored dots + better visual hierarchy.

**Changes needed:**

- **Model name styling:** Keep the colored dot indicator but ensure it's more prominent (currently 9×9px)
- **Row background colors:** The table currently uses neutral zebra striping. Consider if the mockup requires lighter row backgrounds for visual separation
- **Typography:** Verify header capitalization matches mockup (currently `eyebrowSm` variant)
- **Cell padding:** Current is `13px 12px` — confirm if this matches mockup density
- **Numeric columns:** Right-aligned with `tabular-nums` — verify column widths are proportional

**Current structure:**
```tsx
// Model cell with dot
<Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1.125 }}>
  <Box sx={{ width: 9, height: 9, borderRadius: '3px', flexShrink: 0, bgcolor: colorForIndex(...) }} />
  {shortModelName(cell.model)}
</Box>
```

**Potential improvements:**
- Increase dot size to 12×12px for better visibility
- Consider slight glow/shadow on dots for emphasis
- Verify model name font weight (currently 600)

---

### 2. Top Sessions Card (`TopSessionsCard.tsx`)

**Issue:** Current implementation uses a `LinearProgress` bar for "Share of spend"; mockup shows a different visualization approach.

**Changes needed:**

- **Progress bar styling:** Currently uses `radii.pill` and inline width of 108px. Verify if bar color and background match mockup
- **Share percentage placement:** Currently inline with the bar. Mockup may show different positioning
- **Prompt/session ID layout:** Currently stacked (prompt bold, ID muted below). Verify this matches mockup
- **Row interactivity:** Currently pointer cursor + hover background. Ensure hover state matches mockup

**Current structure:**
```tsx
<LinearProgress
  variant="determinate"
  value={Math.min(100, share)}
  sx={{
    display: 'inline-block',
    verticalAlign: 'middle',
    width: SHARE_TRACK_WIDTH,
    height: 7,
    borderRadius: radii.pill,
    bgcolor: trackColor,
    '& .MuiLinearProgress-bar': { borderRadius: radii.pill, bgcolor: barColor },
  }}
/>
<Box component="span" sx={{ ml: 1.25, ...tabular-nums, color: 'text.secondary' }}>
  {share.toFixed(1)}%
</Box>
```

---

## Visual Comparison

| Element | Local Code | Aurora Mockup |
|---------|-----------|--------------|
| Cost Drivers - Model dot | 9×9px, small | Larger, more prominent |
| Cost Drivers - Row styling | Neutral zebra stripes | Subtle alternating backgrounds |
| Top Sessions - Share viz | LinearProgress bar inline | [Verify from mockup] |
| Top Sessions - Typography | Prompt (600wt) + ID (11px mono) | [Verify spacing/sizing] |

---

## Implementation Notes

1. **Do NOT revert** the zebra-striped table design — it's an improvement over unstyled rows
2. **Keep the neutral color palette** — the colored dots on model names provide enough visual interest without a full color retheme
3. **Verify actual mockup specs** before changing:
   - Exact dot size
   - Row background opacity/color
   - Progress bar height and styling
   - Cell padding and font sizes

---

## Files to Update

- `frontend/src/pages/CostPage/components/CostDriversCard/CostDriversCard.tsx`
- `frontend/src/pages/CostPage/components/TopSessionsCard/TopSessionsCard.tsx`

---

## Next Steps

1. Compare the actual Aurora mockup specs side-by-side with the local implementation
2. Update `tableSx()` styling for any color/padding differences
3. Adjust component dimensions (dot size, bar width, padding) to match mockup
4. Test hover states and responsive behavior on both desktop and tablet
