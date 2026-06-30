import { Box, useTheme } from '@mui/material';
import { SEVERITIES, type Severity } from '../../logsApi';
import { severityColor } from '../severity';

interface SeverityLegendProps {
  facetSeverity: { value: string; count: number }[];
  hiddenSeverities: Set<Severity>;
  onToggleSeverity: (severity: Severity) => void;
}

// Clickable severity chips (swatch + label + facet count) that double as the
// histogram legend: clicking one mutes/unmutes that severity across the chart.
// Rendered in LogHistogramChart's header row.
const SeverityLegend = ({
  facetSeverity,
  hiddenSeverities,
  onToggleSeverity,
}: SeverityLegendProps) => {
  const theme = useTheme();
  const severityCount = (severity: Severity) =>
    facetSeverity.find((facet) => facet.value === severity)?.count ?? 0;

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.75 }}>
      {SEVERITIES.map((severity) => {
        const isHidden = hiddenSeverities.has(severity);
        return (
          <Box
            key={severity}
            onClick={() => onToggleSeverity(severity)}
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 0.75,
              fontSize: 12,
              fontWeight: 500,
              color: 'text.secondary',
              cursor: 'pointer',
              userSelect: 'none',
              opacity: isHidden ? 0.34 : 1,
              textDecoration: isHidden ? 'line-through' : 'none',
              '&:hover': { color: 'text.primary' },
            }}
          >
            <Box
              sx={{
                width: 10,
                height: 10,
                borderRadius: 0.75,
                bgcolor: severityColor(theme, severity),
              }}
            />
            {`${severity[0]}${severity.slice(1).toLowerCase()}`}
            <Box
              component="span"
              sx={{
                color: 'text.disabled',
                fontVariantNumeric: 'tabular-nums',
              }}
            >
              {severityCount(severity).toLocaleString()}
            </Box>
          </Box>
        );
      })}
    </Box>
  );
};

export default SeverityLegend;
