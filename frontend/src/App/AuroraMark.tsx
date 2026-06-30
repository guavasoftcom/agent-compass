import { alpha, Box } from '@mui/material';
import { auroraColors, gradients, neutralColors } from '../theme/colors';
import { fontFamilies } from '../theme/typography';

export interface AuroraMarkProps {
  size?: number;
}

/**
 * Aurora brand mark: a rounded square with a violet→pink gradient and a bold "A".
 * Replaces the compass circuit glyph in the Aurora retheme.
 */
const AuroraMark = ({ size = 36 }: AuroraMarkProps) => {
  return (
    <Box
      sx={{
        width: size,
        height: size,
        borderRadius: `${Math.round(size * 0.3)}px`,
        display: 'grid',
        placeItems: 'center',
        flexShrink: 0,
        color: neutralColors.white,
        fontFamily: fontFamilies.display,
        fontWeight: 800,
        fontSize: Math.round(size * 0.5),
        lineHeight: 1,
        background: gradients.auroraAction,
        boxShadow: `0 6px 18px ${alpha(auroraColors.violetLight, 0.45)}`,
      }}
    >
      A
    </Box>
  );
};

export default AuroraMark;
