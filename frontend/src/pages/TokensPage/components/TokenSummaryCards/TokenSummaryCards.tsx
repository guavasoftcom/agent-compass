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
