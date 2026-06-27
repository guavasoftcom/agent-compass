import { Box } from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { useCopyToClipboard } from './useCopyToClipboard';

// Compact, labeled, copyable ID chip showing the full value with a click-to-copy affordance.
const IdChip = ({ label, value }: { label: string; value: string }) => {
  const { copied, copy } = useCopyToClipboard();
  return (
    <Box
      component="button"
      onClick={() => copy(value)}
      title={`${label}: ${value} — click to copy`}
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.85,
        height: 28,
        px: 1.4,
        border: 1,
        borderColor: 'divider',
        borderRadius: '8px',
        bgcolor: 'background.paper',
        cursor: 'pointer',
        '&:hover': { borderColor: 'primary.main' },
      }}
    >
      <Box
        component="span"
        sx={{
          fontFamily: "'Sora', sans-serif",
          fontSize: 9.5,
          fontWeight: 700,
          letterSpacing: '0.5px',
          textTransform: 'uppercase',
          color: 'text.disabled',
        }}
      >
        {label}
      </Box>
      <Box
        component="span"
        sx={{
          fontFamily: "'JetBrains Mono', monospace",
          fontSize: 11.5,
          color: copied ? 'success.main' : 'text.secondary',
        }}
      >
        {copied ? 'copied!' : value}
      </Box>
      <ContentCopyIcon
        sx={{ fontSize: 13, color: copied ? 'success.main' : 'text.disabled' }}
      />
    </Box>
  );
};

export default IdChip;
