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
import { useEffect, useState, type MouseEvent } from 'react';
import {
  alpha,
  Box,
  Button,
  Divider,
  Menu,
  MenuItem,
  Typography,
} from '@mui/material';
import { auroraColors, gradients, neutralColors } from '../../theme/colors';
import ArrowDropDownIcon from '@mui/icons-material/ArrowDropDown';
import AccessTimeIcon from '@mui/icons-material/AccessTime';
import CheckIcon from '@mui/icons-material/Check';
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft';
import type { WindowOption } from '../../lib/constants';
import AuroraCalendar from './AuroraCalendar';
import { fontFamilies } from '../../theme/typography';
import { radii } from '../../theme/theme';

export interface WindowSelectorViewProps {
  windows: readonly WindowOption[];
  buttonLabel: string;
  /** True when the active selection is a custom range (controls the entry-row summary). */
  isCustomActive: boolean;
  customSummary: string | null;
  anchor: HTMLElement | null;
  onAnchorOpen: (event: MouseEvent<HTMLElement>) => void;
  onAnchorClose: () => void;
  startInput: string;
  endInput: string;
  onStartInputChange: (next: string) => void;
  onEndInputChange: (next: string) => void;
  customApplyDisabled: boolean;
  rangeTooLarge: boolean;
  onApplyCustomRange: () => void;
  isPresetSelected: (minutes: number) => boolean;
  onPresetSelect: (minutes: number) => void;
}

const WindowSelectorView = ({
  windows,
  buttonLabel,
  isCustomActive,
  customSummary,
  anchor,
  onAnchorOpen,
  onAnchorClose,
  startInput,
  endInput,
  onStartInputChange,
  onEndInputChange,
  customApplyDisabled,
  rangeTooLarge,
  onApplyCustomRange,
  isPresetSelected,
  onPresetSelect,
}: WindowSelectorViewProps) => {
  // Two-view dropdown: 'presets' (compact list, default) → 'custom' (calendar).
  const [panel, setPanel] = useState<'presets' | 'custom'>('presets');

  // Open straight to the calendar when a custom range is active; otherwise the
  // presets list. Re-runs each time the menu opens (anchor flips to non-null).
  useEffect(() => {
    if (anchor != null) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setPanel(isCustomActive ? 'custom' : 'presets');
    }
  }, [anchor, isCustomActive]);

  return (
    <>
      <Button
        variant="outlined"
        size="medium"
        startIcon={<AccessTimeIcon sx={{ color: 'primary.main' }} />}
        endIcon={<ArrowDropDownIcon />}
        onClick={onAnchorOpen}
        sx={{
          minWidth: 188,
          justifyContent: 'space-between',
          flexShrink: 0,
          fontWeight: 500,
        }}
      >
        {buttonLabel}
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
              width: 332,
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
        {panel === 'presets'
          ? [
              <Box key="presets" sx={{ p: 1 }}>
                {windows.map((window) => {
                  const selected = isPresetSelected(window.value);
                  return (
                    <MenuItem
                      key={window.value}
                      selected={selected}
                      onClick={() => onPresetSelect(window.value)}
                      sx={(theme) => ({
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
                      })}
                    >
                      {window.label}
                      {selected && (
                        <CheckIcon fontSize="small" sx={{ color: 'primary.main' }} />
                      )}
                    </MenuItem>
                  );
                })}
              </Box>,
              <Divider key="divider" />,
              // Entry row → opens the calendar view. Not a MenuItem so its multi-line
              // layout and the chevron read as a navigation affordance.
              <Box
                key="entry"
                role="button"
                onClick={() => setPanel('custom')}
                sx={(theme) => ({
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1.25,
                  m: 1,
                  px: 1.5,
                  py: 1.25,
                  borderRadius: radii.sm,
                  cursor: 'pointer',
                  border: `1px solid ${theme.palette.divider}`,
                  transition: theme.transitions.create(['background-color', 'border-color']),
                  '&:hover': {
                    backgroundColor: theme.palette.action.hover,
                    borderColor: theme.palette.primary.main,
                  },
                })}
              >
                <CalendarMonthIcon sx={{ fontSize: 20, color: 'primary.main' }} />
                <Box sx={{ minWidth: 0 }}>
                  <Typography
                    sx={{
                      fontSize: 14,
                      fontWeight: 600,
                      lineHeight: 1.2,
                      color: isCustomActive ? 'primary.main' : 'text.primary',
                    }}
                  >
                    Custom range
                  </Typography>
                  <Typography
                    noWrap
                    sx={{ fontSize: 11.5, color: 'text.secondary', mt: '1px' }}
                  >
                    {isCustomActive && customSummary
                      ? customSummary
                      : 'Pick a start & end date'}
                  </Typography>
                </Box>
                <ChevronRightIcon sx={{ ml: 'auto', fontSize: 20, color: 'text.secondary' }} />
              </Box>,
            ]
          : [
              <Box
                key="back"
                role="button"
                onClick={() => setPanel('presets')}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1,
                  px: 2,
                  pt: 1.75,
                  pb: 1.25,
                  cursor: 'pointer',
                  color: 'text.secondary',
                  typography: 'eyebrowSm',
                  transition: (theme) => theme.transitions.create('color'),
                  '&:hover': { color: 'primary.main' },
                }}
              >
                <ChevronLeftIcon sx={{ fontSize: 18 }} />
                Back to presets
              </Box>,
              <Box
                key="calendar"
                onKeyDown={(event) => event.stopPropagation()}
                sx={{ px: 2, pb: 1.5 }}
              >
                <AuroraCalendar
                  startInput={startInput}
                  endInput={endInput}
                  onStartInputChange={onStartInputChange}
                  onEndInputChange={onEndInputChange}
                />

                {rangeTooLarge && (
                  <Typography
                    variant="caption"
                    color="error"
                    sx={{ display: 'block', mt: 1.5 }}
                  >
                    Custom range cannot exceed 30 days.
                  </Typography>
                )}

                <Button
                  fullWidth
                  variant="contained"
                  disableElevation
                  disabled={customApplyDisabled}
                  onClick={onApplyCustomRange}
                  sx={{
                    mt: 1.75,
                    height: 40,
                    borderRadius: radii.lg,
                    fontFamily: fontFamilies.display,
                    fontWeight: 600,
                    background: gradients.auroraAction,
                    boxShadow: `0 8px 18px ${alpha(auroraColors.violet, 0.4)}`,
                    '&:hover': {
                      background: gradients.auroraAction,
                      filter: 'brightness(1.05)',
                    },
                    '&.Mui-disabled': { background: undefined, opacity: 0.5 },
                  }}
                >
                  Apply range
                </Button>
              </Box>,
            ]}
      </Menu>
    </>
  );
};

export default WindowSelectorView;
