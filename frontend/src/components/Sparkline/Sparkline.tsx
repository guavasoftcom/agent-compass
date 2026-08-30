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
}

/**
 * Tiny inline bar sparkline used on the "Total invocations" stat card.
 * Bars use the primary color fading to transparent, matching the Aurora mockup.
 */
const Sparkline = ({ values, height = 28 }: SparklineProps) => {
  if (values.length === 0) {
    return null;
  }
  const max = Math.max(...values, 1);
  return (
    <Box sx={{ display: 'flex', alignItems: 'flex-end', gap: '3px', height }}>
      {values.map((value, index) => (
        <Box
          key={index}
          sx={(theme) => ({
            flex: 1,
            minWidth: 2,
            height: `${Math.max(8, (value / max) * 100)}%`,
            borderRadius: '2px',
            opacity: 0.85,
            background: `linear-gradient(180deg, ${theme.palette.primary.main}, transparent)`,
          })}
        />
      ))}
    </Box>
  );
};

export default Sparkline;
