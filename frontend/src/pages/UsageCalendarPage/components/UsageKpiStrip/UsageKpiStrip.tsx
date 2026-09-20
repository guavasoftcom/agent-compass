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
import Sparkline from '../../../../components/Sparkline';
import StatCard from '../../../../components/StatCard';
import { colorForIndex } from '../../../../theme/theme';
import type { KpiCardModel } from '../../usageCalendarDerivations';

export interface UsageKpiStripProps {
  cards: KpiCardModel[];
  /**
   * Whether each card carries its per-day bars. On for the week view (seven bars read well); off for the
   * month view, where 28-31 bars squeezed into one card are noise rather than signal.
   */
  showBars: boolean;
}

const KPI_VALUE_FONT_SIZE = 23;
const KPI_BAR_HEIGHT = 46;

/**
 * The four KPI cards above the calendar: a StatCard each, headline number and delta. In the week view a
 * per-day bar Sparkline sits beneath; today's bar is drawn at full strength and any day still to come is
 * a faint placeholder.
 */
const UsageKpiStrip = ({ cards, showBars }: UsageKpiStripProps) => (
  <Box
    sx={{
      display: 'grid',
      gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(4, minmax(0, 1fr))' },
      gap: 1.75,
    }}
  >
    {cards.map((card) => (
      <StatCard
        key={card.id}
        label={card.label}
        displayFont
        displayFontSize={KPI_VALUE_FONT_SIZE}
        value={
          <>
            {card.value}
            {card.valueSuffix ? (
              <Box component="small" sx={{ ml: 0.75, fontSize: 12.5, fontWeight: 600, color: 'text.secondary' }}>
                {card.valueSuffix}
              </Box>
            ) : null}
          </>
        }
        trend={card.change ? { delta: card.change.label, direction: card.change.direction } : undefined}
      >
        {showBars ? (
          <Sparkline
            values={card.series.values}
            height={KPI_BAR_HEIGHT}
            color={colorForIndex(card.colorIndex)}
            emphasizedIndex={card.series.todayIndex}
            placeholderFromIndex={card.series.firstFutureIndex}
          />
        ) : null}
      </StatCard>
    ))}
  </Box>
);

export default UsageKpiStrip;
