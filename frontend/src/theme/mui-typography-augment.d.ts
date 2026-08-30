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
// Registers the custom typography variants defined in theme.ts (`mono`, `eyebrow`,
// `eyebrowSm`) with MUI's type system so they are valid as `<Typography variant=…>`
// and as `sx={{ typography: … }}` values.
import type { CSSProperties } from 'react';
import '@mui/material/styles';
import '@mui/material/Typography';

declare module '@mui/material/styles' {
  interface TypographyVariants {
    mono: CSSProperties;
    eyebrow: CSSProperties;
    eyebrowSm: CSSProperties;
  }
  interface TypographyVariantsOptions {
    mono?: CSSProperties;
    eyebrow?: CSSProperties;
    eyebrowSm?: CSSProperties;
  }
}

declare module '@mui/material/Typography' {
  interface TypographyPropsVariantOverrides {
    mono: true;
    eyebrow: true;
    eyebrowSm: true;
  }
}
