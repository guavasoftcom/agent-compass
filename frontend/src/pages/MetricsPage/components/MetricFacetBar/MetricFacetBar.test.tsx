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
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../../../test/renderWithProviders';
import type { AttributeFilter, MetricFacet } from '../../metricsApi';
import MetricFacetBar, { type MetricFacetBarProps } from './MetricFacetBar';

const FACETS: MetricFacet[] = [
  {
    key: 'model',
    values: [
      { value: 'claude-sonnet-4', count: 14 },
      { value: 'claude-opus-4', count: 9 },
    ],
  },
  {
    key: 'terminal.type',
    values: [
      { value: 'vscode', count: 15 },
      { value: 'iTerm.app', count: 7 },
    ],
  },
];

const NO_FILTERABLE_ATTRIBUTES = 'No filterable attributes for this metric in the window';

// Fresh spies per test: the props share no state between cases.
const buildProps = (): MetricFacetBarProps => ({
  splitKeys: ['None', 'Model'],
  split: 'None',
  onSplitChange: vi.fn(),
  aggregation: 'sum',
  onAggregationChange: vi.fn(),
  filters: [],
  onFiltersChange: vi.fn(),
  facets: FACETS,
  isFacetsLoading: false,
  facetsErrorMessage: null,
  onFacetPickerOpenChange: vi.fn(),
});

const modelSonnet: AttributeFilter = { key: 'model', value: 'claude-sonnet-4' };
const terminalVscode: AttributeFilter = { key: 'terminal.type', value: 'vscode' };

// The bar is controlled, so a test that needs choosing a value to add a chip renders it through
// this stand-in for the view/container. It still forwards to the spies.
const StatefulFacetBar = (props: MetricFacetBarProps) => {
  const [filters, setFilters] = useState<AttributeFilter[]>(props.filters);
  return (
    <MetricFacetBar
      {...props}
      filters={filters}
      onFiltersChange={(next) => {
        props.onFiltersChange(next);
        setFilters(next);
      }}
    />
  );
};

const renderBar = (props: MetricFacetBarProps) =>
  renderWithProviders(<StatefulFacetBar {...props} />);

const openPicker = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole('button', { name: '+ Add filter' }));
};

describe('MetricFacetBar', () => {
  describe('filter chips', () => {
    it('always shows the bar with an add pill, even with no filters', () => {
      renderBar(buildProps());

      expect(screen.getByText('Filter')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '+ Add filter' })).toBeEnabled();
      expect(screen.queryByRole('button', { name: /^Remove filter/ })).not.toBeInTheDocument();
    });

    it('renders one chip per active filter, with the add pill still after them', () => {
      renderBar({ ...buildProps(), filters: [modelSonnet, terminalVscode] });

      expect(screen.getByText('model =')).toBeInTheDocument();
      expect(screen.getByText('claude-sonnet-4')).toBeInTheDocument();
      expect(screen.getByText('terminal.type =')).toBeInTheDocument();
      expect(screen.getByText('vscode')).toBeInTheDocument();

      const addPill = screen.getByRole('button', { name: '+ Add filter' });
      const lastRemoveButton = screen.getByRole('button', { name: 'Remove filter terminal.type = vscode' });
      // The pill follows the chips in document order.
      expect(lastRemoveButton.compareDocumentPosition(addPill) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('removes just the chip whose x was clicked and keeps the others', async () => {
      const user = userEvent.setup();
      const props = { ...buildProps(), filters: [modelSonnet, terminalVscode] };
      renderBar(props);

      await user.click(screen.getByRole('button', { name: 'Remove filter model = claude-sonnet-4' }));

      expect(props.onFiltersChange).toHaveBeenCalledWith([terminalVscode]);
      expect(screen.queryByRole('button', { name: 'Remove filter model = claude-sonnet-4' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Remove filter terminal.type = vscode' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '+ Add filter' })).toBeInTheDocument();
    });

    it('reports an empty list when the last chip is removed', async () => {
      const user = userEvent.setup();
      const props = { ...buildProps(), filters: [modelSonnet] };
      renderBar(props);

      await user.click(screen.getByRole('button', { name: 'Remove filter model = claude-sonnet-4' }));

      expect(props.onFiltersChange).toHaveBeenCalledWith([]);
      expect(screen.getByRole('button', { name: '+ Add filter' })).toBeInTheDocument();
    });
  });

  describe('filter picker', () => {
    it('lists keys, then the chosen key\'s values with counts, and applies the chosen value', async () => {
      const user = userEvent.setup();
      const props = buildProps();
      renderBar(props);

      await openPicker(user);
      expect(props.onFacetPickerOpenChange).toHaveBeenLastCalledWith(true);
      expect(await screen.findByRole('menuitem', { name: 'model' })).toBeInTheDocument();
      expect(screen.getByRole('menuitem', { name: 'terminal.type' })).toBeInTheDocument();

      await user.click(screen.getByRole('menuitem', { name: 'terminal.type' }));
      expect(await screen.findByRole('menuitem', { name: 'vscode, 15 active label-sets' })).toBeInTheDocument();
      expect(screen.getByRole('menuitem', { name: 'iTerm.app, 7 active label-sets' })).toBeInTheDocument();
      // The counts are visible too, not just in the accessible name.
      expect(screen.getByText('15')).toBeInTheDocument();
      // The key list is gone while a key's values are showing.
      expect(screen.queryByRole('menuitem', { name: 'model' })).not.toBeInTheDocument();

      await user.click(screen.getByRole('menuitem', { name: 'iTerm.app, 7 active label-sets' }));

      expect(props.onFiltersChange).toHaveBeenCalledWith([{ key: 'terminal.type', value: 'iTerm.app' }]);
      expect(props.onFacetPickerOpenChange).toHaveBeenLastCalledWith(false);
      await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
      expect(screen.getByText('terminal.type =')).toBeInTheDocument();
      // The pill stays available for another filter.
      expect(screen.getByRole('button', { name: '+ Add filter' })).toBeInTheDocument();
    });

    it('appends a second chip for a different key, keeping the first (they AND together)', async () => {
      const user = userEvent.setup();
      const props = { ...buildProps(), filters: [modelSonnet] };
      renderBar(props);

      await openPicker(user);
      await user.click(await screen.findByRole('menuitem', { name: 'terminal.type' }));
      await user.click(await screen.findByRole('menuitem', { name: 'vscode, 15 active label-sets' }));

      expect(props.onFiltersChange).toHaveBeenCalledWith([modelSonnet, terminalVscode]);
      await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
      expect(screen.getByRole('button', { name: 'Remove filter model = claude-sonnet-4' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Remove filter terminal.type = vscode' })).toBeInTheDocument();
    });

    it('replaces the existing chip when a value is picked for a key that already has one', async () => {
      const user = userEvent.setup();
      const props = { ...buildProps(), filters: [modelSonnet, terminalVscode] };
      renderBar(props);

      await openPicker(user);
      await user.click(await screen.findByRole('menuitem', { name: 'model' }));
      expect(await screen.findByText('Choosing a value replaces model = claude-sonnet-4')).toBeInTheDocument();
      await user.click(await screen.findByRole('menuitem', { name: 'claude-opus-4, 9 active label-sets' }));

      // Replaced in place: the model chip keeps its position and there is still one model chip.
      expect(props.onFiltersChange).toHaveBeenCalledWith([{ key: 'model', value: 'claude-opus-4' }, terminalVscode]);
      await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
      expect(screen.getByRole('button', { name: 'Remove filter model = claude-opus-4' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Remove filter model = claude-sonnet-4' })).not.toBeInTheDocument();
      expect(screen.getAllByRole('button', { name: /^Remove filter model = / })).toHaveLength(1);
    });

    it('does not offer a key = value pair that is already active', async () => {
      const user = userEvent.setup();
      renderBar({ ...buildProps(), filters: [modelSonnet] });

      await openPicker(user);
      await user.click(await screen.findByRole('menuitem', { name: 'model' }));

      expect(await screen.findByRole('menuitem', { name: 'claude-opus-4, 9 active label-sets' })).toBeInTheDocument();
      expect(screen.queryByRole('menuitem', { name: /^claude-sonnet-4,/ })).not.toBeInTheDocument();
    });

    it('says so when every value of the chosen key is already active', async () => {
      const user = userEvent.setup();
      const singleValueFacets: MetricFacet[] = [{ key: 'model', values: [{ value: 'claude-sonnet-4', count: 3 }] }];
      renderBar({ ...buildProps(), filters: [modelSonnet], facets: singleValueFacets });

      await openPicker(user);
      await user.click(await screen.findByRole('menuitem', { name: 'model' }));

      expect(await screen.findByText('No other values in the window')).toBeInTheDocument();
      expect(screen.queryByRole('menuitem', { name: /active label-sets/ })).not.toBeInTheDocument();
    });

    it('goes back from a key\'s values to the key list', async () => {
      const user = userEvent.setup();
      renderBar(buildProps());

      await openPicker(user);
      await user.click(await screen.findByRole('menuitem', { name: 'model' }));
      await user.click(await screen.findByRole('button', { name: 'Back to attributes' }));

      expect(await screen.findByRole('menuitem', { name: 'terminal.type' })).toBeInTheDocument();
      expect(screen.queryByRole('menuitem', { name: /claude-sonnet-4/ })).not.toBeInTheDocument();
    });

    it('closes on Escape without applying anything', async () => {
      const user = userEvent.setup();
      const props = buildProps();
      renderBar(props);

      await openPicker(user);
      await screen.findByRole('menuitem', { name: 'model' });
      await user.keyboard('{Escape}');

      expect(props.onFacetPickerOpenChange).toHaveBeenLastCalledWith(false);
      expect(props.onFiltersChange).not.toHaveBeenCalled();
      await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
    });

    it('shows a loading line while the facets are fetching', async () => {
      const user = userEvent.setup();
      renderBar({ ...buildProps(), facets: undefined, isFacetsLoading: true });

      await openPicker(user);

      expect(await screen.findByText('Loading attributes…')).toBeInTheDocument();
      expect(screen.queryByRole('menuitem')).not.toBeInTheDocument();
    });

    it('shows the error message when the facets failed to load', async () => {
      const user = userEvent.setup();
      renderBar({ ...buildProps(), facets: undefined, facetsErrorMessage: 'boom' });

      await openPicker(user);

      expect(await screen.findByText('Could not load attributes: boom')).toBeInTheDocument();
      expect(screen.queryByText('Loading attributes…')).not.toBeInTheDocument();
    });

    it('disables the pill, with an explanatory tooltip, once the facets are known to be empty', async () => {
      const user = userEvent.setup();
      const props = { ...buildProps(), facets: [] as MetricFacet[] };
      renderBar(props);

      const pill = screen.getByRole('button', { name: '+ Add filter' });
      expect(pill).toBeDisabled();

      await user.click(pill);
      expect(props.onFacetPickerOpenChange).not.toHaveBeenCalled();

      // A disabled button takes no pointer events, so the tooltip hangs off its wrapper.
      await user.hover(pill.parentElement as HTMLElement);
      expect(await screen.findByText(NO_FILTERABLE_ATTRIBUTES)).toBeInTheDocument();
    });

    it('keeps the disabled pill visible next to existing chips', () => {
      renderBar({ ...buildProps(), filters: [modelSonnet], facets: [] as MetricFacet[] });

      expect(screen.getByRole('button', { name: '+ Add filter' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Remove filter model = claude-sonnet-4' })).toBeInTheDocument();
    });
  });

  describe('Agg control', () => {
    it('reports a chosen aggregation', async () => {
      const user = userEvent.setup();
      const props = buildProps();
      renderBar(props);

      await user.click(screen.getByRole('button', { name: 'p95' }));

      expect(props.onAggregationChange).toHaveBeenCalledWith('p95');
    });
  });

  describe('Split by', () => {
    it('is shown and enabled for a sum aggregation', () => {
      renderBar(buildProps());

      expect(screen.getByText('Split by')).toBeInTheDocument();
      const groupBy = screen.getByRole('group', { name: 'Group by' });
      expect(groupBy).not.toHaveAttribute('aria-disabled');
      expect(groupBy).not.toHaveAttribute('inert');
    });

    it('is disabled, with an explanatory tooltip, under a non-sum aggregation', async () => {
      const user = userEvent.setup();
      renderBar({ ...buildProps(), aggregation: 'avg' });

      const groupBy = screen.getByRole('group', { name: 'Group by' });
      expect(groupBy).toHaveAttribute('aria-disabled', 'true');
      expect(groupBy).toHaveAttribute('inert');

      await user.hover(groupBy);
      expect(
        await screen.findByText(
          'Averages and percentiles are not additive across groups - switch Agg back to sum to group',
        ),
      ).toBeInTheDocument();
    });

    it('is omitted for a metric with no splits, while Filter and Agg remain', () => {
      renderBar({ ...buildProps(), splitKeys: ['None'] });

      expect(screen.queryByText('Split by')).not.toBeInTheDocument();
      expect(screen.getByText('Filter')).toBeInTheDocument();
      expect(screen.getByText('Agg')).toBeInTheDocument();
    });
  });
});
