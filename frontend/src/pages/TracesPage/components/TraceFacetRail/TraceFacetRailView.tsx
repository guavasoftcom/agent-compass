import { Box, Typography, useTheme } from '@mui/material';
import CheckIcon from '@mui/icons-material/Check';
import {
  DURATION_BUCKETS,
  type FacetKey,
  type TraceFacets,
} from '../../tracesApi';
import { serviceColor } from '../traceColors';
import SearchInput from '../../../../components/SearchInput';

interface FacetDefinition {
  key: FacetKey;
  title: string;
  dotType?: 'status' | 'service';
  /** Render values in a monospace font (identifiers rather than prose). */
  monospace?: boolean;
}
const FACET_DEFINITIONS: FacetDefinition[] = [
  { key: 'status', title: 'Status', dotType: 'status' },
  { key: 'operation', title: 'Operation', dotType: 'service', monospace: true },
  { key: 'service', title: 'Service', dotType: 'service', monospace: true },
  { key: 'duration', title: 'Duration' },
  { key: 'session', title: 'Session', monospace: true },
];

export interface TraceFacetSelections {
  status: Set<string>;
  operation: Set<string>;
  service: Set<string>;
  duration: Set<string>;
  session: Set<string>;
}

export interface TraceFacetRailViewProps {
  facets: TraceFacets | undefined;
  serviceForValue: (key: FacetKey, value: string) => string | null;
  selections: TraceFacetSelections;
  search: string;
  onSearchChange: (search: string) => void;
  onToggle: (key: FacetKey, value: string) => void;
  onClear: (key: FacetKey) => void;
}

const DURATION_LABEL = new Map(DURATION_BUCKETS.map((b) => [b.id, b.label]));

const TraceFacetRailView = ({
  facets,
  serviceForValue,
  selections,
  search,
  onSearchChange,
  onToggle,
  onClear,
}: TraceFacetRailViewProps) => {
  const theme = useTheme();

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        overflowY: 'auto',
        py: 0.75,
      }}
    >
      <SearchInput
        value={search}
        onChange={onSearchChange}
        placeholder="Search trace / session / op…"
        sx={{ mx: 1.75, mt: 1.5, mb: 1 }}
      />

      {FACET_DEFINITIONS.map((def) => {
        const rows = facets?.[def.key] ?? [];
        const active = selections[def.key];
        return (
          <Box key={def.key} sx={{ px: 0.75, pt: 0.75, pb: 0.5 }}>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                px: 1.25,
                pt: 1.1,
                pb: 0.75,
              }}
            >
              <Typography
                component="span"
                sx={{
                  fontFamily: "'Sora', sans-serif",
                  fontSize: 10.5,
                  fontWeight: 700,
                  letterSpacing: '1.1px',
                  textTransform: 'uppercase',
                  color: 'text.secondary',
                }}
              >
                {def.title}
              </Typography>
              {active.size ? (
                <Box
                  component="button"
                  onClick={() => onClear(def.key)}
                  sx={{
                    border: 'none',
                    bgcolor: 'transparent',
                    cursor: 'pointer',
                    fontFamily: "'Space Grotesk', sans-serif",
                    fontSize: 11,
                    fontWeight: 600,
                    color: 'primary.main',
                  }}
                >
                  clear
                </Box>
              ) : null}
            </Box>
            {rows.map((row) => {
              const isSelected = active.has(row.value);
              let dotColor: string | null = null;
              if (def.dotType === 'status') {
                dotColor =
                  row.value === 'error'
                    ? theme.palette.error.main
                    : theme.palette.primary.main;
              } else if (def.dotType === 'service') {
                const serviceName = serviceForValue(def.key, row.value);
                dotColor = serviceName ? serviceColor(serviceName) : null;
              }
              const label =
                def.key === 'duration'
                  ? (DURATION_LABEL.get(row.value) ?? row.value)
                  : row.value;
              return (
                <Box
                  key={row.value}
                  onClick={() => onToggle(def.key, row.value)}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1.1,
                    mx: 0.5,
                    my: '1px',
                    px: 1.1,
                    py: 0.75,
                    borderRadius: 1.1,
                    cursor: 'pointer',
                    color: isSelected ? 'text.primary' : 'text.secondary',
                    opacity: row.count ? 1 : 0.4,
                    transition: 'background-color .12s',
                    '&:hover': {
                      bgcolor: 'action.hover',
                      color: 'text.primary',
                    },
                  }}
                >
                  <Box
                    sx={{
                      width: 15,
                      height: 15,
                      borderRadius: 0.75,
                      flexShrink: 0,
                      display: 'grid',
                      placeItems: 'center',
                      border: isSelected
                        ? 'none'
                        : `1.5px solid ${theme.palette.text.disabled}`,
                      bgcolor: isSelected ? 'primary.main' : 'transparent',
                    }}
                  >
                    {isSelected ? (
                      <CheckIcon
                        sx={{ fontSize: 11, color: 'primary.contrastText' }}
                      />
                    ) : null}
                  </Box>
                  {dotColor ? (
                    <Box
                      sx={{
                        width: 8,
                        height: 8,
                        borderRadius: 0.5,
                        bgcolor: dotColor,
                        flexShrink: 0,
                      }}
                    />
                  ) : null}
                  <Box
                    component="span"
                    sx={{
                      flex: 1,
                      fontSize: def.monospace ? 12.5 : 13,
                      fontFamily: def.monospace
                        ? "'JetBrains Mono', monospace"
                        : undefined,
                      fontWeight: 500,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                    title={label}
                  >
                    {label}
                  </Box>
                  <Box
                    component="span"
                    sx={{
                      fontSize: 12,
                      color: 'text.disabled',
                      fontVariantNumeric: 'tabular-nums',
                      fontWeight: 500,
                    }}
                  >
                    {row.count.toLocaleString()}
                  </Box>
                </Box>
              );
            })}
          </Box>
        );
      })}
    </Box>
  );
};

export default TraceFacetRailView;
