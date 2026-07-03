import { Box, useTheme } from '@mui/material';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlined';
import GhostButton from '../../../../components/GhostButton';

interface Props {
  anyCollapsed: boolean;
  errorCount: number;
  onToggleAll: () => void;
  onNextError: () => void;
}

const WaterfallToolbar = ({
  anyCollapsed,
  errorCount,
  onToggleAll,
  onNextError,
}: Props) => {
  const theme = useTheme();
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        px: 2,
        py: 1.1,
        borderBottom: 1,
        borderColor: 'divider',
        flexShrink: 0,
        flexWrap: 'wrap',
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1.1,
          typography: 'eyebrow',
          color: 'text.secondary',
        }}
      >
        <Box
          component="svg"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={2}
          sx={{ width: 15, height: 15 }}
        >
          <path d="M3 6h13M3 12h18M3 18h9" />
        </Box>
        Span waterfall
      </Box>
      <Box
        sx={{
          ml: 'auto',
          display: 'flex',
          alignItems: 'center',
          gap: 1.6,
          fontSize: 11.5,
          color: 'text.secondary',
        }}
      >
        {(
          [
            ['ok', theme.palette.primary.main],
            ['error', theme.palette.error.main],
            ['tokens', theme.palette.warning.main],
          ] as const
        ).map(([label, color]) => (
          <Box
            key={label}
            component="span"
            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75 }}
          >
            <Box
              sx={{ width: 9, height: 9, borderRadius: '3px', bgcolor: color }}
            />{' '}
            {label}
          </Box>
        ))}
      </Box>
      <GhostButton onClick={onToggleAll}>
        {anyCollapsed ? 'Expand all' : 'Collapse all'}
      </GhostButton>
      {errorCount ? (
        <GhostButton tone="danger" onClick={onNextError} sx={{ px: 1.5 }}>
          <ErrorOutlineIcon /> Next error
        </GhostButton>
      ) : null}
    </Box>
  );
};

export default WaterfallToolbar;
