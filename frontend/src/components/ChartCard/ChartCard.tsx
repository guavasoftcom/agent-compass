import type { ReactNode } from 'react';
import { Box, Paper, Stack, Typography } from '@mui/material';

export interface ChartCardLegendItem {
  label: string;
  color: string;
}

export interface ChartCardProps {
  /**
   * Primary heading rendered in `subtitle1` weight at the top-left of the card.
   * Usually a plain string; pass a ReactNode (e.g. title text + an info-icon
   * Tooltip) when the heading needs an inline adornment.
   */
  title: ReactNode;
  /**
   * Optional secondary line rendered below the title row in `body2` / secondary
   * color — used by cards that need a one-line description under the heading.
   */
  subtitle?: string;
  /**
   * Content placed in the right side of the title row. Accepts either:
   *   - An array of `{ label, color }` items: ChartCard renders a compact static
   *     color-dot legend (rounded square swatch + caption label per item).
   *   - Any ReactNode: rendered as-is, enabling callers to pass a fully
   *     interactive `<AreaTrendLegend>` or other custom controls.
   */
  legend?: ChartCardLegendItem[] | ReactNode;
  /**
   * Optional slot pinned to the far right of the title row alongside (or instead
   * of) the legend — useful for icon buttons, menus, or other card-level actions.
   */
  actions?: ReactNode;
  /**
   * When true the Paper gets `height: '100%'` and lays itself out as a flex
   * column so the `children` area can expand with `flex: 1`. Use this on cards
   * that must fill a Grid cell or a fixed-height container.
   */
  fillHeight?: boolean;
  /**
   * The chart body. Rendered below the title row (and subtitle, if provided).
   * ChartCard leaves the chart entirely untouched — only the surrounding
   * Paper / title / legend scaffolding is managed here.
   */
  children: ReactNode;
}

/**
 * Returns true when the legend prop is an array of `{ label, color }` items so
 * ChartCard can decide whether to render its built-in dot legend or pass the
 * value through as a ReactNode.
 */
const isLegendItemArray = (value: unknown): value is ChartCardLegendItem[] =>
  Array.isArray(value) &&
  value.length > 0 &&
  typeof (value as ChartCardLegendItem[])[0].label === 'string' &&
  typeof (value as ChartCardLegendItem[])[0].color === 'string';

/**
 * A single static legend chip: a small rounded-square color swatch plus a
 * caption label. Non-interactive — for interactive legends (click-to-toggle,
 * hover-to-spotlight) pass `<AreaTrendLegend>` as the `legend` ReactNode prop.
 */
const StaticLegendItem = ({ label, color }: ChartCardLegendItem) => (
  <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
    <Box sx={{ width: 9, height: 9, borderRadius: '3px', bgcolor: color, flexShrink: 0 }} />
    <Typography variant="caption" color="text.secondary">
      {label}
    </Typography>
  </Stack>
);

/**
 * Standard "chart in a card" shell used by every chart card in the dashboard.
 *
 * Renders an outlined Paper containing:
 *   1. A title row — `title` on the left, optional `legend` and/or `actions` on
 *      the right.
 *   2. An optional `subtitle` line below the title row.
 *   3. A `children` slot for the chart body (AreaTrendChart, CSS bars, SVG, etc.).
 *
 * The actual chart body is left completely untouched — only the surrounding
 * scaffolding lives here.
 */
const ChartCard = ({
  title,
  subtitle,
  legend,
  actions,
  fillHeight = false,
  children,
}: ChartCardProps) => {
  const paperSx = fillHeight
    ? { p: 2, height: '100%', display: 'flex', flexDirection: 'column' }
    : { p: 2 };

  const legendNode = isLegendItemArray(legend) ? (
    <Stack direction="row" spacing={1.5} sx={{ flexShrink: 0 }}>
      {legend.map((item) => (
        <StaticLegendItem key={item.label} label={item.label} color={item.color} />
      ))}
    </Stack>
  ) : (
    legend
  );

  const hasRightContent = legendNode != null || actions != null;

  return (
    <Paper variant="outlined" sx={paperSx}>
      <Stack
        direction="row"
        sx={{
          alignItems: 'baseline',
          justifyContent: hasRightContent ? 'space-between' : 'flex-start',
          gap: 2,
          mb: subtitle ? 0.25 : 1,
          flexWrap: 'wrap',
        }}
      >
        <Typography variant="subtitle1">{title}</Typography>
        {hasRightContent && (
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexShrink: 0 }}>
            {legendNode}
            {actions}
          </Stack>
        )}
      </Stack>

      {subtitle && (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {subtitle}
        </Typography>
      )}

      {children}
    </Paper>
  );
};

export default ChartCard;
