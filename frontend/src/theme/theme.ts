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
import { alpha, createTheme, type Theme } from '@mui/material';
import type { ColorMode } from './colorMode';
import { auroraColors, neutralColors } from './colors';
import { fontFamilies } from './typography';

declare module '@mui/material/styles' {
  interface Theme {
    custom: {
      progressTrack: string;
      rowStripe: string;
      titleColor: string;
      surfaceMuted: string;
    };
  }
  interface ThemeOptions {
    custom?: {
      progressTrack?: string;
      rowStripe?: string;
      titleColor?: string;
      surfaceMuted?: string;
    };
  }
}

// Shared chart series palette so every chart, donut, and rank-list bar uses the same colors.
// Aurora palette: violet / pink / cyan lead, then harmonized hues that read on both
// the light lilac surface and the dark glass surface.
const CHART_PALETTE = [
  auroraColors.violet,
  auroraColors.pink,
  auroraColors.cyan,
  auroraColors.green,
  auroraColors.gold,
  auroraColors.blue,
  auroraColors.purple,
  auroraColors.teal,
];

export const colorForIndex = (index: number): string => {
  return CHART_PALETTE[index % CHART_PALETTE.length];
};

// Corner-radius scale in MUI shape units (×12px base, see `shape.borderRadius` below).
// Every `sx` borderRadius snaps to one of these so cards, controls, chips, and bars
// share a consistent rounding rhythm. `lg` (18px) matches the MuiPaper.outlined card.
export const radii = {
  xs: 0.75, // 9px  — chips, small bars, dense tiles
  sm: 1.25, // 15px — inputs, cells, medium surfaces
  lg: 1.5, //  18px — cards / Paper surfaces
  xl: 2.25, // 27px — large panels / docks
  pill: 999, // fully rounded — progress bars, pills
} as const;

interface ThemeTokens {
  primary: string;
  primarySoft: string;
  pageBg: string;
  paperBg: string;
  chromeBg: string;
  cardGradient: string;
  cardShadow: string;
  backdrop: string;
  textPrimary: string;
  textSecondary: string;
  border: string;
  actionHover: string;
  actionSelected: string;
  appBarShadow: string;
  progressTrack: string;
  rowStripe: string;
  titleColor: string;
  surfaceMuted: string;
}

// Aurora radial "glow" backdrop painted behind the whole app (fixed, non-scrolling).
const DARK_BACKDROP =
  `radial-gradient(900px 520px at 6% -6%, ${alpha(auroraColors.violetLight, 0.22)}, transparent 60%),` +
  `radial-gradient(820px 520px at 98% 2%, ${alpha(auroraColors.pinkBright, 0.13)}, transparent 60%),` +
  `radial-gradient(960px 640px at 78% 116%, ${alpha(auroraColors.cyanGlow, 0.11)}, transparent 62%)`;
const LIGHT_BACKDROP =
  `radial-gradient(900px 520px at 6% -6%, ${alpha(auroraColors.violet, 0.16)}, transparent 60%),` +
  `radial-gradient(820px 520px at 98% 2%, ${alpha(auroraColors.pink, 0.1)}, transparent 60%),` +
  `radial-gradient(960px 640px at 78% 116%, ${alpha(auroraColors.cyanBright, 0.1)}, transparent 62%)`;

/**
 * The app backdrop as a `background-image` value, for surfaces that sit *over*
 * the page rather than inside it (the Sessions detail drawer) and so can't
 * inherit the body's fixed glow. Same two constants the body gets — don't
 * hand-write a third copy of the radial stack.
 */
export const backdropGradient = (mode: ColorMode): string =>
  mode === 'dark' ? DARK_BACKDROP : LIGHT_BACKDROP;

const TOKENS: Record<ColorMode, ThemeTokens> = {
  light: {
    primary: auroraColors.violet,
    primarySoft: auroraColors.violetDeep,
    pageBg: neutralColors.pageLight,
    paperBg: neutralColors.white,
    chromeBg: alpha(neutralColors.white, 0.72),
    cardGradient: `linear-gradient(180deg, ${alpha(neutralColors.white, 0.92)}, ${alpha(neutralColors.white, 0.66)})`,
    cardShadow: `0 10px 30px ${alpha(neutralColors.shadowIndigo, 0.07)}`,
    backdrop: LIGHT_BACKDROP,
    textPrimary: neutralColors.inkLight,
    textSecondary: neutralColors.inkSecondaryLight,
    border: alpha(neutralColors.inkLight, 0.09),
    actionHover: alpha(auroraColors.violet, 0.07),
    actionSelected: alpha(auroraColors.violet, 0.13),
    appBarShadow: `0 1px 0 ${alpha(neutralColors.inkLight, 0.05)}`,
    progressTrack: alpha(neutralColors.inkLight, 0.08),
    // Half of progressTrack's opacity — a table zebra stripe needs to read as a
    // faint tint, not the same strength as a hover fill. Deliberately a separate
    // token rather than `alpha(progressTrack, 0.5)` at the call site: alpha()
    // overwrites a color's existing alpha channel instead of multiplying it, so
    // re-wrapping an already-translucent token silently discards its opacity and
    // substitutes the literal 0.5 (0.08 became 0.5 — a nearly opaque stripe, the
    // bug that motivated adding this token).
    rowStripe: alpha(neutralColors.inkLight, 0.04),
    // Deep indigo page-title color (matches the Aurora mockup — not pure ink).
    titleColor: neutralColors.titleLight,
    surfaceMuted: neutralColors.surfaceMutedLight,
  },
  dark: {
    primary: auroraColors.violetLight,
    primarySoft: auroraColors.violetPale,
    pageBg: neutralColors.pageDark,
    paperBg: neutralColors.paperDark,
    chromeBg: alpha(neutralColors.chromeDark, 0.72),
    cardGradient: `linear-gradient(180deg, ${alpha(neutralColors.white, 0.055)}, ${alpha(neutralColors.white, 0.02)})`,
    cardShadow: `0 14px 36px ${alpha(neutralColors.black, 0.45)}`,
    backdrop: DARK_BACKDROP,
    textPrimary: neutralColors.textPrimaryDark,
    textSecondary: auroraColors.mutedLavender,
    border: alpha(neutralColors.white, 0.09),
    actionHover: alpha(auroraColors.violetLight, 0.12),
    actionSelected: alpha(auroraColors.violetLight, 0.22),
    appBarShadow: `0 1px 0 ${alpha(neutralColors.white, 0.04)}`,
    progressTrack: alpha(neutralColors.white, 0.08),
    rowStripe: alpha(neutralColors.white, 0.04),
    titleColor: neutralColors.titleDark,
    surfaceMuted: neutralColors.surfaceMutedDark,
  },
};

export const createAppTheme = (mode: ColorMode = 'light'): Theme => {
  const tokens = TOKENS[mode] ?? TOKENS.light;
  return createTheme({
    palette: {
      mode,
      primary: { main: tokens.primary, contrastText: neutralColors.white },
      background: { default: tokens.pageBg, paper: tokens.paperBg },
      text: { primary: tokens.textPrimary, secondary: tokens.textSecondary },
      divider: tokens.border,
      action: {
        hover: tokens.actionHover,
        selected: tokens.actionSelected,
      },
    },
    custom: {
      progressTrack: tokens.progressTrack,
      rowStripe: tokens.rowStripe,
      titleColor: tokens.titleColor,
      surfaceMuted: tokens.surfaceMuted,
    },
    shape: { borderRadius: 12 },
    typography: {
      fontFamily: fontFamilies.body,
      h4: {
        fontFamily: fontFamilies.display,
        fontWeight: 800,
        fontSize: 30,
        letterSpacing: -0.6,
      },
      h5: {
        fontFamily: fontFamilies.display,
        fontWeight: 700,
        fontSize: 21,
        letterSpacing: -0.3,
      },
      h6: { fontFamily: fontFamilies.display, fontWeight: 700, fontSize: 16.5 },
      subtitle1: { fontFamily: fontFamilies.display, fontWeight: 600 },
      button: { textTransform: 'none', fontWeight: 600 },
      // Monospace + tabular figures for numeric/ID/timestamp text. Applied via
      // `sx={{ typography: 'mono' }}` so it composes with any element's own sizing.
      mono: {
        fontFamily: fontFamilies.mono,
        fontVariantNumeric: 'tabular-nums',
      },
      // Uppercase display-font section labels ("eyebrows"). Two sizes: `eyebrow`
      // for card/section headers, `eyebrowSm` for dense contexts (table headers,
      // inline labels). Use via `sx={{ typography: 'eyebrow' }}`.
      eyebrow: {
        fontFamily: fontFamilies.display,
        fontWeight: 700,
        fontSize: 12,
        letterSpacing: 0.8,
        textTransform: 'uppercase',
      },
      eyebrowSm: {
        fontFamily: fontFamilies.display,
        fontWeight: 700,
        fontSize: 10.5,
        letterSpacing: 0.6,
        textTransform: 'uppercase',
      },
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: {
            backgroundColor: tokens.pageBg,
            backgroundImage: tokens.backdrop,
            backgroundAttachment: 'fixed',
            backgroundRepeat: 'no-repeat',
          },
          'input[type="datetime-local"]': { colorScheme: mode },
        },
      },
      MuiAppBar: {
        defaultProps: { elevation: 0, color: 'inherit' },
        styleOverrides: {
          root: {
            backgroundColor: tokens.chromeBg,
            backdropFilter: 'blur(14px)',
            WebkitBackdropFilter: 'blur(14px)',
            color: tokens.textPrimary,
            borderBottom: `1px solid ${tokens.border}`,
            boxShadow: tokens.appBarShadow,
          },
        },
      },
      MuiDrawer: {
        styleOverrides: {
          paper: {
            backgroundColor: tokens.chromeBg,
            backdropFilter: 'blur(14px)',
            WebkitBackdropFilter: 'blur(14px)',
            borderRight: `1px solid ${tokens.border}`,
          },
        },
      },
      MuiPaper: {
        defaultProps: { elevation: 0 },
        styleOverrides: {
          outlined: {
            borderColor: tokens.border,
            borderRadius: 18,
            backgroundImage: tokens.cardGradient,
            backdropFilter: 'blur(10px)',
            WebkitBackdropFilter: 'blur(10px)',
            boxShadow: tokens.cardShadow,
          },
        },
      },
      // Dialog's Paper defaults to elevation={24}, which in dark mode paints a
      // translucent white overlay gradient on top of `background.paper` — every
      // modal in the app read as a washed-out gray-purple instead of the same
      // solid dark surface used by cards/drawers. Cancel the overlay and pin the
      // surface color explicitly so no Dialog call site needs its own PaperProps.
      MuiDialog: {
        styleOverrides: {
          paper: {
            backgroundImage: 'none',
            backgroundColor: tokens.paperBg,
            border: `1px solid ${tokens.border}`,
            boxShadow: tokens.cardShadow,
          },
        },
      },
      // MUI's default Tooltip is a flat mid-gray (`rgba(97,97,97,.92)` in both modes),
      // which reads as an unstyled overlay against the app's tinted paper surfaces —
      // most visibly in dark mode, where it sits lighter than the card underneath it
      // instead of blending in. Match it to the same solid surface + border + shadow
      // MuiDialog uses, and repaint the arrow to the same solid color so it doesn't
      // show the default gray poking out from behind the tooltip body.
      MuiTooltip: {
        styleOverrides: {
          tooltip: {
            backgroundColor: tokens.paperBg,
            color: tokens.textPrimary,
            border: `1px solid ${tokens.border}`,
            boxShadow: tokens.cardShadow,
            fontSize: 12,
          },
          arrow: {
            color: tokens.paperBg,
            '&::before': {
              border: `1px solid ${tokens.border}`,
            },
          },
        },
      },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: { borderRadius: 10 },
          outlined: {
            borderColor: tokens.border,
            // Solid surface so controls don't show the aurora backdrop through them.
            backgroundColor: tokens.paperBg,
            boxShadow: tokens.cardShadow,
            '&:hover': {
              backgroundColor: tokens.paperBg,
              borderColor: tokens.primary,
            },
          },
        },
      },
      MuiChip: {
        styleOverrides: { root: { borderRadius: 999 } },
      },
      MuiToggleButton: {
        styleOverrides: { root: { borderRadius: 10, textTransform: 'none' } },
      },
      MuiTabs: {
        styleOverrides: {
          indicator: { height: 3, borderRadius: 3 },
        },
      },
      MuiTab: {
        styleOverrides: {
          root: { textTransform: 'none', fontWeight: 600, letterSpacing: 0.1 },
        },
      },
      MuiLinearProgress: {
        styleOverrides: {
          root: { borderRadius: 6, height: 8 },
          bar: { borderRadius: 6 },
        },
      },
      // Back-compat: MUI v9 dropped Stack's layout shorthand props
      // (alignItems / justifyContent / gap / flexWrap). Forward them from
      // ownerState so any v5-style <Stack alignItems=…> still lays out.
      // New code should put these in `sx`; this is a safety net only.
      MuiStack: {
        styleOverrides: {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          root: ({ ownerState, theme: t }: any) => ({
            ...(ownerState?.alignItems
              ? { alignItems: ownerState.alignItems }
              : {}),
            ...(ownerState?.justifyContent
              ? { justifyContent: ownerState.justifyContent }
              : {}),
            ...(ownerState?.flexWrap ? { flexWrap: ownerState.flexWrap } : {}),
            ...(ownerState?.gap != null
              ? {
                  gap:
                    typeof ownerState.gap === 'number'
                      ? t.spacing(ownerState.gap)
                      : ownerState.gap,
                }
              : {}),
          }),
        },
      },
    },
  });
};
