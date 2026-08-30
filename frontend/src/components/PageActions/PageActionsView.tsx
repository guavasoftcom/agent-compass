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
import type { ReactNode } from 'react';
import { alpha, Box, Button, CircularProgress, Tooltip } from '@mui/material';
import PauseIcon from '@mui/icons-material/Pause';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import { auroraColors, gradients } from '../../theme/colors';

export interface PageActionsViewProps {
  windowSelector: ReactNode;
  extraActions?: ReactNode;
  onReload: () => void;
  reloadDisabled: boolean;
  hideReload?: boolean;
  autoRefreshActive: boolean;
  autoRefreshDisabled: boolean;
  isPolling: boolean;
  onToggleAutoRefresh: () => void;
  hideAutoRefresh?: boolean;
}

const PageActionsView = ({
  windowSelector,
  extraActions,
  onReload,
  reloadDisabled,
  hideReload = false,
  autoRefreshActive,
  autoRefreshDisabled,
  isPolling,
  onToggleAutoRefresh,
  hideAutoRefresh = false,
}: PageActionsViewProps) => {
  return (
    <Box
      sx={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: 1.5,
        justifyContent: { xs: 'flex-start', sm: 'flex-end' },
        width: '100%',
      }}
    >
      {windowSelector}
      {extraActions}
      {!hideReload && (
        <Tooltip title="Refresh">
          <Button
            variant="outlined"
            size="medium"
            onClick={onReload}
            disabled={reloadDisabled}
            sx={{ minWidth: 'auto', px: 1 }}
          >
            <RefreshIcon />
          </Button>
        </Tooltip>
      )}
      {!hideAutoRefresh && (
        <Tooltip title={autoRefreshActive ? 'Auto refresh on' : 'Auto refresh off'}>
          <Button
            variant={autoRefreshActive ? 'contained' : 'outlined'}
            size="medium"
            onClick={onToggleAutoRefresh}
            disabled={autoRefreshDisabled}
            sx={{
              minWidth: 'auto',
              px: 1,
              // Aurora gradient "go" button when auto-refresh is on.
              ...(autoRefreshActive && {
                background: gradients.auroraAction,
                boxShadow: `0 6px 16px ${alpha(auroraColors.violetLight, 0.4)}`,
                '&:hover': {
                  background: gradients.auroraAction,
                  filter: 'brightness(1.06)',
                },
              }),
            }}
          >
            {isPolling ? (
              <CircularProgress size={20} color="inherit" />
            ) : autoRefreshActive ? (
              <PauseIcon />
            ) : (
              <PlayArrowIcon />
            )}
          </Button>
        </Tooltip>
      )}
    </Box>
  );
};

export default PageActionsView;
