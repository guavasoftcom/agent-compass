import { alpha, Box } from '@mui/material';
import type { LogRow, SpanRow } from '../../../../api';
import { severityOf } from '../../../LogsPage/logsDerivations';
import CollapsibleSection from './CollapsibleSection';
import { AttrRows } from './drawerParts';
import { radii } from '../../../../theme/theme';

interface ErrorSectionProps {
  span: SpanRow;
  // Logs already bucketed onto this span (the drawer's existing `logs` prop) —
  // searched here for the ERROR-severity entry that carries stderr, if any.
  logs: LogRow[];
}

// Replaces the old plain "statusMessage in a red box" treatment with expandable
// context: exit_code / command (from the span's own attributes) and stderr
// (from the matching error log), plus a one-click copy of all of it. Renders
// nothing for non-error spans.
const ErrorSection = ({ span, logs }: ErrorSectionProps) => {
  if (span.statusCode !== 'error') {
    return null;
  }
  // Severity is derived, never stored: real telemetry leaves severityText and
  // severityNumber null on every row, so matching on severityText alone found
  // nothing. `severityOf` is the shared derivation (mirrors the SQL
  // derive_log_severity) the Logs page uses. Prefer an error log that actually
  // carries stderr, since a span can log several.
  const errorLogs = logs.filter((l) => severityOf(l) === 'ERROR');
  const errorLog =
    errorLogs.find((l) => typeof l.attributes?.['stderr'] === 'string')
    ?? errorLogs[0];
  const attributes = span.attributes ?? {};
  const detail: Record<string, unknown> = {};
  if (attributes['exit_code'] !== undefined) {
    detail.exit_code = attributes['exit_code'];
  }
  if (attributes['command'] !== undefined) {
    detail.command = attributes['command'];
  }
  const stderr = errorLog?.attributes?.['stderr'];
  if (typeof stderr === 'string' && stderr.length > 0) {
    detail.stderr = stderr;
  }

  const copyError = () => {
    const lines = [
      `${span.name} — ${span.statusMessage ?? 'error'}`,
      ...Object.entries(detail).map(([key, value]) => `${key}: ${value}`),
    ];
    navigator.clipboard?.writeText(lines.join('\n'));
  };

  return (
    <CollapsibleSection title="Error" tone="error">
      <Box
        sx={{
          px: 1.4,
          py: 1,
          mb: Object.keys(detail).length ? 1 : 0,
          border: 1,
          borderRadius: radii.sm,
          fontSize: 11.5,
          typography: 'mono',
          color: 'error.main',
          bgcolor: (t) => alpha(t.palette.error.main, 0.12),
          borderColor: (t) => alpha(t.palette.error.main, 0.3),
        }}
      >
        {span.statusMessage ?? 'Span ended with an error'}
      </Box>
      {Object.keys(detail).length ? (
        <AttrRows tone="error" attrs={detail} />
      ) : null}
      <Box
        component="button"
        onClick={copyError}
        sx={{
          mt: 1,
          height: 26,
          px: 1.5,
          border: 1,
          borderRadius: radii.sm,
          borderColor: (t) => alpha(t.palette.error.main, 0.4),
          bgcolor: 'transparent',
          color: 'error.main',
          fontSize: 11,
          fontWeight: 600,
          cursor: 'pointer',
          '&:hover': { bgcolor: (t) => alpha(t.palette.error.main, 0.12) },
        }}
      >
        Copy error
      </Box>
    </CollapsibleSection>
  );
};

export default ErrorSection;
