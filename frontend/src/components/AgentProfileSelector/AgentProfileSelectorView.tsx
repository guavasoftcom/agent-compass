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
import type { MouseEvent } from 'react';
import { alpha, Box, Button, Menu, MenuItem } from '@mui/material';
import ArrowDropDownIcon from '@mui/icons-material/ArrowDropDown';
import CheckIcon from '@mui/icons-material/Check';
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined';
import type { AgentProfileSummary } from '../../api';
import { agentProfileLabel } from '../../lib/format';
import { auroraColors, neutralColors } from '../../theme/colors';
import { radii } from '../../theme/theme';

export interface AgentProfileSelectorViewProps {
  /** `null` = use the primary profile; the button then shows the primary's label. */
  value: string | null;
  primaryAgentProfile: string | null;
  profiles: AgentProfileSummary[];
  isLoading: boolean;
  anchor: HTMLElement | null;
  onAnchorOpen: (event: MouseEvent<HTMLElement>) => void;
  onAnchorClose: () => void;
  onSelect: (next: string | null) => void;
}

const AgentProfileSelectorView = ({
  value,
  primaryAgentProfile,
  profiles,
  isLoading,
  anchor,
  onAnchorOpen,
  onAnchorClose,
  onSelect,
}: AgentProfileSelectorViewProps) => {
  const effectiveProfile = value ?? primaryAgentProfile;
  const buttonLabel =
    effectiveProfile === null
      ? isLoading
        ? 'Loading agents...'
        : 'Agent'
      : agentProfileLabel(effectiveProfile, primaryAgentProfile);

  const menuItemSx = (theme: import('@mui/material').Theme) => ({
    borderRadius: radii.sm,
    py: 1.1,
    px: 1.5,
    fontSize: 14,
    justifyContent: 'space-between',
    '&.Mui-selected': {
      color: 'primary.main',
      fontWeight: 600,
      backgroundColor: theme.palette.action.selected,
      boxShadow: `inset 0 0 0 1px ${
        theme.palette.mode === 'dark'
          ? alpha(auroraColors.violetLight, 0.4)
          : alpha(auroraColors.violet, 0.32)
      }`,
      '&:hover': { backgroundColor: theme.palette.action.selected },
    },
  });

  return (
    <>
      <Button
        variant="outlined"
        size="medium"
        startIcon={<SmartToyOutlinedIcon sx={{ color: 'primary.main' }} />}
        endIcon={<ArrowDropDownIcon />}
        onClick={onAnchorOpen}
        sx={{
          minWidth: 188,
          maxWidth: 300,
          justifyContent: 'space-between',
          flexShrink: 0,
          fontWeight: 500,
        }}
      >
        <Box
          component="span"
          sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
        >
          {buttonLabel}
        </Box>
      </Button>
      <Menu
        anchorEl={anchor}
        open={anchor != null}
        onClose={onAnchorClose}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{
          paper: {
            sx: (theme) => ({
              mt: 1,
              width: 320,
              maxHeight: 420,
              borderRadius: radii.xl,
              border: `1px solid ${theme.palette.divider}`,
              backgroundImage: 'none',
              backgroundColor: theme.palette.background.paper,
              boxShadow:
                theme.palette.mode === 'dark'
                  ? `0 28px 64px ${alpha(neutralColors.black, 0.6)}`
                  : `0 24px 60px ${alpha(neutralColors.shadowIndigo, 0.18)}`,
              overflow: 'hidden',
            }),
          },
          list: { sx: { py: 0 } },
        }}
      >
        <Box sx={{ p: 1 }}>
          {isLoading && (
            <MenuItem disabled sx={menuItemSx}>
              Loading agents...
            </MenuItem>
          )}
          {profiles.map((profile) => {
            const selected = effectiveProfile === profile.agentProfile;
            return (
              <MenuItem
                key={profile.agentProfile}
                selected={selected}
                onClick={() => onSelect(profile.agentProfile)}
                sx={menuItemSx}
                title={profile.agentProfile}
              >
                <Box
                  component="span"
                  sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                >
                  {agentProfileLabel(profile.agentProfile, primaryAgentProfile)}
                </Box>
                {selected && <CheckIcon fontSize="small" sx={{ color: 'primary.main', ml: 1 }} />}
              </MenuItem>
            );
          })}
        </Box>
      </Menu>
    </>
  );
};

export default AgentProfileSelectorView;
