import type { ReactNode } from 'react';
import {
  ToolUsageIcon,
  TokenUsageIcon,
  SessionsIcon,
  TracesIcon,
  LogsIcon,
  MetricsIcon,
  ReportIcon,
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

// Sidebar order + grouping only. Routes (including the children of /tool-activity)
// live in App.tsx. Headings are presentational — they don't map to routes.
const navGroups: NavGroup[] = [
  {
    heading: 'Activity',
    items: [
      { to: '/tool-activity', label: 'Tool Usage', icon: <ToolUsageIcon /> },
      { to: '/tokens', label: 'Token Usage', icon: <TokenUsageIcon /> },
      { to: '/sessions', label: 'Sessions', icon: <SessionsIcon /> },
    ],
  },
  {
    heading: 'Observability',
    items: [
      { to: '/logs', label: 'Logs', icon: <LogsIcon /> },
      { to: '/insights', label: 'Metrics', icon: <MetricsIcon /> },
      { to: '/traces', label: 'Traces', icon: <TracesIcon /> },
    ],
  },
  {
    heading: 'Reports',
    items: [
      { to: '/report', label: 'Report', icon: <ReportIcon /> },
    ],
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
