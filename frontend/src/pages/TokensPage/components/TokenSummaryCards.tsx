import type { ReactNode } from 'react';
import { Box, Paper, Typography } from '@mui/material';

export interface TokenSummaryCard {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  /** Paint the value with the Aurora violet→pink gradient. */
  accent?: boolean;
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
        <Paper key={card.label} variant="outlined" sx={{ p: 2, height: '100%' }}>
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ letterSpacing: 0.4, textTransform: 'uppercase', fontWeight: 600 }}
          >
            {card.label}
          </Typography>
          <Box
            sx={{
              mt: 0.75,
              fontFamily: "'Sora', sans-serif",
              fontWeight: 800,
              fontSize: 30,
              lineHeight: 1.05,
              letterSpacing: '-0.6px',
              minHeight: 36,
              display: 'flex',
              alignItems: 'center',
              ...(card.accent
                ? {
                    backgroundImage: 'linear-gradient(120deg, #8b5cff, #ff6ad5)',
                    WebkitBackgroundClip: 'text',
                    backgroundClip: 'text',
                    color: 'transparent',
                  }
                : { color: 'text.primary' }),
            }}
          >
            {card.value ?? '—'}
          </Box>
          {card.sub && (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1.25 }}>
              {card.sub}
            </Typography>
          )}
        </Paper>
      ))}
    </Box>
  );
};

export default TokenSummaryCards;
