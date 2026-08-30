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
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { alpha, Box, LinearProgress, Paper, Stack, Tooltip, Typography } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import { neutralColors } from '../../theme/colors';
import { fontFamilies } from '../../theme/typography';
import { radii } from '../../theme/theme';

export interface DonutSlice {
  label: string;
  value: number;
  color: string;
  /** Render the legend label dimmed + italic (e.g. an "unknown" bucket). */
  muted?: boolean;
  /**
   * Model id → calls for this row, used to light up the `coverageTicks` dots.
   * Omit (or leave a model out) when the row carries no per-model split.
   */
  coverageByModel?: Record<string, number>;
}

/** One model in the fixed left-to-right order shared by `coverageTicks` and `legendCaption`. */
export interface DonutCoverageModel {
  /** Key into a slice's `coverageByModel` map (the raw model id). */
  key: string;
  /** Short display name (e.g. "Sonnet 5"). */
  label: string;
  color: string;
}

export interface DonutCardProps {
  title: string;
  /** Optional muted caption line under the title (e.g. "4 models identified, ranked by spend"). */
  description?: ReactNode;
  slices: DonutSlice[];
  /** Big number shown in the ring center (e.g. a total or a percentage). */
  centerValue: ReactNode;
  /** Small uppercase caption under the center value (e.g. "calls"). */
  centerLabel: string;
  /** Show a 1-based rank number before each legend row. */
  ranked?: boolean;
  /**
   * When `'horizontal'`, the ring sits to the left of the legend (flex row,
   * ring fixed-size, legend flexes to fill the rest) instead of the default
   * stack (ring centered on top, legend below). Use `'horizontal'` for a
   * card that spans the full content width on its own — matches the Cost
   * page's "Model mix" card; the default `'vertical'` suits a card paired
   * side-by-side with another (e.g. Skill mix / Subagent mix in a 2-column
   * grid), where there isn't enough width for a row layout to read well.
   */
  orientation?: 'vertical' | 'horizontal';
  /**
   * When `true`, each legend row gets a full-width progress bar (its share of
   * `sum`) below the label/value line, instead of a hairline divider between
   * rows. Matches the mockup's ranked-donut-list rows (rank + dot + label +
   * value/% on one line, a colored bar beneath).
   */
  showBars?: boolean;
  hasData?: boolean;
  isLoading?: boolean;
  emptyLabel?: string;
  /**
   * Fixed model order for a per-row "which models touched this" tick group,
   * inserted between the legend name and its value. Each dot is lit with the
   * model's color when that row's `coverageByModel[key] > 0`, otherwise a flat
   * gray square. Omit to render the legend without ticks.
   */
  coverageTicks?: DonutCoverageModel[];
  /**
   * Caption row below the legend keying each `coverageTicks` color to its model
   * name. Typically the same list passed to `coverageTicks`. Omit to skip the
   * caption row.
   */
  legendCaption?: DonutCoverageModel[];
  /**
   * Renders each legend row's raw value. Defaults to a thousands-separated
   * count, which is right for every caller whose slices are counts. Pass a
   * formatter when they are not — the Settings page's storage donut passes
   * `formatBytes`, since "4,491,976,704" is not a size anyone reads. Also
   * used to render each `coverageTicks` tooltip's amount, so a caller in a
   * non-count mode (e.g. Skills & Subagents' Cost toggle) gets consistently
   * formatted numbers in both the legend and the tick tooltips.
   */
  formatSliceValue?: (value: number) => string;
  /**
   * Unit word appended after `formatSliceValue`'s output in each coverage
   * tick's tooltip (e.g. "40 calls"). Defaults to `'calls'`, matching the
   * only current caller of `coverageTicks`. Pass `''` to omit the suffix —
   * e.g. when `formatSliceValue` already renders a self-describing value
   * like a currency string ("$12.34").
   */
  coverageValueLabel?: string;
}

const SIZE = 200;
const STROKE = 20;
const RADIUS = 80; // leaves room for the round caps inside the viewBox
const CENTER = SIZE / 2;
const CIRC = 2 * Math.PI * RADIUS;
const GAP_PX = 6; // gap between segments

// Center value auto-shrink: the ring's inner edge (where the stroke stops),
// minus a little breathing room so digits never sit flush against it.
const INNER_DIAMETER = 2 * (RADIUS - STROKE / 2);
const CENTER_TEXT_HORIZONTAL_PADDING = 14;
const CENTER_VALUE_AVAILABLE_WIDTH = INNER_DIAMETER - CENTER_TEXT_HORIZONTAL_PADDING * 2;
const CENTER_VALUE_BASE_FONT_SIZE = 36;
const CENTER_VALUE_MINIMUM_FONT_SIZE = 16;

/**
 * Aurora donut: a stroke-based ring (rounded caps + small gaps between segments),
 * a total in the center, and a legend below — matching the design mockup. Shares
 * the dashboard palette via each slice's `color`. Not built on @mui/x-charts so the
 * ring style matches the rest of the Aurora UI exactly.
 */
const DonutCard = ({
  title,
  description,
  slices,
  centerValue,
  centerLabel,
  ranked = false,
  orientation = 'vertical',
  showBars = false,
  hasData = true,
  isLoading = false,
  emptyLabel = 'No data in this window.',
  coverageTicks,
  legendCaption,
  formatSliceValue = (value: number) => value.toLocaleString(),
  coverageValueLabel = 'calls',
}: DonutCardProps) => {
  const theme = useTheme();

  // Measures the center value at its natural (base-size) width via a hidden
  // probe, then shrinks the visible font size just enough to keep it inside
  // the ring's inner edge — so a long value (e.g. "$1,897.92") never grows
  // wide enough to render on top of the ring stroke the way a fixed font
  // size did. Runs before paint (useLayoutEffect), so there's no visible
  // flash at the oversized base size first.
  const centerValueProbeRef = useRef<HTMLSpanElement>(null);
  const [centerValueFontSize, setCenterValueFontSize] = useState(CENTER_VALUE_BASE_FONT_SIZE);
  useLayoutEffect(() => {
    const probe = centerValueProbeRef.current;
    if (!probe) {
      return;
    }
    const naturalWidth = probe.scrollWidth;
    setCenterValueFontSize(
      naturalWidth > CENTER_VALUE_AVAILABLE_WIDTH
        ? Math.max(
            CENTER_VALUE_MINIMUM_FONT_SIZE,
            Math.floor(CENTER_VALUE_BASE_FONT_SIZE * (CENTER_VALUE_AVAILABLE_WIDTH / naturalWidth)),
          )
        : CENTER_VALUE_BASE_FONT_SIZE,
    );
  }, [centerValue]);

  const sum = slices.reduce((acc, slice) => acc + slice.value, 0);
  const trackColor =
    theme.palette.mode === 'dark'
      ? alpha(neutralColors.white, 0.07)
      : alpha(neutralColors.inkLight, 0.07);
  const tickTrackColor = theme.custom?.progressTrack ?? trackColor;

  // Build one arc per slice, positioned by rotating from -90° (12 o'clock).
  const positiveSlices = slices.filter((slice) => slice.value > 0);
  const arcs = positiveSlices.map((slice, index) => {
    const precedingValue = positiveSlices
      .slice(0, index)
      .reduce((acc, preceding) => acc + preceding.value, 0);
    const fraction = slice.value / sum;
    const startAngle = -90 + (precedingValue / sum) * 360;
    const rawLen = fraction * CIRC;
    // Subtract a gap so rounded caps don't overlap; keep a minimum sliver.
    const dash = Math.max(rawLen - (slices.length > 1 ? GAP_PX : 0), 2);
    return { color: slice.color, dash, startAngle };
  });

  return (
    <Paper variant="outlined" sx={{ p: 2, height: '100%' }}>
      <Typography variant="subtitle1" gutterBottom={!description}>
        {title}
      </Typography>
      {description && (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          {description}
        </Typography>
      )}

      {(!hasData || sum === 0) && !isLoading ? (
        <Typography color="text.secondary">{emptyLabel}</Typography>
      ) : (
        <>
        <Box
          sx={
            orientation === 'horizontal'
              ? { display: 'flex', gap: 4, alignItems: 'center', flexWrap: 'wrap', mt: 1.75 }
              : undefined
          }
        >
          <Box
            sx={{
              position: 'relative',
              width: SIZE,
              height: SIZE,
              flexShrink: 0,
              ...(orientation === 'vertical' ? { mx: 'auto', my: 1 } : {}),
            }}
          >
            <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`}>
              <circle
                cx={CENTER}
                cy={CENTER}
                r={RADIUS}
                fill="none"
                stroke={trackColor}
                strokeWidth={STROKE}
              />
              {arcs.map((arc, index) => (
                <circle
                  key={index}
                  cx={CENTER}
                  cy={CENTER}
                  r={RADIUS}
                  fill="none"
                  stroke={arc.color}
                  strokeWidth={STROKE}
                  strokeLinecap="round"
                  strokeDasharray={`${arc.dash} ${CIRC - arc.dash}`}
                  transform={`rotate(${arc.startAngle} ${CENTER} ${CENTER})`}
                />
              ))}
            </svg>
            {/* Hidden probe, rendered at the base font size purely to measure the
                value's natural width — never shown, never counted for layout. */}
            <Box
              ref={centerValueProbeRef}
              aria-hidden
              sx={{
                position: 'absolute',
                visibility: 'hidden',
                whiteSpace: 'nowrap',
                pointerEvents: 'none',
                fontFamily: fontFamilies.display,
                fontWeight: 800,
                fontSize: CENTER_VALUE_BASE_FONT_SIZE,
                letterSpacing: -1,
              }}
            >
              {centerValue}
            </Box>
            <Box
              sx={{
                position: 'absolute',
                inset: 0,
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                pointerEvents: 'none',
                px: `${CENTER_TEXT_HORIZONTAL_PADDING}px`,
              }}
            >
              <Typography
                sx={{
                  fontFamily: fontFamilies.display,
                  fontWeight: 800,
                  fontSize: centerValueFontSize,
                  lineHeight: 1,
                  letterSpacing: -1,
                  whiteSpace: 'nowrap',
                }}
              >
                {centerValue}
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                noWrap
                sx={{
                  letterSpacing: 1,
                  textTransform: 'uppercase',
                  mt: 0.5,
                  maxWidth: '100%',
                  textOverflow: 'ellipsis',
                }}
              >
                {centerLabel}
              </Typography>
            </Box>
          </Box>

          <Stack
            sx={{
              mt: orientation === 'horizontal' ? 0 : 1.5,
              ...(orientation === 'horizontal' ? { flex: 1, minWidth: 240 } : {}),
            }}
          >
            {slices.map((slice, index) => {
              const pct = sum > 0 ? (slice.value / sum) * 100 : 0;
              return (
                <Box
                  key={slice.label}
                  sx={{
                    py: showBars ? 0 : 0.9,
                    mb: showBars ? 1.25 : 0,
                    borderBottom: !showBars && index < slices.length - 1 ? '1px solid' : 'none',
                    borderColor: 'divider',
                  }}
                >
                <Stack
                  direction="row"
                  spacing={1.25}
                  sx={{
                    alignItems: 'center',
                    mb: showBars ? 0.75 : 0,
                  }}
                >
                  {ranked && (
                    <Typography
                      variant="body2"
                      color="text.disabled"
                      sx={{ width: 16, flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}
                    >
                      {index + 1}
                    </Typography>
                  )}
                  <Box
                    sx={{
                      width: 10,
                      height: 10,
                      borderRadius: '3px',
                      flexShrink: 0,
                      bgcolor: slice.color,
                    }}
                  />
                  <Typography
                    variant="body2"
                    noWrap
                    sx={{
                      flex: 1,
                      minWidth: 0,
                      fontWeight: 600,
                      ...(slice.muted && { fontStyle: 'italic', color: 'text.disabled' }),
                    }}
                  >
                    {slice.label}
                  </Typography>
                  {coverageTicks && coverageTicks.length > 0 && (
                    <Stack direction="row" spacing={0.6} sx={{ flexShrink: 0 }}>
                      {coverageTicks.map((model) => {
                        const calls = slice.coverageByModel?.[model.key] ?? 0;
                        const lit = calls > 0;
                        return (
                          <Tooltip
                            key={model.key}
                            title={`${model.label} · ${lit ? `${formatSliceValue(calls)}${coverageValueLabel ? ` ${coverageValueLabel}` : ''}` : 'not used'}`}
                            placement="top"
                            arrow
                            disableInteractive
                          >
                            <Box
                              sx={{
                                width: 8,
                                height: 8,
                                borderRadius: '3px',
                                bgcolor: lit ? model.color : tickTrackColor,
                              }}
                            />
                          </Tooltip>
                        );
                      })}
                    </Stack>
                  )}
                  <Typography
                    variant="body2"
                    color="text.secondary"
                    sx={{ flexShrink: 0, whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}
                  >
                    <Box component="span" sx={{ color: 'text.primary', fontWeight: 700 }}>
                      {formatSliceValue(slice.value)}
                    </Box>{' '}
                    · {pct.toFixed(1)}%
                  </Typography>
                </Stack>
                {showBars && (
                  <LinearProgress
                    variant="determinate"
                    value={pct}
                    sx={{
                      height: 6,
                      borderRadius: radii.pill,
                      bgcolor: trackColor,
                      '& .MuiLinearProgress-bar': { backgroundColor: slice.color, borderRadius: radii.pill },
                    }}
                  />
                )}
                </Box>
              );
            })}
          </Stack>
        </Box>

          {legendCaption && legendCaption.length > 0 && (
            <Stack
              direction="row"
              spacing={1.75}
              sx={{
                mt: 1.5,
                pt: 1.5,
                borderTop: '1px solid',
                borderColor: 'divider',
                flexWrap: 'wrap',
              }}
            >
              {legendCaption.map((model) => (
                <Stack key={model.key} direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                  <Box
                    sx={{ width: 8, height: 8, borderRadius: '3px', flexShrink: 0, bgcolor: model.color }}
                  />
                  <Typography variant="caption" color="text.secondary" sx={{ fontSize: 11.5 }}>
                    {model.label}
                  </Typography>
                </Stack>
              ))}
            </Stack>
          )}
        </>
      )}
    </Paper>
  );
};

export default DonutCard;
