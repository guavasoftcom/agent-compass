import { Box, InputBase, Typography, alpha, useTheme } from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import CheckIcon from '@mui/icons-material/Check';
import { SEVERITIES, type FacetKey, type LogFacets, type Severity } from '../logsApi';
import { severityColor } from './LogHistogram';

interface FacetDef {
  key: FacetKey;
  title: string;
}
const DEFS: FacetDef[] = [
  { key: 'severity', title: 'Severity' },
  { key: 'event', title: 'Event type' },
  { key: 'tool', title: 'Tool / server' },
];

export interface FacetSelections {
  severity: Set<string>;
  event: Set<string>;
  tool: Set<string>;
}

interface Props {
  facets: LogFacets | undefined;
  selections: FacetSelections;
  search: string;
  onSearchChange: (v: string) => void;
  onToggle: (key: FacetKey, value: string) => void;
  onClear: (key: FacetKey) => void;
}

const LogFacetRail = ({ facets, selections, search, onSearchChange, onToggle, onClear }: Props) => {
  const theme = useTheme();

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', overflowY: 'auto', py: 0.75 }}>
      <Box sx={{ position: 'relative', mx: 1.75, mt: 1.5, mb: 1 }}>
        <SearchIcon sx={{ position: 'absolute', left: 11, top: '50%', transform: 'translateY(-50%)', fontSize: 17, color: 'text.disabled' }} />
        <InputBase
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder="Search body & attributes…"
          sx={{
            width: '100%',
            height: 38,
            borderRadius: 1.25,
            border: 1,
            borderColor: 'divider',
            bgcolor: 'background.paper',
            fontSize: 13,
            pl: '33px',
            pr: 1.5,
            color: 'text.primary',
            transition: 'border-color .12s, box-shadow .12s',
            '&.Mui-focused': {
              borderColor: 'primary.main',
              boxShadow: (t) => `0 0 0 3px ${alpha(t.palette.primary.main, 0.18)}`,
            },
          }}
        />
      </Box>

      {DEFS.map((def) => {
        const rows = facets?.[def.key] ?? [];
        const active = selections[def.key];
        const ordered = def.key === 'severity'
          ? SEVERITIES.map((s) => rows.find((r) => r.value === s)).filter((r): r is { value: string; count: number } => !!r)
          : rows;
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
                sx={{ fontFamily: "'Sora', sans-serif", fontSize: 10.5, fontWeight: 700, letterSpacing: '1.1px', textTransform: 'uppercase', color: 'text.secondary' }}
              >
                {def.title}
              </Typography>
              {active.size ? (
                <Box
                  component="button"
                  onClick={() => onClear(def.key)}
                  sx={{ border: 'none', bgcolor: 'transparent', cursor: 'pointer', fontFamily: "'Space Grotesk', sans-serif", fontSize: 11, fontWeight: 600, color: 'primary.main' }}
                >
                  clear
                </Box>
              ) : null}
            </Box>
            {ordered.map((row) => {
              const on = active.has(row.value);
              const dot = def.key === 'severity' ? severityColor(theme, row.value as Severity) : null;
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
                    color: on ? 'text.primary' : 'text.secondary',
                    opacity: row.count ? 1 : 0.4,
                    transition: 'background-color .12s',
                    '&:hover': { bgcolor: 'action.hover', color: 'text.primary' },
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
                      border: on ? 'none' : `1.5px solid ${theme.palette.text.disabled}`,
                      bgcolor: on ? 'primary.main' : 'transparent',
                    }}
                  >
                    {on ? <CheckIcon sx={{ fontSize: 11, color: 'primary.contrastText' }} /> : null}
                  </Box>
                  {dot ? <Box sx={{ width: 8, height: 8, borderRadius: 0.5, bgcolor: dot, flexShrink: 0 }} /> : null}
                  <Box
                    component="span"
                    sx={{ flex: 1, fontSize: 13, fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}
                    title={row.value}
                  >
                    {row.value}
                  </Box>
                  <Box component="span" sx={{ fontSize: 12, color: 'text.disabled', fontVariantNumeric: 'tabular-nums', fontWeight: 500 }}>
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

export default LogFacetRail;
