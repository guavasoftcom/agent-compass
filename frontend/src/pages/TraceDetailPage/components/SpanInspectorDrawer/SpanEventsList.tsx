/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
import { Box } from '@mui/material';
import type { SpanEvent } from '../../../../api';
import { formatDuration } from '../../../TracesPage/tracesApi';
import CollapsibleSection from './CollapsibleSection';
import { LongAttrValue } from './longValue';
import { radii } from '../../../../theme/theme';

// Span events as a collapsible section of timestamped cards (offset from span
// start) with each event's attributes below its name. Renders nothing when the
// span has no events.
const SpanEventsList = ({ events, spanStartMs }: { events: SpanEvent[] | undefined; spanStartMs: number }) => {
  if (!events || events.length === 0) {
    return null;
  }
  return (
    <CollapsibleSection title="Events" count={events.length}>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.75 }}>
        {events.map((ev, i) => {
          const attrEntries = Object.entries(ev.attributes ?? {});
          return (
            <Box key={i} sx={{ border: 1, borderColor: 'divider', borderRadius: radii.sm, px: 1.4, py: 1, bgcolor: 'background.paper' }}>
              <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.1 }}>
                <Box component="span" sx={{ typography: 'mono', fontSize: 11.5, fontWeight: 600, color: 'primary.main' }}>
                  T+{formatDuration((Date.parse(ev.timestamp) - spanStartMs) * 1e6)}
                </Box>
                <Box component="span" sx={{ typography: 'mono', fontSize: 11.5, fontWeight: 600 }}>{ev.name}</Box>
              </Box>
              {attrEntries.length ? (
                <Box sx={{ mt: 0.6, display: 'grid', gridTemplateColumns: 'auto 1fr', columnGap: 1.25, rowGap: 0.4 }}>
                  {attrEntries.map(([k, v]) => (
                    <Box key={k} sx={{ display: 'contents' }}>
                      <Box component="span" sx={{ typography: 'mono', fontSize: 10.5, color: 'text.secondary' }}>{k}</Box>
                      {/* A process.exit event carries the whole stderr dump; clamp
                          it and hand the rest to the modal instead of letting one
                          event card run for a screen and a half. */}
                      <Box component="span" sx={{ typography: 'mono', fontSize: 10.5 }}>
                        <LongAttrValue attrKey={k} value={v} />
                      </Box>
                    </Box>
                  ))}
                </Box>
              ) : null}
            </Box>
          );
        })}
      </Box>
    </CollapsibleSection>
  );
};

export default SpanEventsList;
