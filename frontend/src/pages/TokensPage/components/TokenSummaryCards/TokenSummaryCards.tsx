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
import type { ReactNode } from 'react';
import { Box } from '@mui/material';
import StatCard from '../../../../components/StatCard/StatCard';

export interface TokenSummaryCard {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  /** Paint the value with the Aurora violet→pink gradient. */
  accent?: boolean;
  /** Forwarded to `StatCard`'s `infoTooltip` — an inline info-icon caveat next to the label. */
  infoTooltip?: ReactNode;
  /** Forwarded to `StatCard`'s `infoTooltipSeverity`. */
  infoTooltipSeverity?: 'info' | 'warning';
}

export interface TokenSummaryCardsProps {
  cards: TokenSummaryCard[];
}

/**
 * Top-row summary KPIs for the Token Usage page: four compact cards
 * (Total cost · Total tokens · Models used · Top model). Total cost is the
 * window's spend (not a clock-hour burn rate), painted as the accent hero.
 */
const TokenSummaryCards = ({ cards }: TokenSummaryCardsProps) => {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(4, 1fr)' },
        gap: 2,
      }}
    >
      {cards.map((card) => (
        <StatCard
          key={card.label}
          label={card.label}
          value={card.value}
          sub={card.sub}
          accent={card.accent}
          infoTooltip={card.infoTooltip}
          infoTooltipSeverity={card.infoTooltipSeverity}
          displayFont
        />
      ))}
    </Box>
  );
};

export default TokenSummaryCards;
