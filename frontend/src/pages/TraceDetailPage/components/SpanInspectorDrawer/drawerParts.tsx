import { alpha, Box } from '@mui/material';
import { LongAttrValue } from './longValue';
import { radii } from '../../../../theme/theme';

// Wall-clock HH:MM:SS.mmm for a millisecond timestamp.
export const clock = (ms: number): string => {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`;
};

// 'error' tone added for the drawer's Error section (exit_code / command /
// stderr) — same red the drawer's error status text already uses.
export const AttrRows = ({ attrs, tone }: { attrs: Record<string, unknown>; tone?: 'token' | 'tool' | 'error' }) => (
  <Box sx={{ border: 1, borderColor: (t) => (tone === 'token' ? alpha(t.palette.warning.main, 0.42) : tone === 'tool' ? alpha(t.palette.info.main, 0.38) : tone === 'error' ? alpha(t.palette.error.main, 0.4) : t.palette.divider), borderRadius: radii.lg, overflow: 'hidden', bgcolor: (t) => (tone === 'token' ? alpha(t.palette.warning.main, 0.08) : tone === 'tool' ? alpha(t.palette.info.main, 0.07) : tone === 'error' ? alpha(t.palette.error.main, 0.07) : t.palette.background.paper) }}>
    {Object.entries(attrs).map(([k, v]) => {
      const num = typeof v === 'number';
      const str = typeof v === 'string';
      const isPrompt = !tone && /prompt$/i.test(k);
      return (
        <Box key={k} sx={{ display: 'grid', gridTemplateColumns: 'minmax(96px,42%) 1fr', gap: 1.25, px: 1.5, py: 0.9, borderBottom: 1, borderColor: (t) => (tone === 'token' ? alpha(t.palette.warning.main, 0.22) : tone === 'tool' ? alpha(t.palette.info.main, 0.20) : tone === 'error' ? alpha(t.palette.error.main, 0.20) : t.palette.divider), '&:last-of-type': { borderBottom: 'none' }, fontSize: 11.5, ...(isPrompt ? { bgcolor: (t) => alpha(t.palette.primary.main, 0.09), boxShadow: (t) => `inset 3px 0 0 ${t.palette.primary.main}` } : {}) }}>
          <Box component="span" sx={{ typography: 'mono', color: (t) => (tone === 'token' ? `color-mix(in srgb, ${t.palette.warning.main} 78%, ${t.palette.text.secondary})` : tone === 'tool' ? `color-mix(in srgb, ${t.palette.info.main} 76%, ${t.palette.text.secondary})` : tone === 'error' ? `color-mix(in srgb, ${t.palette.error.main} 76%, ${t.palette.text.secondary})` : isPrompt ? t.palette.primary.main : t.palette.text.secondary), fontWeight: isPrompt ? 600 : 400, wordBreak: 'break-word' }}>{k}</Box>
          <Box component="span" sx={{ typography: 'mono', fontWeight: tone === 'token' ? 600 : 500, wordBreak: 'break-word' }}>
            {/* Long strings (a heredoc full_command, a stderr dump, a prompt) clamp
                here and hand the rest to the drawer's shared "view formatted" modal,
                rather than pushing this grid out of shape. Numbers pass through
                pre-formatted and never clamp. */}
            <LongAttrValue
              attrKey={k}
              value={v}
              text={num ? (v as number).toLocaleString() : undefined}
              color={tone === 'token' ? 'warning.main' : tone === 'error' ? 'error.main' : num ? 'info.main' : str ? 'success.main' : 'text.primary'}
            />
          </Box>
        </Box>
      );
    })}
  </Box>
);
