import type { ReactNode } from 'react';
import { Box, Typography } from '@mui/material';
import { radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';

export interface SummaryItem {
  /** Uppercase label shown above the value. */
  label: string;
  /** When true, the value renders in the monospace stat style. */
  monospace?: boolean;
  value: ReactNode;
  /** Full text surfaced as a tooltip only when the value is truncated. */
  title: string;
}

// A single bordered row of labeled metric cells with dividers between them.
// Each value is ellipsis-truncated and only shows a tooltip when it overflows.
// Aurora sync: an optional `prompt` header row sits above the tile row in the same
// bordered/rounded panel, divided by a hairline — the trace's first user prompt
// where the backend can supply one (see TraceDetailHeaderView).
const SummaryStrip = ({ items, prompt }: { items: SummaryItem[]; prompt?: string | null }) => (
  <Box
    sx={{
      mt: 2.25,
      mb: 2,
      border: 1,
      borderColor: 'divider',
      borderRadius: radii.xl,
      overflow: 'hidden',
      bgcolor: 'background.paper',
    }}
  >
    {prompt ? (
      <Box
        onMouseEnter={(e) => {
          const promptElement = e.currentTarget.querySelector(
            '[data-prompt-text]',
          ) as HTMLElement | null;
          if (promptElement && promptElement.scrollWidth > promptElement.clientWidth) {
            e.currentTarget.setAttribute('title', prompt);
          } else {
            e.currentTarget.removeAttribute('title');
          }
        }}
        sx={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 1.5,
          px: 2.25,
          py: 1.4,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        <Typography sx={{ typography: 'eyebrowSm', color: 'text.disabled', flexShrink: 0 }}>
          Prompt
        </Typography>
        <Box
          data-prompt-text
          sx={{
            fontSize: 13.5,
            color: 'text.primary',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            minWidth: 0,
          }}
        >
          {prompt}
        </Box>
      </Box>
    ) : null}
    <Box sx={{ display: 'flex', alignItems: 'stretch' }}>
      {items.map((item, i) => (
        <Box
          key={item.label}
          sx={{
            flex: 1,
            minWidth: 0,
            px: 2.25,
            py: 1.6,
            display: 'flex',
            flexDirection: 'column',
            gap: 0.5,
            borderRight: i < items.length - 1 ? 1 : 0,
            borderColor: 'divider',
          }}
        >
          <Typography
            sx={{
              typography: 'eyebrowSm',
              color: 'text.disabled',
            }}
          >
            {item.label}
          </Typography>
          <Box
            onMouseEnter={(e) => {
              const element = e.currentTarget;
              if (element.scrollWidth > element.clientWidth) {
                element.setAttribute('title', item.title);
              } else {
                element.removeAttribute('title');
              }
            }}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              color: 'text.primary',
              ...(item.monospace
                ? {
                    typography: 'mono',
                    fontSize: 16,
                    fontWeight: 600,
                  }
                : {
                    fontFamily: fontFamilies.display,
                    fontSize: 20,
                    fontWeight: 700,
                  }),
            }}
          >
            {item.value}
          </Box>
        </Box>
      ))}
    </Box>
  </Box>
);

export default SummaryStrip;
