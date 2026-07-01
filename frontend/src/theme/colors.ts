// Single source of truth for every raw color the dashboard paints.
//
// `theme.ts`, `traceColors.ts`, and the page/component `sx` props all reference
// the tokens below — no `#rrggbb` or `rgba(...)` literal should live anywhere
// else under `src/`. For partial transparency, wrap a base hex in MUI's
// `alpha(token, opacity)` rather than hand-writing an rgba string, so the source
// hue stays centralized here.

// ─── Aurora brand hues ──────────────────────────────────────────────────────
// The signature violet / pink / cyan lead, then harmonized hues chosen to read
// on both the light lilac surface and the dark glass surface.
export const auroraColors = {
  violet: '#7c4dff', // primary action (light mode)
  violetLight: '#8b5cff', // primary action (dark mode) + gradient start
  violetDeep: '#6a3df0', // softer violet (light "primarySoft")
  violetPale: '#b89bff', // softer violet (dark "primarySoft")
  pink: '#e84bc0', // chart pink / cache-creation tokens
  pinkBright: '#ff6ad5', // gradient end + model-trace hue
  cyan: '#1aa7dd', // chart cyan / info / input tokens
  cyanBright: '#13b6e6', // tool-trace hue + live-tail gradient
  cyanGlow: '#42d6ff', // backdrop radial glow only
  green: '#22b08a', // chart green / output tokens
  greenDeep: '#1f9d6b', // subagent-trace hue + live-tail gradient
  gold: '#e6a33b', // chart gold
  orange: '#e6952b', // mcp-trace hue + warning severity
  blue: '#5d6ef0', // chart blue
  purple: '#b85cd6', // chart purple
  teal: '#3aa6bd', // chart teal
  red: '#e5484d', // error / severe severity + error gradient start
  coral: '#ff8d6a', // error gradient end
  mutedLavender: '#9a93b6', // permission-trace hue + dark secondary text
  mutedSlate: '#938cae', // unknown-service fallback hue
} as const;

// ─── Neutral surfaces, text, and shadow bases ───────────────────────────────
export const neutralColors = {
  white: '#ffffff',
  black: '#000000',
  pageLight: '#f4f2fb', // page background (light)
  pageDark: '#0b0a12', // page background (dark)
  paperDark: '#14111f', // card / paper background (dark)
  chromeDark: '#12101c', // app-bar / drawer base (dark, used at 72% alpha)
  inkLight: '#1c1830', // primary text + neutral border/shadow base (light)
  inkSecondaryLight: '#6c6589', // secondary text (light)
  textPrimaryDark: '#eee9fb', // primary text (dark)
  titleLight: '#2a2150', // deep-indigo page title (light)
  titleDark: '#efeaff', // page title (dark)
  shadowIndigo: '#241450', // card / popover shadow base (light)
  shadowDeep: '#0a061e', // heavy chart-tooltip shadow base
} as const;

// ─── Semantic aliases ───────────────────────────────────────────────────────
// Role-named tokens for colors that are used raw in components (i.e. not already
// covered by the theme palette in `theme.ts`). These reference the primitives
// above so the hue stays defined in exactly one place.

// Status / severity tints (used where a value isn't a MUI palette color, e.g.
// the tool-repeat heat scale and inline trace error states).
export const severity = {
  error: auroraColors.red,
  severe: auroraColors.red,
  warning: auroraColors.orange,
} as const;

// Token-composition bar segments (cache-read / input / cache-creation / output)
// shown in the trace summary.
export const tokenComposition = {
  cacheRead: auroraColors.violet,
  input: auroraColors.cyan,
  cacheCreate: auroraColors.pink,
  output: auroraColors.green,
} as const;

// ─── Signature gradients ────────────────────────────────────────────────────
// The violet -> pink "Aurora" action gradient (and friends) reused across
// buttons, the brand mark, active pills, and summary headers.
export const gradients = {
  auroraAction: `linear-gradient(135deg, ${auroraColors.violetLight}, ${auroraColors.pinkBright})`,
  auroraActionSoft: `linear-gradient(120deg, ${auroraColors.violetLight}, ${auroraColors.pinkBright})`,
  liveTail: `linear-gradient(135deg, ${auroraColors.greenDeep}, ${auroraColors.cyanBright})`,
  error: `linear-gradient(90deg, ${auroraColors.red}, ${auroraColors.coral})`,
} as const;
