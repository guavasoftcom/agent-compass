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
