import { alpha, Box } from '@mui/material';
import { type TokenBreakdown } from '../../../TracesPage/tokenBreakdown';
import { SectionTitle } from './dockParts';
import { radii } from '../../../../theme/theme';

// Aurora sync: token breakdown table now includes cache_read as a plain row
// (input / output / cache_creation / cache_read) instead of a separately
// dashed-off line — the total combines all four in the section header count.
const TokensSection = ({ tokens }: { tokens: TokenBreakdown }) => {
  if (tokens.total <= 0) {
    return null;
  }
  return (
    <Box>
      <SectionTitle count={tokens.total} tone="token">Tokens</SectionTitle>
      <Box sx={{ border: 1, borderColor: (t) => alpha(t.palette.warning.main, 0.42), borderRadius: radii.lg, overflow: 'hidden', bgcolor: (t) => alpha(t.palette.warning.main, 0.08) }}>
        {([['input', tokens.input], ['output', tokens.output], ['cache_creation', tokens.cacheCreate], ['cache_read', tokens.cacheRead]] as const).map(([k, v]) => (
          <Box key={k} sx={{ display: 'grid', gridTemplateColumns: 'minmax(120px,auto) 1fr', gap: 1.75, px: 1.75, py: 1, borderBottom: 1, borderColor: (t) => alpha(t.palette.warning.main, 0.22), fontSize: 12.5, '&:last-of-type': { borderBottom: 0 } }}>
            <Box component="span" sx={{ typography: 'mono', color: (t) => `color-mix(in srgb, ${t.palette.warning.main} 78%, ${t.palette.text.secondary})`, whiteSpace: 'nowrap' }}>{k}</Box>
            <Box component="span" sx={{ typography: 'mono', fontWeight: 600, color: 'warning.main' }}>{v.toLocaleString()}</Box>
          </Box>
        ))}
      </Box>
    </Box>
  );
};

export default TokensSection;
