import { Box, alpha } from '@mui/material';
import { formatRelativeTime } from '../../../../lib/format';
import { formatTokens, formatUsd } from '../../../TracesPage/tracesApi';
import { LONG_VALUE_ATTR, LongAttrValue } from '../SpanInspectorDrawer/longValue';
import PromptSummaryText from '../../../../components/PromptSummaryText';
import type { SwitchTraceRow } from './SwitchTraceModalView';

interface Props {
  row: SwitchTraceRow;
  isCurrent: boolean;
  onSelect: () => void;
}

export const GRID_COLUMNS = '58px 1fr 64px 76px 72px';

// Sum of the turn's four-way token split, or null when the turn has none —
// distinct from 0, which would print "0 tok" instead of the row's "—".
const tokenTotalOf = (tokens: SwitchTraceRow['tokens']): number | null =>
  tokens ? tokens.input + tokens.output + tokens.cacheCreation + tokens.cacheRead : null;

// One row: time / prompt / cost / tokens / current flag.
const SwitchTraceModalRow = ({ row, isCurrent, onSelect }: Props) => {
  const tokenTotal = tokenTotalOf(row.tokens ?? null);

  return (
    <Box
      onClick={isCurrent ? undefined : onSelect}
      sx={{
        display: 'grid',
        gridTemplateColumns: GRID_COLUMNS,
        alignItems: 'center',
        gap: 1.5,
        px: 2.5,
        py: 1.1,
        borderBottom: 1,
        borderColor: 'divider',
        cursor: isCurrent ? 'default' : 'pointer',
        bgcolor: isCurrent
          ? (t) => alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.22 : 0.12)
          : 'transparent',
        boxShadow: isCurrent ? (t) => `inset 2px 0 0 ${t.palette.primary.main}` : 'none',
        '&:hover': isCurrent ? {} : { bgcolor: 'action.hover' },
        '&:last-of-type': { borderBottom: 'none' },
      }}
    >
      <Box sx={{ typography: 'mono', fontSize: 10.5, color: 'text.disabled' }}>
        {formatRelativeTime(row.timestamp)}
      </Box>
      <Box
        title={row.prompt}
        sx={{
          fontSize: 13,
          color: 'text.primary',
          wordBreak: 'break-word',
        }}
      >
        <PromptSummaryText
          prompt={row.prompt}
          renderOrdinary={(p) => <LongAttrValue attrKey="Prompt" value={p} limit={LONG_VALUE_ATTR} />}
        />
      </Box>
      <Box
        sx={{
          typography: 'mono',
          fontSize: 11,
          fontWeight: 600,
          color: 'warning.main',
          textAlign: 'right',
        }}
      >
        {formatUsd(row.costUsd ?? 0)}
      </Box>
      <Box
        sx={{
          typography: 'mono',
          fontSize: 11,
          color: 'text.secondary',
          textAlign: 'right',
        }}
      >
        {tokenTotal === null ? '—' : `${formatTokens(tokenTotal)} tok`}
      </Box>
      <Box sx={{ textAlign: 'right' }}>
        {isCurrent ? (
          <Box
            component="span"
            sx={{
              typography: 'eyebrowSm',
              px: 0.9,
              py: 0.3,
              borderRadius: '5px',
              bgcolor: (t) => alpha(t.palette.primary.main, 0.14),
              color: 'primary.main',
            }}
          >
            current
          </Box>
        ) : null}
      </Box>
    </Box>
  );
};

export default SwitchTraceModalRow;
