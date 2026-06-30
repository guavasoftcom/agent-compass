// Typographic design tokens — the font-family stacks used across the app.
//
// Like `colors.ts`, this is the single source of truth: no raw font-family
// string should live in component `sx` props or `theme.ts`. The three stacks
// below cover every text surface. Font *sizes* are intentionally left inline —
// the ~28 values in use don't form a clean scale, so a 1:1 extraction would add
// indirection without benefit; snapping them to a real type scale is a separate,
// deliberate redesign (see frontend/CLAUDE.md).
export const fontFamilies = {
  // Display / heading font (Sora) — page titles, card headers, KPI numbers.
  display: "'Sora', sans-serif",
  // Body / UI font (Space Grotesk) — the app default, with system fallbacks.
  body: "'Space Grotesk', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
  // Monospace font (JetBrains Mono) — code, identifiers, values, timestamps.
  mono: "'JetBrains Mono', monospace",
} as const;
