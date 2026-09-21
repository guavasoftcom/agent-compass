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
import { Box } from '@mui/material';

export interface SparklineProps {
  /** Bar heights as raw values; normalized internally. */
  values: number[];
  height?: number;
  /**
   * CSS color the bars fade from. Defaults to the theme's primary; pass a chart-palette color
   * when a card's bars should match the metric's dot elsewhere on the page.
   */
  color?: string;
  /**
   * Index of one bar to draw at full opacity — e.g. today within a period of days. Setting it also
   * dims every other bar (0.55 rather than the default 0.85) so the emphasis actually reads.
   */
  emphasizedIndex?: number;
  /**
   * First index of the bars that have no data yet (future days). From here on each bar is a faint,
   * fixed-height placeholder instead of a value-scaled bar, and is left out of the normalization
   * so a not-yet-recorded value can never squash the real ones.
   */
  placeholderFromIndex?: number;
  /** Pixels between bars; the default 3 is generous for a handful of bars and too wide for 24. */
  gap?: number;
  /**
   * Draw a bar whose value is zero as a one-pixel baseline tick in the bar's own color instead of
   * the 8% floor, so an empty stretch reads as a dashed line rather than as low activity.
   */
  zeroBarsAsBaseline?: boolean;
}

const DEFAULT_BAR_OPACITY = 0.85;
const DIMMED_BAR_OPACITY = 0.55;
const EMPHASIZED_BAR_OPACITY = 1;
const PLACEHOLDER_BAR_OPACITY = 0.16;
const PLACEHOLDER_BAR_HEIGHT_PX = 2;
// A string on purpose: MUI's sx reads a bare number <= 1 as a fraction of the parent (1 -> 100%).
const BASELINE_TICK_HEIGHT = '1px';
const DEFAULT_GAP_PX = 3;

/**
 * Tiny inline bar sparkline used on the "Total invocations" stat card.
 * Bars use the primary color fading to transparent, matching the Aurora mockup.
 */
const Sparkline = ({
  values,
  height = 28,
  color,
  emphasizedIndex,
  placeholderFromIndex,
  gap = DEFAULT_GAP_PX,
  zeroBarsAsBaseline = false,
}: SparklineProps) => {
  if (values.length === 0) {
    return null;
  }
  const measuredValues = placeholderFromIndex === undefined ? values : values.slice(0, placeholderFromIndex);
  const max = Math.max(...measuredValues, 1);
  return (
    <Box sx={{ display: 'flex', alignItems: 'flex-end', gap: `${gap}px`, height }}>
      {values.map((value, index) => {
        const isPlaceholder = placeholderFromIndex !== undefined && index >= placeholderFromIndex;
        const isBaselineTick = zeroBarsAsBaseline && !isPlaceholder && value <= 0;
        let opacity = emphasizedIndex === undefined ? DEFAULT_BAR_OPACITY : DIMMED_BAR_OPACITY;
        if (isPlaceholder) {
          opacity = PLACEHOLDER_BAR_OPACITY;
        } else if (index === emphasizedIndex) {
          opacity = EMPHASIZED_BAR_OPACITY;
        }
        return (
          <Box
            key={index}
            data-testid={isPlaceholder ? 'sparkline-placeholder-bar' : 'sparkline-bar'}
            sx={(theme) => {
              const barColor = color ?? theme.palette.primary.main;
              let barHeight: string | number = `${Math.max(8, (value / max) * 100)}%`;
              if (isPlaceholder) {
                barHeight = PLACEHOLDER_BAR_HEIGHT_PX;
              } else if (isBaselineTick) {
                barHeight = BASELINE_TICK_HEIGHT;
              }
              return {
                flex: 1,
                minWidth: 2,
                height: barHeight,
                borderRadius: '2px',
                opacity,
                background:
                  isPlaceholder || isBaselineTick
                    ? barColor
                    : `linear-gradient(180deg, ${barColor}, transparent)`,
              };
            }}
          />
        );
      })}
    </Box>
  );
};

export default Sparkline;
