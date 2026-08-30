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
import { NavLink } from 'react-router-dom';
import {
  alpha,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Tooltip,
} from '@mui/material';
import { auroraColors } from '../theme/colors';

export interface NavItemProps {
  to: string;
  label: string;
  icon: ReactNode;
  navOpen: boolean;
}

const NavItem = ({ to, label, icon, navOpen }: NavItemProps) => {
  return (
    <Tooltip title={navOpen ? '' : label} placement="right" disableInteractive>
      <ListItem disablePadding sx={{ px: 1.25, py: 0.3 }}>
        <ListItemButton
          component={NavLink}
          to={to}
          sx={(theme) => ({
            borderRadius: '12px',
            color: 'text.secondary',
            py: 0.85,
            px: navOpen ? 1.5 : 1,
            justifyContent: navOpen ? 'flex-start' : 'center',
            transition: theme.transitions.create(['background-color', 'color']),
            '& .MuiListItemIcon-root': {
              color: 'text.secondary',
              minWidth: navOpen ? 34 : 0,
              justifyContent: 'center',
            },
            '&:hover': {
              bgcolor: 'action.hover',
              color: 'text.primary',
              '& .MuiListItemIcon-root': { color: 'text.primary' },
            },
            '&.active': {
              background:
                theme.palette.mode === 'dark'
                  ? `linear-gradient(90deg, ${alpha(auroraColors.violetLight, 0.22)}, ${alpha(auroraColors.violetLight, 0.05)})`
                  : `linear-gradient(90deg, ${alpha(auroraColors.violet, 0.14)}, ${alpha(auroraColors.violet, 0.03)})`,
              color: 'primary.main',
              boxShadow: `inset 0 0 0 1px ${
                theme.palette.mode === 'dark'
                  ? alpha(auroraColors.violetLight, 0.3)
                  : alpha(auroraColors.violet, 0.22)
              }`,
              '& .MuiListItemIcon-root': { color: 'primary.main' },
              '& .MuiListItemText-primary': { fontWeight: 600 },
            },
          })}
        >
          <ListItemIcon>{icon}</ListItemIcon>
          {navOpen && <ListItemText primary={label} />}
        </ListItemButton>
      </ListItem>
    </Tooltip>
  );
};

export default NavItem;
