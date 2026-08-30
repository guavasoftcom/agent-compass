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
import {
  ToolUsageIcon,
  TokenUsageIcon,
  CostIcon,
  SessionsIcon,
  TracesIcon,
  LogsIcon,
  MetricsIcon,
  ReportIcon,
  TrendReportIcon,
  SettingsIcon,
} from './NavIcons';

export interface NavLeaf {
  to: string;
  label: string;
  icon: ReactNode;
}

export interface NavGroup {
  heading: string;
  items: NavLeaf[];
}

// Sidebar order + grouping only. Routes (including the children of /tools)
// live in App.tsx. Headings are presentational — they don't map to routes.
const navGroups: NavGroup[] = [
  {
    heading: 'Activity',
    items: [
      { to: '/cost', label: 'Cost', icon: <CostIcon /> },
      { to: '/tools', label: 'Tool Usage', icon: <ToolUsageIcon /> },
      { to: '/tokens', label: 'Token Usage', icon: <TokenUsageIcon /> },
      { to: '/sessions', label: 'Sessions', icon: <SessionsIcon /> },
    ],
  },
  {
    heading: 'Observability',
    items: [
      { to: '/logs', label: 'Logs', icon: <LogsIcon /> },
      { to: '/metrics', label: 'Metrics', icon: <MetricsIcon /> },
      { to: '/traces', label: 'Traces', icon: <TracesIcon /> },
    ],
  },
  {
    heading: 'Reports',
    items: [
      { to: '/report', label: 'Tuning Report', icon: <ReportIcon /> },
      { to: '/trend-report', label: 'Trend Report', icon: <TrendReportIcon /> },
    ],
  },
  {
    heading: 'System',
    items: [{ to: '/settings', label: 'Settings', icon: <SettingsIcon /> }],
  },
];

/**
 * Heading of the nav group that owns a given route, or undefined if none match.
 * Used to build the "Group · Tab" eyebrow above page titles. Matches by `to`
 * being a prefix of the pathname so child routes (e.g. /tool-activity/calls)
 * resolve to their parent nav item's group.
 */
export const groupForPath = (pathname: string): string | undefined => {
  for (const group of navGroups) {
    if (group.items.some((item) => pathname.startsWith(item.to))) {
      return group.heading;
    }
  }
  return undefined;
};

export default navGroups;
