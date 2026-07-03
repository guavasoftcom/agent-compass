import type { ReactNode } from 'react';
import { alpha, Box, Typography } from '@mui/material';
import { attrValueAsString } from '../../attrFormat';
import { radii } from '../../../../theme/theme';

// Wall-clock HH:MM:SS.mmm for a millisecond timestamp.
export const clock = (ms: number): string => {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`;
};

export const SectionTitle = ({ children, count, tone }: { children: ReactNode; count?: number; tone?: 'token' | 'tool' }) => (
  <Typography sx={{ typography: 'eyebrowSm', color: tone === 'token' ? 'warning.main' : tone === 'tool' ? 'info.main' : 'text.secondary', mb: 1.1, display: 'flex', alignItems: 'center', gap: 1 }}>
    {tone === 'tool' ? <Box component="svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} sx={{ width: 13, height: 13 }}><path d="M14.7 6.3a4 4 0 0 0-5.2 5.2L3 18l3 3 6.5-6.5a4 4 0 0 0 5.2-5.2l-2.4 2.4-2.1-.6-.6-2.1 2.4-2.4z" /></Box> : null}
    {children}
    {count != null ? <Box component="span" sx={{ typography: 'mono', fontSize: 10.5, fontWeight: tone ? 700 : 500, color: tone === 'token' ? 'warning.main' : tone === 'tool' ? 'info.main' : 'text.disabled', bgcolor: (t) => (tone === 'token' ? alpha(t.palette.warning.main, 0.16) : tone === 'tool' ? alpha(t.palette.info.main, 0.16) : t.palette.action.hover), borderRadius: radii.xs, px: 0.9, py: 0.15 }}>{count.toLocaleString()}</Box> : null}
  </Typography>
);

export const AttrRows = ({ attrs, tone }: { attrs: Record<string, unknown>; tone?: 'token' | 'tool' }) => (
  <Box sx={{ border: 1, borderColor: (t) => (tone === 'token' ? alpha(t.palette.warning.main, 0.42) : tone === 'tool' ? alpha(t.palette.info.main, 0.38) : t.palette.divider), borderRadius: radii.lg, overflow: 'hidden', bgcolor: (t) => (tone === 'token' ? alpha(t.palette.warning.main, 0.08) : tone === 'tool' ? alpha(t.palette.info.main, 0.07) : t.palette.background.paper) }}>
    {Object.entries(attrs).map(([k, v]) => {
      const num = typeof v === 'number';
      const str = typeof v === 'string';
      const isPrompt = !tone && /prompt$/i.test(k);
      return (
        <Box key={k} sx={{ display: 'grid', gridTemplateColumns: 'minmax(120px,auto) 1fr', gap: 1.75, px: 1.75, py: 1, borderBottom: 1, borderColor: (t) => (tone === 'token' ? alpha(t.palette.warning.main, 0.22) : tone === 'tool' ? alpha(t.palette.info.main, 0.20) : t.palette.divider), '&:last-of-type': { borderBottom: 'none' }, fontSize: 12.5, ...(isPrompt ? { bgcolor: (t) => alpha(t.palette.primary.main, 0.09), boxShadow: (t) => `inset 3px 0 0 ${t.palette.primary.main}` } : {}) }}>
          <Box component="span" sx={{ typography: 'mono', color: (t) => (tone === 'token' ? `color-mix(in srgb, ${t.palette.warning.main} 78%, ${t.palette.text.secondary})` : tone === 'tool' ? `color-mix(in srgb, ${t.palette.info.main} 76%, ${t.palette.text.secondary})` : isPrompt ? t.palette.primary.main : t.palette.text.secondary), fontWeight: isPrompt ? 600 : 400, whiteSpace: 'nowrap' }}>{k}</Box>
          <Box component="span" sx={{ typography: 'mono', fontWeight: tone === 'token' ? 600 : 500, wordBreak: 'break-word', color: tone === 'token' ? 'warning.main' : num ? 'info.main' : str ? 'success.main' : 'text.primary' }}>{num ? (v as number).toLocaleString() : attrValueAsString(v)}</Box>
        </Box>
      );
    })}
  </Box>
);
