import { Box } from '@mui/material';
import type { SpanEvent } from '../../../../api';
import { formatDuration } from '../../../TracesPage/tracesApi';
import { SectionTitle } from './dockParts';
import { fontFamilies } from '../../../../theme/typography';

// Span events as a stack of timestamped cards (offset from span start).
// Renders nothing when the span has no events.
const SpanEventsList = ({ events, spanStartMs }: { events: SpanEvent[] | undefined; spanStartMs: number }) => {
  if (!events || events.length === 0) {
    return null;
  }
  return (
    <Box>
      <SectionTitle count={events.length}>Events</SectionTitle>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.75 }}>
        {events.map((ev, i) => (
          <Box key={i} sx={{ border: 1, borderColor: 'divider', borderRadius: 1.25, p: 1.1, bgcolor: 'background.paper' }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Box component="span" sx={{ fontFamily: fontFamilies.mono, fontSize: 10.5, color: 'text.disabled' }}>
                T+{formatDuration((Date.parse(ev.timestamp) - spanStartMs) * 1e6)}
              </Box>
              <Box component="span" sx={{ fontFamily: fontFamilies.mono, fontSize: 12, fontWeight: 600 }}>{ev.name}</Box>
            </Box>
          </Box>
        ))}
      </Box>
    </Box>
  );
};

export default SpanEventsList;
