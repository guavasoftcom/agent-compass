import { useState } from 'react';
import { Outlet } from 'react-router-dom';
import {
  Box,
  Container,
  Divider,
  Drawer,
  IconButton,
  List,
  Stack,
  Tooltip,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import MenuIcon from '@mui/icons-material/Menu';
import MenuOpenIcon from '@mui/icons-material/MenuOpen';
import NavItem from './NavItem';
import ColorModeToggle from './ColorModeToggle';
import AuroraMark from './AuroraMark';
import navGroups from './navGroups';

const DRAWER_WIDTH_OPEN = 244;
const DRAWER_WIDTH_COLLAPSED = 76;
const NAV_STORAGE_KEY = 'navOpen';

const readInitialNavOpen = (): boolean => {
  if (typeof window === 'undefined') {
    return true;
  }
  const stored = window.localStorage?.getItem(NAV_STORAGE_KEY);
  if (stored === 'false') {
    return false;
  }
  return true;
};

const persistNavOpen = (open: boolean): void => {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage?.setItem(NAV_STORAGE_KEY, String(open));
  } catch {
    // ignore quota / disabled storage
  }
};

const AppShell = () => {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('md'));
  const [navOpen, setNavOpen] = useState(readInitialNavOpen);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const drawerWidth = navOpen ? DRAWER_WIDTH_OPEN : DRAWER_WIDTH_COLLAPSED;

  const toggleNav = () => {
    if (isMobile) {
      setMobileNavOpen((previous) => !previous);
      return;
    }
    setNavOpen((previous) => {
      const next = !previous;
      persistNavOpen(next);
      return next;
    });
  };

  const closeMobileNav = () => {
    setMobileNavOpen(false);
  };

  // `open` reflects the expanded vs. icon-rail state for this render of the rail.
  const renderRail = (open: boolean) => (
    <Stack sx={{ height: '100%', minHeight: 0 }}>
      {/* Brand block — logo lives in the sidebar, top of page */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1.25,
          px: open ? 2.25 : 0,
          py: 2.25,
          justifyContent: open ? 'flex-start' : 'center',
          minHeight: 72,
        }}
      >
        <AuroraMark size={36} />
        {open && (
          <Typography
            variant="h6"
            noWrap
            sx={{ display: 'inline-flex', alignItems: 'baseline', gap: 0.75, fontSize: 18 }}
          >
            <Box component="span" sx={{ fontWeight: 800, letterSpacing: 0.2 }}>
              Agent
            </Box>
            <Box component="span" sx={{ color: 'text.secondary', fontWeight: 500 }}>
              Compass
            </Box>
          </Typography>
        )}
      </Box>

      {/* Grouped navigation */}
      <Box
        sx={{ flex: 1, minHeight: 0, overflowY: 'auto', overflowX: 'hidden', py: 1 }}
        onClick={isMobile ? closeMobileNav : undefined}
      >
        {navGroups.map((group, groupIndex) => (
          <Box key={group.heading} sx={{ mb: 0.5 }}>
            {open ? (
              <Typography
                variant="overline"
                sx={{
                  display: 'block',
                  px: 3,
                  pt: groupIndex === 0 ? 1.25 : 2,
                  pb: 0.5,
                  color: 'text.secondary',
                  fontWeight: 700,
                  fontSize: 10.5,
                  letterSpacing: 1.4,
                  lineHeight: 1,
                }}
              >
                {group.heading}
              </Typography>
            ) : (
              groupIndex > 0 && <Divider sx={{ mx: 1.5, my: 1, opacity: 0.6 }} />
            )}
            <List disablePadding>
              {group.items.map((item) => (
                <NavItem
                  key={item.to}
                  to={item.to}
                  label={item.label}
                  icon={item.icon}
                  navOpen={open}
                />
              ))}
            </List>
          </Box>
        ))}
      </Box>

      {/* Footer — color mode + collapse control pinned to the bottom */}
      <Divider sx={{ mx: open ? 2 : 1.25, opacity: 0.7 }} />
      <Stack
        direction={open ? 'row' : 'column'}
        spacing={0.5}
        sx={{
          alignItems: 'center',
          justifyContent: open ? 'space-between' : 'center',
          px: open ? 1.5 : 0,
          py: 1.25,
        }}
      >
        <ColorModeToggle />
        {!isMobile && (
          <Tooltip title={open ? 'Collapse navigation' : 'Expand navigation'} placement="right">
            <IconButton onClick={toggleNav} size="small" color="inherit" aria-label="toggle navigation">
              {open ? <MenuOpenIcon fontSize="small" /> : <MenuIcon fontSize="small" />}
            </IconButton>
          </Tooltip>
        )}
      </Stack>
    </Stack>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      {/* Desktop: permanent full-height rail. Mobile: temporary overlay drawer. */}
      {isMobile ? (
        <>
          <Box
            sx={{
              position: 'fixed',
              top: 12,
              left: 12,
              zIndex: (t) => t.zIndex.drawer + 2,
            }}
          >
            <IconButton
              onClick={toggleNav}
              aria-label="open navigation"
              sx={{
                bgcolor: 'background.paper',
                border: 1,
                borderColor: 'divider',
                boxShadow: 2,
                '&:hover': { bgcolor: 'background.paper' },
              }}
            >
              <MenuIcon />
            </IconButton>
          </Box>
          <Drawer
            variant="temporary"
            open={mobileNavOpen}
            onClose={closeMobileNav}
            ModalProps={{ keepMounted: true }}
            sx={{ '& .MuiDrawer-paper': { width: DRAWER_WIDTH_OPEN, boxSizing: 'border-box' } }}
          >
            {renderRail(true)}
          </Drawer>
        </>
      ) : (
        <Drawer
          variant="permanent"
          sx={{
            width: drawerWidth,
            flexShrink: 0,
            '& .MuiDrawer-paper': {
              width: drawerWidth,
              boxSizing: 'border-box',
              overflowX: 'hidden',
              transition: (t) =>
                t.transitions.create('width', {
                  easing: t.transitions.easing.sharp,
                  duration: t.transitions.duration.shortest,
                }),
            },
          }}
        >
          {renderRail(navOpen)}
        </Drawer>
      )}

      <Box
        component="main"
        sx={{
          flexGrow: 1,
          minWidth: 0,
          p: { xs: 2, sm: 2.5, md: 3.5 },
          pt: { xs: 8, md: 3.5 },
        }}
      >
        <Container maxWidth={false} disableGutters>
          <Outlet />
        </Container>
      </Box>
    </Box>
  );
};

export default AppShell;
