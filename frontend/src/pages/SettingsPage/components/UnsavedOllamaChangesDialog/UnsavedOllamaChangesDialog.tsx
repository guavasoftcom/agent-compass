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
import { Button, Dialog, DialogActions, DialogContent, DialogTitle, Typography } from '@mui/material';

export interface UnsavedOllamaChangesDialogProps {
  open: boolean;
  onCancel: () => void;
  onDiscard: () => void;
}

/**
 * Guards a tab switch away from the Ollama tab while the port, model, or Enabled toggle has been
 * edited but not saved. Mounted only while `open` (see `SettingsPage.tsx`'s `handleTabChange`),
 * the same "no stale state to reset" idiom `PurgeConfirmDialog` uses for its typed confirmation —
 * there is nothing here to reset, but it keeps the two dialogs on this page consistent.
 *
 * Deliberately a plain two-button dialog rather than `PurgeConfirmDialog`'s type-to-confirm
 * pattern: that friction is reserved for the one irreversible action in the app (permanently
 * deleting telemetry). Losing an unsaved port/model edit is easily redone by retyping it, so a
 * single confirming click is proportionate.
 */
const UnsavedOllamaChangesDialog = ({ open, onCancel, onDiscard }: UnsavedOllamaChangesDialogProps) => (
  <Dialog open={open} onClose={onCancel} maxWidth="xs" fullWidth>
    <DialogTitle>Leave without saving?</DialogTitle>
    <DialogContent>
      <Typography variant="body2" color="text.secondary">
        You have unsaved Ollama settings changes. Leaving this tab now discards them.
      </Typography>
    </DialogContent>
    <DialogActions sx={{ px: 3, pb: 2.5 }}>
      <Button onClick={onCancel} color="inherit">
        Cancel
      </Button>
      <Button onClick={onDiscard} color="error" variant="contained">
        Leave without saving
      </Button>
    </DialogActions>
  </Dialog>
);

export default UnsavedOllamaChangesDialog;
