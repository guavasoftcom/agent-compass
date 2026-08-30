import type { ReactNode } from 'react';
import { Box, Typography, alpha, useTheme } from '@mui/material';

export interface SectionHeaderProps {
  label: string;
  /** Section accent color (resolved by the caller — theme token or `theme/colors.ts` hue). */
  accentColor: string;
  icon: ReactNode;
}

/**
 * Full-width section label row for the Trend Report diff card (Cost / Token efficiency /
 * Reliability / Activity) — a small 22×22px icon chip tinted at ~18% opacity of the section's
 * accent color, next to an uppercase label. Spans the whole card width (not the 3-column metric
 * grid), matching the reference design's `.section-label` band.
 */
const SectionHeader = ({ label, accentColor, icon }: SectionHeaderProps) => {
  const theme = useTheme();

  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.125,
        px: 3,
        py: 1.25,
        backgroundColor: theme.custom.surfaceMuted,
        borderBottom: `1px solid ${theme.palette.divider}`,
        borderTop: `1px solid ${theme.palette.divider}`,
      }}
    >
      <Box
        sx={{
          width: 22,
          height: 22,
          borderRadius: '7px',
          display: 'grid',
          placeItems: 'center',
          flexShrink: 0,
          backgroundColor: alpha(accentColor, 0.18),
          color: accentColor,
        }}
      >
        {icon}
      </Box>
      <Typography sx={{ typography: 'eyebrowSm', color: 'text.secondary' }}>{label}</Typography>
    </Box>
  );
};

export default SectionHeader;
