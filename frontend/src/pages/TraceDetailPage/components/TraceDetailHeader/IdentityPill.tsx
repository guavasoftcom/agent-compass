import { useMemo, useState } from 'react';
import { Box } from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { useQuery } from '@tanstack/react-query';
import { fetchSessionPrompts } from '../../../../api';
import { hasTraceAndPrompt } from './SwitchTraceModalView';
import SwitchTraceModal from './SwitchTraceModal';

interface Props {
  traceId: string;
  sessionId: string | null;
}

// Combined session/trace identity pill in the breadcrumb row, replacing the
// old pair of separate IdChips. Session first (it's the trace's parent),
// sharing one rounded, bordered container so the two ids read as one identity
// rather than two unrelated chips. Both segments are plain, uncopyable text;
// the trace segment additionally carries a caret and is clickable — but only
// when the session has more than one trace to switch to. A session's prompt
// timeline is fetched here (same query SwitchTraceModal itself runs when
// opened, sharing its cache entry) purely to count distinct traces before
// deciding whether the affordance is worth offering; a single-trace session
// has nothing to switch to, so the segment stays inert like the no-sessionId
// case below rather than opening a modal with one row and nowhere to go.
const IdentityPill = ({ traceId, sessionId }: Props) => {
  const [switcherOpen, setSwitcherOpen] = useState(false);

  const { data: prompts } = useQuery({
    queryKey: ['session-prompts', sessionId],
    queryFn: () => fetchSessionPrompts(sessionId as string),
    enabled: Boolean(sessionId),
  });

  const distinctTraceCount = useMemo(() => {
    if (!prompts) {
      return 0;
    }
    return new Set(prompts.filter(hasTraceAndPrompt).map((row) => row.traceId)).size;
  }, [prompts]);

  // false while the query hasn't resolved yet, so the segment starts inert
  // rather than flashing enabled and then disabled once the session turns
  // out to have only one trace.
  const canSwitchTraces = Boolean(sessionId) && distinctTraceCount > 1;

  return (
    <>
      <Box
        sx={{
          display: 'inline-flex',
          alignItems: 'stretch',
          height: 28,
          border: 1,
          borderColor: 'divider',
          borderRadius: '8px',
          overflow: 'hidden',
          bgcolor: 'background.paper',
        }}
      >
        {sessionId ? (
          <Box
            title={`session: ${sessionId}`}
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 0.85,
              px: 1.4,
              borderRight: 1,
              borderColor: 'divider',
            }}
          >
            <Box component="span" sx={{ typography: 'eyebrowSm', color: 'text.disabled' }}>
              SESSION
            </Box>
            <Box
              component="span"
              sx={{
                typography: 'mono',
                fontSize: 11.5,
                color: 'text.secondary',
                whiteSpace: 'nowrap',
              }}
            >
              {sessionId}
            </Box>
          </Box>
        ) : null}
        <Box
          component="button"
          type="button"
          onClick={() => {
            if (canSwitchTraces) {
              setSwitcherOpen(true);
            }
          }}
          title={
            canSwitchTraces ? `trace: ${traceId} — click to switch traces` : `trace: ${traceId}`
          }
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 0.85,
            px: 1.4,
            py: 0,
            border: 'none',
            bgcolor: 'transparent',
            font: 'inherit',
            cursor: canSwitchTraces ? 'pointer' : 'default',
            '&:hover': canSwitchTraces ? { bgcolor: 'action.hover' } : {},
          }}
        >
          <Box component="span" sx={{ typography: 'eyebrowSm', color: 'text.disabled' }}>
            TRACE
          </Box>
          <Box
            component="span"
            sx={{
              typography: 'mono',
              fontSize: 11.5,
              color: 'text.secondary',
              whiteSpace: 'nowrap',
            }}
          >
            {traceId}
          </Box>
          {canSwitchTraces ? <ExpandMoreIcon sx={{ fontSize: 14, color: 'text.disabled' }} /> : null}
        </Box>
      </Box>
      {sessionId ? (
        <SwitchTraceModal
          open={switcherOpen}
          onClose={() => setSwitcherOpen(false)}
          sessionId={sessionId}
          currentTraceId={traceId}
        />
      ) : null}
    </>
  );
};

export default IdentityPill;
