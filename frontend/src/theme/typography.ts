/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
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
