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
import { useState, type MouseEvent, type ReactNode } from 'react';
import {
  alpha,
  Box,
  IconButton,
  MenuItem,
  MenuList,
  Popover,
  Tooltip,
  Typography,
  type Theme,
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import CloseIcon from '@mui/icons-material/Close';
import { radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import type { AttributeFilter, MetricFacet } from '../../metricsApi';
import { applyFilterSelection, findFilterForKey, isFilterActive, removeFilterAt } from './attributeFilters';

export interface AttributeFilterControlProps {
  /** The active filters, ANDed together (at most one per key). */
  filters: AttributeFilter[];
  onFiltersChange: (next: AttributeFilter[]) => void;
  /** The selected metric's filterable attributes. Undefined until the picker has been opened once. */
  facets?: MetricFacet[];
  isFacetsLoading?: boolean;
  facetsErrorMessage?: string | null;
  /** Reported on open/close so the container can fetch facets lazily. */
  onFacetPickerOpenChange: (isOpen: boolean) => void;
}

const NO_FILTERABLE_ATTRIBUTES_MESSAGE = 'No filterable attributes for this metric in the window';

const pillSx = (theme: Theme) => ({
  display: 'inline-flex',
  alignItems: 'center',
  height: 32,
  px: '11px',
  m: 0,
  borderRadius: radii.xs,
  border: `1px dashed ${theme.palette.divider}`,
  bgcolor: 'transparent',
  color: theme.palette.text.secondary,
  fontFamily: 'inherit',
  fontSize: 13,
  fontWeight: 500,
  cursor: 'pointer',
  '&:hover:not(:disabled)': {
    color: theme.palette.primary.main,
    borderColor: alpha(theme.palette.primary.main, 0.4),
  },
  '&:focus-visible': { outline: `2px solid ${theme.palette.primary.main}`, outlineOffset: 2 },
  '&:disabled': { opacity: 0.45, cursor: 'not-allowed' },
});

const FilterChip = ({ filter, onRemove }: { filter: AttributeFilter; onRemove: () => void }) => (
  <Box
    sx={(theme) => ({
      display: 'inline-flex',
      alignItems: 'center',
      gap: '7px',
      height: 32,
      pl: '11px',
      pr: '7px',
      maxWidth: '100%',
      borderRadius: radii.xs,
      bgcolor: theme.palette.action.selected,
      boxShadow: `inset 0 0 0 1px ${alpha(theme.palette.primary.main, 0.4)}`,
      fontSize: 13,
      fontWeight: 500,
    })}
  >
    <Box component="span" sx={{ color: 'text.secondary', whiteSpace: 'nowrap' }}>
      {filter.key} =
    </Box>
    <Box
      component="span"
      title={filter.value}
      sx={{ fontWeight: 600, color: 'primary.main', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
    >
      {filter.value}
    </Box>
    <Box
      component="button"
      type="button"
      aria-label={`Remove filter ${filter.key} = ${filter.value}`}
      onClick={onRemove}
      sx={(theme) => ({
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        p: '2px',
        m: 0,
        border: 'none',
        borderRadius: '50%',
        bgcolor: 'transparent',
        color: theme.palette.text.disabled,
        cursor: 'pointer',
        '&:hover': { color: theme.palette.text.primary, bgcolor: theme.palette.action.hover },
        '&:focus-visible': { outline: `2px solid ${theme.palette.primary.main}` },
      })}
    >
      <CloseIcon sx={{ fontSize: 15 }} />
    </Box>
  </Box>
);

const PickerMessage = ({ children, isError = false }: { children: ReactNode; isError?: boolean }) => (
  <Typography variant="caption" sx={{ display: 'block', px: 1.5, py: 1.25, color: isError ? 'error.main' : 'text.secondary' }}>
    {children}
  </Typography>
);

const PickerHeading = ({ children }: { children: ReactNode }) => (
  <Box sx={{ typography: 'eyebrowSm', color: 'text.disabled', px: 1.5, pt: 1, pb: 0.5 }}>{children}</Box>
);

/**
 * The left side of the facet bar: one removable chip per active filter (they AND together), then
 * a dashed "+ Add filter" pill that is always shown. The pill opens a two-step popover: the
 * metric's attribute keys, then that key's values with their label-set counts. Choosing a value
 * appends a chip and closes the popover; choosing a value for a key that already has a chip
 * REPLACES that chip (two values of one key ANDed would match nothing), and the exact pairs
 * already active are not offered. The pill is disabled once the (lazily fetched) facets are known
 * to be empty.
 *
 * The anchor and the picker's step are local UI state — the filters, and the facets, are owned by
 * the container. Parents should `key` this control by metric so a metric switch starts it closed.
 */
const AttributeFilterControl = ({
  filters,
  onFiltersChange,
  facets,
  isFacetsLoading = false,
  facetsErrorMessage = null,
  onFacetPickerOpenChange,
}: AttributeFilterControlProps) => {
  const [anchorElement, setAnchorElement] = useState<HTMLElement | null>(null);
  const [pendingKey, setPendingKey] = useState<string | null>(null);

  const hasNoFacets =
    facets !== undefined && facets.length === 0 && !isFacetsLoading && !facetsErrorMessage;

  const openPicker = (event: MouseEvent<HTMLElement>) => {
    setAnchorElement(event.currentTarget);
    setPendingKey(null);
    onFacetPickerOpenChange(true);
  };

  const closePicker = () => {
    setAnchorElement(null);
    onFacetPickerOpenChange(false);
  };

  const applyValue = (key: string, value: string) => {
    onFiltersChange(applyFilterSelection(filters, { key, value }));
    closePicker();
  };

  const activeFacet = pendingKey === null ? undefined : facets?.find((facet) => facet.key === pendingKey);
  // The exact pairs already active are not offered again; the rest of the key's values are.
  const offeredValues = activeFacet?.values.filter(
    (facetValue) => !isFilterActive(filters, activeFacet.key, facetValue.value),
  );
  const replacedFilter = activeFacet ? findFilterForKey(filters, activeFacet.key) : undefined;

  let pickerBody: ReactNode;
  if (facetsErrorMessage) {
    pickerBody = <PickerMessage isError>Could not load attributes: {facetsErrorMessage}</PickerMessage>;
  } else if (isFacetsLoading || facets === undefined) {
    pickerBody = <PickerMessage>Loading attributes…</PickerMessage>;
  } else if (facets.length === 0) {
    pickerBody = <PickerMessage>{NO_FILTERABLE_ATTRIBUTES_MESSAGE}</PickerMessage>;
  } else if (activeFacet) {
    pickerBody = (
      <>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, px: 0.5, pt: 0.5 }}>
          <IconButton size="small" aria-label="Back to attributes" onClick={() => setPendingKey(null)}>
            <ArrowBackIcon fontSize="small" />
          </IconButton>
          <Box sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 13 }}>{activeFacet.key}</Box>
        </Box>
        {replacedFilter && (
          <PickerMessage>Choosing a value replaces {replacedFilter.key} = {replacedFilter.value}</PickerMessage>
        )}
        {offeredValues && offeredValues.length === 0 && <PickerMessage>No other values in the window</PickerMessage>}
        <MenuList key={activeFacet.key} autoFocusItem dense sx={{ py: 0.5 }}>
          {(offeredValues ?? []).map((facetValue) => (
            <MenuItem
              key={facetValue.value}
              // Explicit: the value and its count are sibling inline spans, which would otherwise
              // run together in the accessible name ("vscode15").
              aria-label={`${facetValue.value}, ${facetValue.count} active label-sets`}
              onClick={() => applyValue(activeFacet.key, facetValue.value)}
              sx={{ borderRadius: radii.xs, justifyContent: 'space-between', gap: 1.5, fontSize: 13 }}
            >
              <Box component="span" sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {facetValue.value}
              </Box>
              <Box
                component="span"
                title={`${facetValue.count} active label-sets`}
                sx={{ color: 'text.disabled', fontVariantNumeric: 'tabular-nums', fontSize: 12 }}
              >
                {facetValue.count}
              </Box>
            </MenuItem>
          ))}
        </MenuList>
      </>
    );
  } else {
    pickerBody = (
      <>
        <PickerHeading>Filter by attribute</PickerHeading>
        <MenuList key="attribute-keys" autoFocusItem dense sx={{ py: 0.5 }}>
          {facets.map((facet) => (
            <MenuItem
              key={facet.key}
              onClick={() => setPendingKey(facet.key)}
              sx={{ borderRadius: radii.xs, justifyContent: 'space-between', gap: 1.5, fontSize: 13 }}
            >
              {facet.key}
              <ChevronRightIcon fontSize="small" sx={{ color: 'text.disabled' }} />
            </MenuItem>
          ))}
        </MenuList>
      </>
    );
  }

  const pill = (
    <Box
      component="button"
      type="button"
      disabled={hasNoFacets}
      aria-haspopup="menu"
      aria-expanded={anchorElement !== null}
      onClick={openPicker}
      sx={pillSx}
    >
      + Add filter
    </Box>
  );

  return (
    <>
      {filters.map((filter, index) => (
        <FilterChip
          key={filter.key}
          filter={filter}
          onRemove={() => onFiltersChange(removeFilterAt(filters, index))}
        />
      ))}
      {hasNoFacets ? (
        // A disabled button emits no pointer events, so the tooltip hangs off a wrapper.
        <Tooltip title={NO_FILTERABLE_ATTRIBUTES_MESSAGE} arrow>
          <Box component="span" sx={{ display: 'inline-flex' }}>
            {pill}
          </Box>
        </Tooltip>
      ) : (
        pill
      )}
      <Popover
        open={anchorElement !== null}
        anchorEl={anchorElement}
        onClose={closePicker}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
        transformOrigin={{ vertical: 'top', horizontal: 'left' }}
        slotProps={{
          paper: {
            sx: (theme) => ({
              mt: 0.75,
              width: 264,
              maxHeight: 360,
              borderRadius: radii.sm,
              border: `1px solid ${theme.palette.divider}`,
              backgroundImage: 'none',
              backgroundColor: theme.palette.background.paper,
              p: 0.5,
            }),
          },
        }}
      >
        {pickerBody}
      </Popover>
    </>
  );
};

export default AttributeFilterControl;
