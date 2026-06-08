import { useMemo } from 'react';
import { Box, Paper, Typography, useTheme } from '@mui/material';
import { colorForIndex } from '../../theme';
import type { ToolDenialRow } from '../../api';

export interface ToolDenialsCardProps {
  denialRows: ToolDenialRow[];
  isDenialsLoading: boolean;
}

const SOURCE_LABELS: Record<string, string> = {
  config: 'settings.json rule',
  hook: 'hook script',
  user_permanent: 'user (permanent)',
  user_temporary: 'user (temporary)',
  user_abort: 'user (abort)',
  user_reject: 'user (reject)',
};

const formatSource = (source: string): string => SOURCE_LABELS[source] ?? source;

/**
 * Stable colour per denial source so the same source reads the same hue across
 * every tool's bar — config/hook use chart-palette tones, user-* outcomes use
 * the magenta/amber/error spread, with a deterministic fallback for anything new.
 */
const useSourceColor = () => {
  const theme = useTheme();
  return (source: string): string => {
    switch (source) {
      case 'config':
        return colorForIndex(0);
      case 'hook':
        return colorForIndex(2);
      case 'user_reject':
        return colorForIndex(1);
      case 'user_temporary':
        return colorForIndex(4);
      case 'user_permanent':
        return theme.palette.error.main;
      case 'user_abort':
        return theme.palette.text.disabled;
      default: {
        // Deterministic fallback: hash the source name into the palette.
        let hash = 0;
        for (let i = 0; i < source.length; i += 1) {
          hash = (hash * 31 + source.charCodeAt(i)) >>> 0;
        }
        return colorForIndex(3 + (hash % 4));
      }
    }
  };
};

/**
 * "Denials by tool & source" — one block per tool: name + total, a stacked bar
 * broken down by denial source, and a source legend with counts. Surfaces *why*
 * each tool was blocked (policy rule vs. hook vs. a user decision), which a flat
 * list buries. Mirrors the Aurora mockup.
 */
const ToolDenialsCard = ({ denialRows, isDenialsLoading }: ToolDenialsCardProps) => {
  const theme = useTheme();
  const sourceColor = useSourceColor();
  const trackColor = theme.custom?.progressTrack ?? theme.palette.action.hover;

  // Group rows by tool, order tools by total desc, sources within a tool by count desc.
  const groups = useMemo(() => {
    const byTool = new Map<string, ToolDenialRow[]>();
    for (const row of denialRows) {
      const list = byTool.get(row.tool) ?? [];
      list.push(row);
      byTool.set(row.tool, list);
    }
    return [...byTool.entries()]
      .map(([tool, rows]) => ({
        tool,
        rows: [...rows].sort((left, right) => right.count - left.count),
        total: rows.reduce((sum, row) => sum + row.count, 0),
      }))
      .sort((left, right) => right.total - left.total);
  }, [denialRows]);

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px', height: '100%' }}>
      <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 1.25, mb: 0.5 }}>
        <Typography sx={{ fontFamily: "'Sora', sans-serif", fontWeight: 600, fontSize: 16 }}>
          Denials by tool &amp; source
        </Typography>
        <Typography variant="caption" color="text.secondary">why each tool was blocked</Typography>
      </Box>

      {!isDenialsLoading && groups.length === 0 ? (
        <Typography color="text.secondary" sx={{ mt: 1 }}>No tool denials in this window.</Typography>
      ) : (
        groups.map((group, groupIndex) => (
          <Box
            key={group.tool}
            sx={{
              mt: groupIndex === 0 ? 1.75 : 2.25,
              pt: groupIndex === 0 ? 0 : 2.25,
              borderTop: groupIndex === 0 ? 'none' : '1px solid',
              borderColor: 'divider',
            }}
          >
            <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1, mb: 1.125 }}>
              <Box sx={{ fontWeight: 600, fontSize: 14, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {group.tool}
              </Box>
              <Box sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 700, fontSize: 14, whiteSpace: 'nowrap' }}>
                {group.total.toLocaleString()}{' '}
                <Box component="span" sx={{ color: 'text.secondary', fontWeight: 500, fontSize: 12 }}>
                  {group.total === 1 ? 'denial' : 'denials'}
                </Box>
              </Box>
            </Box>

            <Box sx={{ height: 10, borderRadius: '6px', bgcolor: trackColor, overflow: 'hidden', display: 'flex' }}>
              {group.rows.map((row) => (
                <Box
                  key={`${row.tool}-${row.source}-bar`}
                  sx={{
                    height: '100%',
                    width: `${group.total > 0 ? (row.count / group.total) * 100 : 0}%`,
                    bgcolor: sourceColor(row.source),
                  }}
                />
              ))}
            </Box>

            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: '7px 18px', mt: 1.375 }}>
              {group.rows.map((row) => (
                <Box
                  key={`${row.tool}-${row.source}-leg`}
                  sx={{ display: 'flex', alignItems: 'center', gap: 0.875, fontSize: 12.5, color: 'text.secondary' }}
                >
                  <Box sx={{ width: 9, height: 9, borderRadius: '3px', flexShrink: 0, bgcolor: sourceColor(row.source) }} />
                  {formatSource(row.source)}
                  <Box component="b" sx={{ color: 'text.primary', fontWeight: 700, fontVariantNumeric: 'tabular-nums', ml: 0.25 }}>
                    {row.count.toLocaleString()}
                  </Box>
                </Box>
              ))}
            </Box>
          </Box>
        ))
      )}
    </Paper>
  );
};

export default ToolDenialsCard;
