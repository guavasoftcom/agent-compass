/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
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
import { radii } from '../../theme/theme';

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
      <DialogTitle sx={{ typography: 'mono', pr: 6 }}>
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
              typography: 'mono',
              p: 1,
              borderRadius: radii.sm,
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
