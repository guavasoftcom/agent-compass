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
