import { Button, Paper, Typography } from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import type { WindowSelection } from '../../api';

export interface ReportPageViewProps {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  data: string | undefined;
  isLoading: boolean;
  error: Error | null;
  copied: boolean;
  onCopy: () => void;
  onReload: () => void;
}

export default function ReportPageView({
  selection,
  onSelectionChange,
  data,
  isLoading,
  error,
  copied,
  onCopy,
  onReload,
}: ReportPageViewProps) {
  return (
    <PageLayout
      title="Markdown Report"
      subtitle="Paste the rendered summary back into your coding agent to tune its prompts and skills."
      error={error}
      actions={
        <PageActions
          selection={selection}
          onSelectionChange={onSelectionChange}
          onReload={onReload}
          hideAutoRefresh
          extraActions={
            <Button
              variant="contained"
              startIcon={<ContentCopyIcon />}
              onClick={onCopy}
              disabled={!data || isLoading}
              sx={{ whiteSpace: 'nowrap', flexShrink: 0 }}
            >
              {copied ? 'Copied' : 'Copy markdown'}
            </Button>
          }
        />
      }
    >
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography
          component="pre"
          sx={{
            typography: 'mono',
            m: 0,
            fontSize: 14,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {isLoading ? 'Loading…' : data ?? ''}
        </Typography>
      </Paper>
    </PageLayout>
  );
}
