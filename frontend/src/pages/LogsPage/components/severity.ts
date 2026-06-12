import type { Theme } from '@mui/material/styles';
import type { Severity } from '../logsApi';

/**
 * Maps a log severity to its theme palette color. Shared across the Logs presentation
 * components — histogram bars/legend, the stream severity rail + chip, and the facet dots.
 */
export const severityColor = (t: Theme, s: Severity): string => {
  switch (s) {
    case 'ERROR':
      return t.palette.error.main;
    case 'WARN':
      return t.palette.warning.main;
    case 'INFO':
      return t.palette.primary.main;
    default:
      return t.palette.text.disabled;
  }
};
