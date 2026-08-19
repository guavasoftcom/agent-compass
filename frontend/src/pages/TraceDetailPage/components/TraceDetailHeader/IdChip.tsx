import { Box } from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { useCopyToClipboard } from './useCopyToClipboard';

interface Props {
  label: string;
  value: string;
}

// Copy-to-clipboard chip for the trace/session id, shown inline next to the
// breadcrumb h1. The value truncates to 150px with an ellipsis — full ids run
// long enough (32+ hex chars) to blow out the header row — while the `title`
// attribute always carries the untruncated value for accessibility and the
// copy always copies the full string, never the clipped label.
const IdChip = ({ label, value }: Props) => {
  const { copied, copy } = useCopyToClipboard();
  return (
    <Box
      component="button"
      onClick={() => copy(value)}
      title={value}
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
      <Box component="span" sx={{ typography: 'eyebrowSm', color: 'text.disabled' }}>
        {label}
      </Box>
      <Box
        component="span"
        sx={{
          typography: 'mono',
          fontSize: 11.5,
          color: copied ? 'success.main' : 'text.secondary',
          maxWidth: 150,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {copied ? 'copied!' : value}
      </Box>
      <ContentCopyIcon sx={{ fontSize: 13, color: copied ? 'success.main' : 'text.disabled' }} />
    </Box>
  );
};

export default IdChip;
