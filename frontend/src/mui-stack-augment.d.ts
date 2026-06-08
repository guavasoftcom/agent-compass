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
