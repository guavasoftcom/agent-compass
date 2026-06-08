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
