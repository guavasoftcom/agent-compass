import { Box } from '@mui/material';
import type { SeriesVisibility } from './useSeriesVisibility';

export interface AreaTrendLegendItem {
  label: string;
  color: string;
}

export interface AreaTrendLegendProps {
  items: AreaTrendLegendItem[];
  visibility: SeriesVisibility;
}

/**
 * Interactive legend for AreaTrendChart: hover a chip to spotlight that series
 * (others dim in the chart), click to toggle it off/on. Mirrors the design
 * preview's top-right legend.
 */
const AreaTrendLegend = ({ items, visibility }: AreaTrendLegendProps) => {
  const { active, toggle, setFocused } = visibility;
  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: '5px 8px', justifyContent: 'flex-end' }}>
      {items.map((item, index) => {
        const isOff = !active[index];
        return (
          <Box
            key={item.label}
            role="button"
            tabIndex={0}
            onClick={() => toggle(index)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                toggle(index);
              }
            }}
            onMouseEnter={() => setFocused(item.label)}
            onMouseLeave={() => setFocused(null)}
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 0.75,
              fontSize: 12,
              lineHeight: 1.4,
              color: 'text.primary',
              cursor: 'pointer',
              userSelect: 'none',
              px: 1.125,
              py: 0.375,
              borderRadius: '8px',
              border: '1px solid transparent',
              opacity: isOff ? 0.42 : 1,
              transition: 'background .12s, border-color .12s, opacity .12s',
              '&:hover': { bgcolor: 'action.hover', borderColor: 'divider' },
              '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: 2 },
            }}
          >
            <Box
              sx={{
                width: 10,
                height: 10,
                borderRadius: '3px',
                flexShrink: 0,
                bgcolor: isOff ? 'text.disabled' : item.color,
              }}
            />
            {item.label}
          </Box>
        );
      })}
    </Box>
  );
};

export default AreaTrendLegend;
