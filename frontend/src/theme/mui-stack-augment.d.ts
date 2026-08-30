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
// MUI v9 removed alignItems/justifyContent as direct Stack shorthand props.
// This augmentation re-adds them so generated code using the old v5 syntax
// type-checks cleanly. The theme's MuiStack styleOverride applies them at runtime.
import type { CSSProperties } from 'react';

declare module '@mui/material/Stack' {
  interface StackOwnProps {
    alignItems?: CSSProperties['alignItems'];
    justifyContent?: CSSProperties['justifyContent'];
    gap?: CSSProperties['gap'];
    flexWrap?: CSSProperties['flexWrap'];
    // MUI System spacing shorthands removed in v9
    mb?: number | string;
    mt?: number | string;
    ml?: number | string;
    mr?: number | string;
    mx?: number | string;
    my?: number | string;
    pb?: number | string;
    pt?: number | string;
    pl?: number | string;
    pr?: number | string;
    px?: number | string;
    py?: number | string;
    p?: number | string;
  }
}
