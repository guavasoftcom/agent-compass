import { Box, alpha, useTheme } from '@mui/material';
import { type Severity } from '../../logsApi';
import { severityColor } from '../severity';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';

// Severity badge (e.g. ERROR / WARN / INFO / DEBUG) used by both the Logs stream
// rows and the Logs table. Lives here rather than inside either so neither has to
// import from the other.
export const SeverityChip = ({ severity }: { severity: Severity }) => {
  const theme = useTheme();
  const c = severityColor(theme, severity);
  const isDebug = severity === 'DEBUG';
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        height: 19,
        px: 1,
        borderRadius: radii.xs,
        fontFamily: fontFamilies.display,
        fontSize: 10,
        fontWeight: 700,
        letterSpacing: '0.5px',
        color: isDebug ? 'text.secondary' : c,
        bgcolor: isDebug ? 'action.hover' : alpha(c, 0.15),
      }}
    >
      {severity}
    </Box>
  );
};
