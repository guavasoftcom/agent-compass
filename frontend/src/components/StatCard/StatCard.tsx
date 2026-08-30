import { useMemo, type ReactNode } from 'react';
import { Box, Paper, Tooltip, Typography } from '@mui/material';
import type { PaperProps } from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import { gradients } from '../../theme/colors';
import { fontFamilies } from '../../theme/typography';

export interface StatCardTrend {
  /** The delta label to display (e.g. "+3.2%" or "−12"). */
  delta: ReactNode;
  /** Determines the arrow direction and colour (green = up, red = down). */
  direction: 'up' | 'down';
}

export interface StatCardProps {
  label: ReactNode;
  value: ReactNode;
  /** Small caption under the value (e.g. "across 3 distinct tools"). */
  sub?: ReactNode;
  /**
   * When true, the value is painted with the vibrant Aurora violet→pink gradient
   * (matches the "Top tool" card in the mockup). Otherwise the value is plain ink.
   */
  accent?: boolean;
  /**
   * When true, renders the value in the Sora display typeface at 30 px with tight
   * letter-spacing — matching the TokensPage KPI tiles. The default h4 MUI variant
   * is used otherwise.
   */
  displayFont?: boolean;
  /**
   * Override the font size of the value when `displayFont` is true. Defaults to 30.
   * Use this when a denser strip card needs a smaller number (e.g. MetricKpiStrip
   * uses 23 to fit six cards across one row).
   */
  displayFontSize?: number;
  /**
   * When false, the label is rendered in mixed-case body style rather than the
   * default uppercase tracking caption. Useful for metric-name labels that are
   * already lowercase identifiers (MetricKpiStrip).
   */
  labelUppercase?: boolean;
  /**
   * Optional info-icon tooltip rendered inline next to the label. Use this to flag a
   * caveat the value can't state on its own — e.g. that this figure is measured from a
   * different pipeline than a similarly-named KPI elsewhere in the dashboard and the
   * two don't reconcile (see the Cost page's "Total spend" and the Tokens page's
   * "Total cost", which read from api_request logs and the cost.usage counter
   * respectively and can legitimately disagree by a few percent).
   */
  infoTooltip?: ReactNode;
  /**
   * Tints the `infoTooltip` icon `warning.main` instead of the default `text.secondary`,
   * for a caveat worth noticing at a glance rather than only on hover — e.g. a KPI that
   * looks the same as one on another page but is measured differently and won't match
   * it. Still the info icon shape (this isn't an error or a broken state), just more
   * visually salient. Defaults to `'info'`.
   */
  infoTooltipSeverity?: 'info' | 'warning';
  /**
   * Optional inline trend badge rendered to the right of the value (delta text +
   * directional arrow). Green for "up", red for "down".
   */
  trend?: StatCardTrend;
  /**
   * When true, shrinks the value to a smaller size step (23px, tighter
   * letter-spacing, word-break: break-word) instead of wrapping a long
   * identifier onto a third line. Callers compute this from the value's own
   * length (see `isLongStatValue`) rather than hardcoding it per card.
   */
  long?: boolean;
  /** Optional slot rendered below the sub (e.g. a sparkline). */
  children?: ReactNode;
  /**
   * Optional node rendered absolutely in the top-right corner of the card.
   * When present the Paper receives `position: relative` and the label gets
   * a right-padding of 1.5 to avoid overlap with the adornment.
   * Intended for small badge indicators such as the metric-type dot in MetricKpiStrip.
   */
  adornment?: ReactNode;
  /**
   * Props forwarded to the underlying MUI Paper element. Use this to wire up
   * interactive behaviour (onClick, role, tabIndex, onKeyDown, sx overrides) when
   * the card acts as a selector button, e.g. MetricKpiStrip.
   */
  PaperProps?: PaperProps;
}

/** Above this character count a value string shrinks via the `long` prop's size step. */
export const LONG_STAT_VALUE_THRESHOLD = 20;

/**
 * Whether a stat value is long enough to need the `long` size step. Callers
 * pass the concrete string (e.g. a skill/subagent identifier) rather than a
 * hardcoded flag, so the shrink only kicks in when the value actually runs
 * long — a short identifier still renders at the default size.
 */
export const isLongStatValue = (value: string): boolean => value.length > LONG_STAT_VALUE_THRESHOLD;

const TrendArrowUp = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={11} height={11}>
    <path d="M7 14l5-5 5 5" />
  </svg>
);

const TrendArrowDown = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={11} height={11}>
    <path d="M7 10l5 5 5-5" />
  </svg>
);

const StatCard = ({
  label,
  value,
  sub,
  accent = false,
  displayFont = false,
  displayFontSize = 30,
  labelUppercase = true,
  infoTooltip,
  infoTooltipSeverity = 'info',
  trend,
  long = false,
  adornment,
  children,
  PaperProps: paperProps,
}: StatCardProps) => {
  const { sx: paperSx, ...restPaperProps } = paperProps ?? {};

  const mergedPaperSx = useMemo(
    () => [
      { p: 2, height: '100%', ...(adornment ? { position: 'relative' } : {}) },
      ...(Array.isArray(paperSx) ? paperSx : paperSx ? [paperSx] : []),
    ],
    [adornment, paperSx],
  );

  const valueContent = (
    <>
      {value ?? '—'}
      {trend && (
        <Box
          component="span"
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '2px',
            fontSize: 11,
            fontWeight: 700,
            fontVariantNumeric: 'tabular-nums',
            ml: 1,
            color: trend.direction === 'up' ? 'success.main' : 'error.main',
            verticalAlign: 'middle',
          }}
        >
          {trend.delta}
          {trend.direction === 'up' ? <TrendArrowUp /> : <TrendArrowDown />}
        </Box>
      )}
    </>
  );

  return (
    <Paper
      variant="outlined"
      sx={mergedPaperSx}
      {...restPaperProps}
    >
      {adornment && (
        <Box sx={{ position: 'absolute', top: 13, right: 13 }}>
          {adornment}
        </Box>
      )}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
        {labelUppercase ? (
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ letterSpacing: 0.4, textTransform: 'uppercase', fontWeight: 600, minWidth: 0, ...(adornment ? { pr: 1.5 } : {}) }}
          >
            {label}
          </Typography>
        ) : (
          <Typography
            sx={{
              fontSize: 12,
              fontWeight: 600,
              fontFamily: fontFamilies.body,
              color: 'text.secondary',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              minWidth: 0,
              ...(adornment ? { pr: 1.5 } : {}),
            }}
          >
            {label}
          </Typography>
        )}
        {infoTooltip && (
          <Tooltip title={infoTooltip} arrow>
            <InfoOutlinedIcon
              sx={{
                fontSize: 14,
                color: infoTooltipSeverity === 'warning' ? 'warning.main' : 'text.secondary',
                cursor: 'help',
                flexShrink: 0,
              }}
            />
          </Tooltip>
        )}
      </Box>

      {displayFont ? (
        <Box
          sx={{
            mt: 0.75,
            fontFamily: fontFamilies.display,
            fontWeight: 800,
            fontSize: long ? 23 : displayFontSize,
            lineHeight: 1.05,
            letterSpacing: long ? '-0.4px' : '-0.6px',
            ...(long && { wordBreak: 'break-word' }),
            minHeight: 36,
            display: 'flex',
            alignItems: 'center',
            ...(accent
              ? {
                  backgroundImage: gradients.auroraActionSoft,
                  WebkitBackgroundClip: 'text',
                  backgroundClip: 'text',
                  color: 'transparent',
                }
              : { color: 'text.primary' }),
          }}
        >
          {valueContent}
        </Box>
      ) : (
        <Typography
          variant="h4"
          sx={{
            mt: 0.75,
            fontWeight: 800,
            lineHeight: 1.05,
            ...(long && { fontSize: 23, letterSpacing: '-0.4px', wordBreak: 'break-word' }),
            ...(accent
              ? {
                  backgroundImage: gradients.auroraActionSoft,
                  WebkitBackgroundClip: 'text',
                  backgroundClip: 'text',
                  color: 'transparent',
                }
              : { color: 'text.primary' }),
          }}
        >
          {valueContent}
        </Typography>
      )}

      {sub && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1.25 }}>
          {sub}
        </Typography>
      )}
      {children && <Box sx={{ mt: 1.5 }}>{children}</Box>}
    </Paper>
  );
};

export default StatCard;
