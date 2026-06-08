import type { ReactNode } from 'react';
import { Box, Paper, Typography } from '@mui/material';

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
  /** Optional slot rendered below the sub (e.g. a sparkline). */
  children?: ReactNode;
}

const StatCard = ({ label, value, sub, accent = false, children }: StatCardProps) => {
  return (
    <Paper variant="outlined" sx={{ p: 2, height: '100%' }}>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ letterSpacing: 0.4, textTransform: 'uppercase', fontWeight: 600 }}
      >
        {label}
      </Typography>
      <Typography
        variant="h4"
        sx={{
          mt: 0.75,
          fontWeight: 800,
          lineHeight: 1.05,
          ...(accent
            ? {
                backgroundImage: 'linear-gradient(120deg, #8b5cff, #ff6ad5)',
                WebkitBackgroundClip: 'text',
                backgroundClip: 'text',
                color: 'transparent',
              }
            : { color: 'text.primary' }),
        }}
      >
        {value ?? '—'}
      </Typography>
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
