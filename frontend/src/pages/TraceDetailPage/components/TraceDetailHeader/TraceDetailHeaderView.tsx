import { Link as RouterLink } from 'react-router-dom';
import { Box, Typography } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { formatDuration, formatUsd } from '../../../TracesPage/tracesApi';
import { spanColor } from '../../../TracesPage/components/traceColors';
import SummaryStrip, { type SummaryItem } from './SummaryStrip';
import IdentityPill from './IdentityPill';
import { fontFamilies } from '../../../../theme/typography';
import type { TokenBreakdown } from '../../../TracesPage/tokenBreakdown';

export interface TraceDetailHeaderViewProps {
  traceId: string;
  sessionId: string | null;
  rootName: string;
  earliestStartMs: number;
  totalMs: number;
  errorCount: number;
  spanCount: number;
  serviceLabels: string[];
  tokenBreakdown: TokenBreakdown;
  modelCallCount: number;
  toolCallCount: number;
  maximumDepth: number;
  totalCostUsd: number;
  // Aurora sync: the trace's first user prompt, shown as a header row inside
  // the summary panel above the KPI tiles. Requires a `firstUserPrompt` field
  // on TraceRow (populated from the root/first prompt-bearing span, mirroring
  // SessionSummaryRow.firstUserPrompt) — null/undefined hides the row.
  firstUserPrompt?: string | null;
}

// The breadcrumb carries copy-to-clipboard IdChips for the trace id, and the
// session id when the trace has one, right after the "Trace detail" h1 — see
// IdChip. The Overview panel's meta footer (SummaryStrip) is left with just
// Root span / Services / Started. What's left up here below the chips is the
// five at-a-glance KPI tiles, Cost leading and gradient-emphasized; the old
// single "Tokens" tile became the richer Token composition card inside the
// panel.
const TraceDetailHeaderView = ({
  traceId,
  sessionId,
  rootName,
  earliestStartMs,
  totalMs,
  errorCount,
  spanCount,
  serviceLabels,
  tokenBreakdown,
  modelCallCount,
  toolCallCount,
  maximumDepth,
  totalCostUsd,
  firstUserPrompt,
}: TraceDetailHeaderViewProps) => {
  const durationLabel = formatDuration(totalMs * 1e6);
  const costLabel = formatUsd(totalCostUsd);
  const startedAtLabel = new Date(earliestStartMs).toLocaleTimeString('en-US', {
    hour12: false,
  });

  const summary: SummaryItem[] = [
    {
      label: 'Cost',
      monospace: true,
      emphasis: true,
      value: costLabel,
      title: costLabel,
    },
    {
      label: 'Duration',
      monospace: true,
      value: durationLabel,
      title: durationLabel,
    },
    { label: 'Spans', value: spanCount, title: String(spanCount) },
    { label: 'Tool calls', value: toolCallCount, title: String(toolCallCount) },
    {
      label: 'Depth',
      value: (
        <>
          {maximumDepth}{' '}
          <Box
            component="small"
            sx={{
              fontFamily: fontFamilies.body,
              fontSize: 12,
              fontWeight: 500,
              color: 'text.secondary',
            }}
          >
            levels
          </Box>
        </>
      ),
      title: `${maximumDepth} levels`,
    },
    {
      label: 'Errors',
      value: (
        <Box
          component="span"
          sx={{ color: errorCount ? 'error.main' : 'inherit' }}
        >
          {errorCount || '—'}
        </Box>
      ),
      title: errorCount
        ? `${errorCount} error${errorCount === 1 ? '' : 's'}`
        : 'No errors',
    },
  ];

  return (
    <Box sx={{ flexShrink: 0 }}>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          gap: 2,
          flexWrap: 'wrap',
        }}
      >
        <Box>
          <Typography
            sx={{
              typography: 'eyebrow',
              color: 'primary.main',
              mb: 0.75,
            }}
          >
            Observability
          </Typography>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1.4,
              flexWrap: 'wrap',
            }}
          >
            <Box
              component={RouterLink}
              to="/traces"
              sx={{
                fontFamily: fontFamilies.display,
                fontWeight: 700,
                fontSize: 26,
                letterSpacing: '-0.4px',
                color: 'text.secondary',
                textDecoration: 'none',
                transition: '.12s',
                '&:hover': { color: 'primary.main' },
              }}
            >
              Traces
            </Box>
            <ChevronRightIcon sx={{ color: 'text.disabled', fontSize: 24 }} />
            <Typography
              component="h1"
              sx={{
                fontFamily: fontFamilies.display,
                fontSize: 26,
                fontWeight: 800,
                letterSpacing: '-0.5px',
                color: 'text.primary',
              }}
            >
              Trace detail
            </Typography>
            <IdentityPill traceId={traceId} sessionId={sessionId} />
          </Box>
        </Box>
      </Box>
      <SummaryStrip
        items={summary}
        prompt={firstUserPrompt}
        tokenBreakdown={tokenBreakdown}
        modelCallCount={modelCallCount}
        totalCostUsd={totalCostUsd}
        rootName={rootName}
        rootColor={spanColor(rootName)}
        serviceLabels={serviceLabels}
        startedAtLabel={startedAtLabel}
        durationLabel={durationLabel}
        spanCount={spanCount}
        toolCallCount={toolCallCount}
        errorCount={errorCount}
      />
    </Box>
  );
};

export default TraceDetailHeaderView;
