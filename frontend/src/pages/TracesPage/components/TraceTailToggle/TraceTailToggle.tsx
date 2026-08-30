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
import LiveTailToggle, { type LiveTailToggleProps } from '../../../../components/LiveTailToggle';

export interface TraceTailToggleProps {
  active: boolean;
  locked: boolean;
  tooltip?: string;
  onToggle: () => void;
}

// Thin page-scoped wrapper around the shared LiveTailToggle.
// Keeps the import surface stable for TracesPageView while delegating
// all styling and behaviour to the shared component.
const TraceTailToggle = (props: TraceTailToggleProps) => {
  const liveTailProps: LiveTailToggleProps = {
    active: props.active,
    locked: props.locked,
    tooltip: props.tooltip,
    onToggle: props.onToggle,
  };
  return <LiveTailToggle {...liveTailProps} />;
};

export default TraceTailToggle;
