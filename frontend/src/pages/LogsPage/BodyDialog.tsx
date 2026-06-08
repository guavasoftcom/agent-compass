import { Alert, Box, Dialog, DialogContent, DialogTitle, IconButton } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { AttributeList } from '../../components/AttributeList';
import { tryParseJson, isPlainObject } from '../../components/AttributeList/utils';

export interface BodyDialogProps {
  body: string | null;
  onClose: () => void;
}

const BodyDialog = ({ body, onClose }: BodyDialogProps) => {
  const parsed = body != null ? tryParseJson(body) : undefined;
  const parsedValue = parsed?.value;
  return (
    <Dialog open={body != null} onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle sx={{ pr: 6 }}>
        Body
        <IconButton
          aria-label="close"
          size="small"
          onClick={onClose}
          sx={{ position: 'absolute', right: 12, top: 12 }}
        >
          <CloseIcon fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers>
        {parsed?.repaired && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Repaired from truncated JSON — trailing values may be missing or
            incomplete.
          </Alert>
        )}
        {isPlainObject(parsedValue) ? (
          <AttributeList attributes={parsedValue} />
        ) : (
          <Box
            sx={{
              p: 1,
              borderRadius: 1,
              fontFamily: 'monospace',
              fontSize: '0.75rem',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              bgcolor: 'action.hover',
            }}
          >
            {parsedValue !== undefined
              ? JSON.stringify(parsedValue, null, 2)
              : body}
          </Box>
        )}
      </DialogContent>
    </Dialog>
  );
};

export default BodyDialog;
