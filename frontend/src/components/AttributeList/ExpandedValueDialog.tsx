import React from 'react';
import {
  Alert,
  Box,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import type { ValueDialogState } from './types';
import { tryParseJson, isPlainObject } from './utils';

export interface ExpandedValueDialogProps {
  state: ValueDialogState | null;
  onClose: () => void;
  renderAttributeList: (attrs: Record<string, unknown>) => React.ReactNode;
}

export const ExpandedValueDialog = ({
  state,
  onClose,
  renderAttributeList,
}: ExpandedValueDialogProps): React.ReactElement => {
  const parsed = state ? tryParseJson(state.value) : undefined;
  const parsedValue = parsed?.value;
  return (
    <Dialog open={state != null} onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle sx={{ pr: 6, fontFamily: 'monospace' }}>
        {state?.key}
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
        {parsed?.repaired ? (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Repaired from truncated JSON — trailing values may be missing or
            incomplete.
          </Alert>
        ) : null}
        {isPlainObject(parsedValue) ? (
          renderAttributeList(parsedValue)
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
              : state?.value}
          </Box>
        )}
      </DialogContent>
    </Dialog>
  );
};
