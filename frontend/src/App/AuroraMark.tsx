import { Box } from '@mui/material';

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
        color: '#fff',
        fontFamily: "'Sora', sans-serif",
        fontWeight: 800,
        fontSize: Math.round(size * 0.5),
        lineHeight: 1,
        background: 'linear-gradient(135deg, #8b5cff, #ff6ad5)',
        boxShadow: '0 6px 18px rgba(139, 92, 255, 0.45)',
      }}
    >
      A
    </Box>
  );
};

export default AuroraMark;
