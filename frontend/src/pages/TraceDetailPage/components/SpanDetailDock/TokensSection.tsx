import { alpha, Box, Tooltip } from '@mui/material';
import { type TokenBreakdown } from '../../../TracesPage/tokenBreakdown';
import { SectionTitle } from './dockParts';
import { radii } from '../../../../theme/theme';

// Token breakdown table (input / output / cache_creation, plus a separated
// cache_read row billed at the reduced rate). Renders nothing with no tokens.
const TokensSection = ({ tokens }: { tokens: TokenBreakdown }) => {
  if (tokens.total <= 0) {
    return null;
  }
  return (
    <Box>
      <SectionTitle count={tokens.total} tone="token">Tokens</SectionTitle>
      <Box sx={{ border: 1, borderColor: (t) => alpha(t.palette.warning.main, 0.42), borderRadius: radii.lg, overflow: 'hidden', bgcolor: (t) => alpha(t.palette.warning.main, 0.08) }}>
        {([['input', tokens.input], ['output', tokens.output], ['cache_creation', tokens.cacheCreate]] as const).map(([k, v]) => (
          <Box key={k} sx={{ display: 'grid', gridTemplateColumns: 'minmax(120px,auto) 1fr', gap: 1.75, px: 1.75, py: 1, borderBottom: 1, borderColor: (t) => alpha(t.palette.warning.main, 0.22), fontSize: 12.5 }}>
            <Box component="span" sx={{ typography: 'mono', color: (t) => `color-mix(in srgb, ${t.palette.warning.main} 78%, ${t.palette.text.secondary})`, whiteSpace: 'nowrap' }}>{k}</Box>
            <Box component="span" sx={{ typography: 'mono', fontWeight: 600, color: 'warning.main' }}>{v.toLocaleString()}</Box>
          </Box>
        ))}
        {tokens.cacheRead > 0 ? (
          <Tooltip arrow placement="top" title="Billed at ~1/10 the input-token rate">
            <Box sx={{ display: 'grid', gridTemplateColumns: 'minmax(120px,auto) 1fr', gap: 1.75, px: 1.75, py: 1, fontSize: 12.5, borderTop: (t) => `1px dashed ${alpha(t.palette.warning.main, 0.26)}`, bgcolor: (t) => alpha(t.palette.text.secondary, 0.05) }}>
              <Box component="span" sx={{ typography: 'mono', color: 'text.disabled', whiteSpace: 'nowrap' }}>cache_read</Box>
              <Box component="span" sx={{ typography: 'mono', fontWeight: 500, color: 'text.secondary', display: 'inline-flex', alignItems: 'baseline', gap: 1 }}>
                {tokens.cacheRead.toLocaleString()}
                <Box component="span" sx={{ typography: 'eyebrowSm', color: 'text.disabled', border: 1, borderColor: (t) => alpha(t.palette.text.secondary, 0.28), borderRadius: '4px', px: 0.6, py: '1px' }}>~1/10 rate</Box>
              </Box>
            </Box>
          </Tooltip>
        ) : null}
      </Box>
    </Box>
  );
};

export default TokensSection;
