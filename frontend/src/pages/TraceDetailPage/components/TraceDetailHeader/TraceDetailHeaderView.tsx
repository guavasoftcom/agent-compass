import { Link as RouterLink } from 'react-router-dom';
import { Box, Typography } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { formatDuration, formatTokens } from '../../../TracesPage/tracesApi';
import { spanColor } from '../../../TracesPage/components/traceColors';
import IdChip from './IdChip';
import SummaryStrip, { type SummaryItem } from './SummaryStrip';
import { fontFamilies } from '../../../../theme/typography';

export interface TraceDetailHeaderViewProps {
  traceId: string;
  sessionId: string | null;
  rootName: string;
  earliestStartMs: number;
  totalMs: number;
  errorCount: number;
  spanCount: number;
  serviceLabels: string[];
  totalTokens: number;
  // Aurora sync: the trace's first user prompt, shown as a header row inside
  // the summary panel above the KPI tiles. Requires a `firstUserPrompt` field
  // on TraceRow (populated from the root/first prompt-bearing span, mirroring
  // SessionSummaryRow.firstUserPrompt) — null/undefined hides the row.
  firstUserPrompt?: string | null;
}

const TraceDetailHeaderView = ({
  traceId,
  sessionId,
  rootName,
  earliestStartMs,
  totalMs,
  errorCount,
  spanCount,
  serviceLabels,
  totalTokens,
  firstUserPrompt,
}: TraceDetailHeaderViewProps) => {
  const summary: SummaryItem[] = [
    {
      label: 'Duration',
      monospace: true,
      value: formatDuration(totalMs * 1e6),
      title: formatDuration(totalMs * 1e6),
    },
    { label: 'Spans', value: spanCount, title: String(spanCount) },
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
    {
      label: 'Services',
      value: (
        <>
          {serviceLabels.length}{' '}
          <Box
            component="small"
            sx={{
              fontFamily: fontFamilies.body,
              fontSize: 12,
              fontWeight: 500,
              color: 'text.secondary',
            }}
          >
            {serviceLabels.join(' · ')}
          </Box>
        </>
      ),
      title: `${serviceLabels.length}: ${serviceLabels.join(', ')}`,
    },
    {
      label: 'Tokens',
      monospace: true,
      value: formatTokens(totalTokens),
      title: `${totalTokens.toLocaleString()} tokens`,
    },
    {
      label: 'Root span',
      value: (
        <Box
          component="span"
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 1,
            minWidth: 0,
          }}
        >
          <Box
            sx={{
              width: 9,
              height: 9,
              borderRadius: '3px',
              flexShrink: 0,
              bgcolor: spanColor(rootName),
            }}
          />
          <Box
            component="span"
            sx={{ overflow: 'hidden', textOverflow: 'ellipsis' }}
          >
            {rootName}
          </Box>
        </Box>
      ),
      title: rootName,
    },
    {
      label: 'Started',
      monospace: true,
      value: new Date(earliestStartMs).toLocaleTimeString('en-US', {
        hour12: false,
      }),
      title: new Date(earliestStartMs).toLocaleString('en-US', {
        hour12: false,
      }),
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
            <IdChip label="trace" value={traceId} />
            {sessionId ? <IdChip label="session" value={sessionId} /> : null}
          </Box>
        </Box>
      </Box>
      <SummaryStrip items={summary} prompt={firstUserPrompt} />
    </Box>
  );
};

export default TraceDetailHeaderView;
