import { alpha, Box } from '@mui/material';
import {
  cacheHitRatePercent,
  type TokenBreakdown,
} from '../../../TracesPage/tokenBreakdown';
import { formatTokens } from '../../../TracesPage/tracesApi';
import CollapsibleSection from './CollapsibleSection';
import { radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';

// The section header carries the four-way total; the body lists all four parts
// that make it up, cache_read included. The tooltip repeats the split so the
// header still answers "of what?" while the section is collapsed.
const tokenTotalTooltip = (tokens: TokenBreakdown): string =>
  `${formatTokens(tokens.total)} total tokens` +
  `\nInput: ${formatTokens(tokens.input)}` +
  `\nOutput: ${formatTokens(tokens.output)}` +
  `\nCache creation: ${formatTokens(tokens.cacheCreate)}` +
  `\nCache read: ${formatTokens(tokens.cacheRead)}`;

// Shared row metrics, matching drawerParts' AttrRows so the Tokens table lines
// up with the Attributes/Tool grids above and below it. The rows are rendered
// here rather than through AttrRows because cache_read needs three things that
// shared primitive deliberately doesn't offer: its own hue, a dashed separator,
// and a trailing chip.
const rowSx = {
  display: 'grid',
  gridTemplateColumns: 'minmax(96px,42%) 1fr',
  gap: 1.25,
  px: 1.5,
  py: 0.9,
  fontSize: 11.5,
} as const;

// Tokens only — no cost. The span's cost, when it has one to show, is the
// `cost` row in the drawer's meta grid above; carrying it here too meant one
// number in two places a few hundred pixels apart.
interface TokensSectionProps {
  tokens: TokenBreakdown;
}

const TokensSection = ({ tokens }: TokensSectionProps) => {
  if (tokens.total <= 0) {
    return null;
  }
  // cache_read sits below the dashed rule, deliberately quieter than the amber
  // rows above: it's the cheap, high-volume figure (often 10-100x the others),
  // so giving it the same weight as the billable counts would make four numbers
  // read as one comparable set when they aren't.
  const pricedRows: Array<[string, string]> = [];
  pricedRows.push(['input', tokens.input.toLocaleString()]);
  pricedRows.push(['output', tokens.output.toLocaleString()]);
  pricedRows.push(['cache_creation', tokens.cacheCreate.toLocaleString()]);
  const cacheHitRate = cacheHitRatePercent(tokens);
  return (
    <CollapsibleSection
      title="Tokens"
      tone="token"
      count={tokens.total}
      countTitle={tokenTotalTooltip(tokens)}
    >
      <Box
        sx={{
          border: 1,
          borderColor: (t) => alpha(t.palette.warning.main, 0.42),
          borderRadius: radii.lg,
          overflow: 'hidden',
          bgcolor: (t) => alpha(t.palette.warning.main, 0.08),
        }}
      >
        {pricedRows.map(([label, value]) => (
          <Box
            key={label}
            sx={{
              ...rowSx,
              borderBottom: 1,
              borderColor: (t) => alpha(t.palette.warning.main, 0.22),
            }}
          >
            <Box
              component="span"
              sx={{
                typography: 'mono',
                color: (t) =>
                  `color-mix(in srgb, ${t.palette.warning.main} 78%, ${t.palette.text.secondary})`,
                wordBreak: 'break-word',
              }}
            >
              {label}
            </Box>
            <Box
              component="span"
              sx={{
                typography: 'mono',
                fontWeight: 600,
                color: 'warning.main',
                wordBreak: 'break-word',
              }}
            >
              {value}
            </Box>
          </Box>
        ))}
        <Box
          sx={{
            ...rowSx,
            borderTop: '1px dashed',
            borderColor: (t) => alpha(t.palette.warning.main, 0.26),
            bgcolor: (t) => alpha(t.palette.text.secondary, 0.06),
          }}
        >
          <Box
            component="span"
            sx={{
              typography: 'mono',
              color: 'text.disabled',
              wordBreak: 'break-word',
            }}
          >
            cache_read
          </Box>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'baseline',
              gap: 1,
              flexWrap: 'wrap',
              minWidth: 0,
              typography: 'mono',
              fontWeight: 500,
              color: 'text.secondary',
            }}
          >
            <Box component="span" sx={{ wordBreak: 'break-word' }}>
              {tokens.cacheRead.toLocaleString()}
            </Box>
            {cacheHitRate != null ? (
              <Box
                component="span"
                title="Share of cacheable tokens served from the prompt cache"
                sx={{
                  fontFamily: fontFamilies.display,
                  fontSize: 8.5,
                  fontWeight: 700,
                  letterSpacing: '.4px',
                  textTransform: 'uppercase',
                  color: 'text.disabled',
                  border: 1,
                  borderColor: (t) => alpha(t.palette.text.disabled, 0.35),
                  borderRadius: radii.xs,
                  px: 0.6,
                  py: 0.1,
                  whiteSpace: 'nowrap',
                  flexShrink: 0,
                }}
              >
                {cacheHitRate}% hit
              </Box>
            ) : null}
          </Box>
        </Box>
      </Box>
    </CollapsibleSection>
  );
};

export default TokensSection;
