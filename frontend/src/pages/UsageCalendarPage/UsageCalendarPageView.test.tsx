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
import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import type { IdentifierUsageRow } from '../../api';
import UsageCalendarPageView, { type UsageCalendarPageViewProps } from './UsageCalendarPageView';
import type { UsageCalendarDay } from './usageCalendarApi';
import {
  indexDaysByDateKey,
  monthGridDates,
  periodRange,
  type KpiCardModel,
} from './usageCalendarDerivations';

// The view always renders RepositorySelector, which fetches the repository list itself (it is not
// window-scoped and has no page-level query to stub via props). Stub the fetcher rather than let it
// hit the network in jsdom.
vi.mock('../../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api')>();
  return { ...actual, fetchRepositories: vi.fn().mockResolvedValue([]) };
});

// 2026-09-20 is a Sunday: the month grid opens on Sun Aug 30.
const TODAY = new Date(2026, 8, 20);
const ANCHOR = new Date(2026, 8, 20);
// 2026-09-19 is a Saturday, the last day of its Sunday-first week (Sep 13-19), so every day of that
// week is elapsed when it is "today".
const WEEK_END_TODAY = new Date(2026, 8, 19);

const makeDay = (date: string, overrides: Partial<UsageCalendarDay> = {}): UsageCalendarDay => ({
  date,
  costUsd: 0,
  tokens: 0,
  skillCalls: 0,
  subagentCalls: 0,
  sessions: 0,
  activeSeconds: 0,
  linesAdded: 0,
  linesRemoved: 0,
  commits: 0,
  pullRequests: 0,
  decisionsAccepted: 0,
  decisionsRejected: 0,
  costByModel: [],
  ...overrides,
});

const busyDay = makeDay('2026-09-01', {
  costUsd: 73.2,
  tokens: 369_400,
  skillCalls: 5,
  subagentCalls: 2,
  sessions: 4,
  activeSeconds: 7_860,
  linesAdded: 284,
  linesRemoved: 99,
  commits: 0,
  pullRequests: 1,
  decisionsAccepted: 11,
  decisionsRejected: 1,
});
const quietDay = makeDay('2026-09-02');

const kpiCards: KpiCardModel[] = [
  {
    id: 'cost',
    label: 'Cost this month',
    value: '$1,641',
    change: { label: '12.4%', direction: 'down' },
    series: { values: [1, 2, 3], todayIndex: 1, firstFutureIndex: 2 },
    colorIndex: 0,
  },
  {
    id: 'tokens',
    label: 'Tokens this month',
    value: '9.4M',
    change: null,
    series: { values: [1, 2, 3], todayIndex: undefined, firstFutureIndex: undefined },
    colorIndex: 1,
  },
  {
    id: 'skills',
    label: 'Skill invocations',
    value: '157',
    change: { label: '28.3%', direction: 'up' },
    series: { values: [1, 2, 3], todayIndex: undefined, firstFutureIndex: undefined },
    colorIndex: 2,
  },
  {
    id: 'activeDays',
    label: 'Active days',
    value: '18',
    valueSuffix: '/ 20',
    change: null,
    series: { values: [1, 1, 0.08], todayIndex: undefined, firstFutureIndex: undefined },
    colorIndex: 3,
  },
];

const skillUsage: IdentifierUsageRow[] = [
  { tool: 'code-reviewer', calls: 3, byModel: {}, costUsd: 0, costByModel: {} },
  { tool: 'ship', calls: 1, byModel: {}, costUsd: 0, costByModel: {} },
];
const subagentUsage: IdentifierUsageRow[] = [
  { tool: 'Explore', calls: 2, byModel: {}, costUsd: 0, costByModel: {} },
];

const buildProps = (overrides: Partial<UsageCalendarPageViewProps> = {}): UsageCalendarPageViewProps => ({
  view: 'month',
  onViewChange: vi.fn(),
  periodLabel: 'September 2026',
  onPreviousPeriod: vi.fn(),
  onNextPeriod: vi.fn(),
  colorBy: 'cost',
  onColorByChange: vi.fn(),
  repositoryUrl: null,
  onRepositoryUrlChange: vi.fn(),
  onReload: vi.fn(),
  today: TODAY,
  gridDates: monthGridDates(ANCHOR),
  anchorMonth: 8,
  daysByDateKey: indexDaysByDateKey([busyDay, quietDay]),
  heatPercents: new Map([['2026-09-01', 22]]),
  kpiCards,
  isLoading: false,
  error: null,
  selectedDateKey: null,
  isDrawerOpen: false,
  onSelectDay: vi.fn(),
  onCloseDrawer: vi.fn(),
  onOpenInMetrics: vi.fn(),
  isDetailLoading: false,
  modelRows: [],
  skillUsage: [],
  subagentUsage: [],
  ...overrides,
});

const renderView = (overrides: Partial<UsageCalendarPageViewProps> = {}) => {
  const props = buildProps(overrides);
  renderWithProviders(<UsageCalendarPageView {...props} />);
  return props;
};

describe('UsageCalendarPageView header and chrome', () => {
  it('shows the title, the counter-pipeline caveat and the period label', () => {
    renderView();
    expect(screen.getByText('Usage Calendar')).toBeInTheDocument();
    expect(screen.getByText(/can read slightly off the Cost page/)).toBeInTheDocument();
    expect(screen.getByText('September 2026')).toBeInTheDocument();
  });

  it('replaces the window selector with the period pill and hides auto-refresh', () => {
    renderView();
    expect(screen.getByRole('button', { name: 'Previous month' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next month' })).toBeInTheDocument();
    // WindowSelector's presets are absent, and so is the auto-refresh toggle.
    expect(screen.queryByText(/Last 24 hours/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /auto refresh/i })).not.toBeInTheDocument();
  });

  it('calls back for previous, next and reload', async () => {
    const user = userEvent.setup();
    const props = renderView();
    await user.click(screen.getByRole('button', { name: 'Previous month' }));
    await user.click(screen.getByRole('button', { name: 'Next month' }));
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(props.onPreviousPeriod).toHaveBeenCalledTimes(1);
    expect(props.onNextPeriod).toHaveBeenCalledTimes(1);
    expect(props.onReload).toHaveBeenCalledTimes(1);
  });

  it('names the step buttons for the week view', () => {
    renderView({ view: 'week', gridDates: periodRange('week', ANCHOR).days, periodLabel: 'Sep 20 – 26, 2026' });
    expect(screen.getByRole('button', { name: 'Previous week' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next week' })).toBeInTheDocument();
  });

  it('shows an error banner when the rollup failed', () => {
    renderView({ error: new Error('boom') });
    expect(screen.getByRole('alert')).toHaveTextContent('boom');
  });
});

describe('UsageCalendarPageView controls', () => {
  it('switches the view through the Month / Week toggle', async () => {
    const user = userEvent.setup();
    const props = renderView();
    await user.click(screen.getByRole('button', { name: 'Week' }));
    expect(props.onViewChange).toHaveBeenCalledWith('week');
  });

  it('picks a Color by metric from the menu', async () => {
    const user = userEvent.setup();
    const props = renderView();
    await user.click(screen.getByRole('button', { name: /Color by/ }));
    await user.click(screen.getByRole('menuitem', { name: 'Tokens' }));
    expect(props.onColorByChange).toHaveBeenCalledWith('tokens');
  });

  it('shows the selected Color by metric on the trigger', () => {
    renderView({ colorBy: 'skills' });
    expect(screen.getByRole('button', { name: /Color by/ })).toHaveTextContent('Skills');
  });
});

describe('UsageCalendarPageView KPI strip', () => {
  it('renders the four cards with values, the active-days suffix and the deltas that exist', () => {
    renderView();
    expect(screen.getByText('Cost this month')).toBeInTheDocument();
    expect(screen.getByText('$1,641')).toBeInTheDocument();
    expect(screen.getByText('Tokens this month')).toBeInTheDocument();
    expect(screen.getByText('Skill invocations')).toBeInTheDocument();
    expect(screen.getByText('157')).toBeInTheDocument();
    expect(screen.getByText('Active days')).toBeInTheDocument();
    expect(screen.getByText('/ 20')).toBeInTheDocument();
    expect(screen.getByText('12.4%')).toBeInTheDocument();
    expect(screen.getByText('28.3%')).toBeInTheDocument();
  });

  it('draws no per-day bars in the month view, where 28-31 of them would be noise', () => {
    renderView();
    expect(screen.queryByTestId('sparkline-bar')).not.toBeInTheDocument();
    expect(screen.queryByTestId('sparkline-placeholder-bar')).not.toBeInTheDocument();
  });

  it('draws a per-day bar row under each card in the week view, with a placeholder for each future day', () => {
    renderView({ view: 'week', gridDates: periodRange('week', ANCHOR).days, periodLabel: 'Sep 20 – 26, 2026' });
    // Four cards, three values each; only the cost card has a firstFutureIndex (1 of its 3 bars).
    expect(screen.getAllByTestId('sparkline-bar')).toHaveLength(11);
    expect(screen.getAllByTestId('sparkline-placeholder-bar')).toHaveLength(1);
  });
});

describe('UsageCalendarPageView month grid', () => {
  it('draws the Sunday-first weekday header and six weeks of cells', () => {
    const container = document.body;
    renderView();
    const headers = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((header) => screen.getByText(header));
    // Sunday leads: each header follows the previous one in document order.
    headers.slice(1).forEach((header, index) => {
      expect(headers[index].compareDocumentPosition(header) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });
    expect(container.querySelectorAll('[data-date]')).toHaveLength(42);
    // September 2026 opens on a Tuesday, so the first cell is the Sunday before it.
    expect(container.querySelectorAll('[data-date]')[0]).toHaveAttribute('data-date', '2026-08-30');
  });

  it('shows cost, tokens and skill runs on an active day and opens it on click', async () => {
    const user = userEvent.setup();
    const props = renderView();
    const cell = screen.getByRole('button', { name: /September 1, 2026/ });
    expect(within(cell).getByText('$73')).toBeInTheDocument();
    expect(within(cell).getByText('369.4K')).toBeInTheDocument();
    expect(within(cell).getByText('5')).toBeInTheDocument();
    expect(within(cell).getByText('skill runs')).toBeInTheDocument();
    await user.click(cell);
    expect(props.onSelectDay).toHaveBeenCalledWith('2026-09-01');
  });

  it('says "No activity" for a day with an all-zero row, and still opens it', async () => {
    const user = userEvent.setup();
    const props = renderView();
    const cell = screen.getByRole('button', { name: 'September 2, 2026: no activity' });
    expect(within(cell).getByText('No activity')).toBeInTheDocument();
    await user.click(cell);
    expect(props.onSelectDay).toHaveBeenCalledWith('2026-09-02');
  });

  it('marks today and leaves future days and neighbouring-month days inert', () => {
    renderView();
    expect(screen.getByText('Today')).toBeInTheDocument();
    // Sep 21 is after today: a dash, and not a button.
    expect(screen.queryByRole('button', { name: /September 21, 2026/ })).not.toBeInTheDocument();
    // Aug 31 (the grid's leading day) belongs to another month.
    expect(screen.queryByRole('button', { name: /August 31, 2026/ })).not.toBeInTheDocument();
  });

  it('is inert while the rollup is loading', () => {
    renderView({ isLoading: true, daysByDateKey: new Map() });
    expect(screen.queryByRole('button', { name: /September 1, 2026/ })).not.toBeInTheDocument();
  });

  it('is inert and blank when the rollup failed, rather than claiming "No activity"', () => {
    renderView({ error: new Error('boom'), daysByDateKey: new Map() });
    expect(screen.getByRole('alert')).toHaveTextContent('boom');
    expect(screen.queryByRole('button', { name: /September 1, 2026/ })).not.toBeInTheDocument();
    expect(screen.queryByText('No activity')).not.toBeInTheDocument();
  });

  it('tints a heated cell with a color-mix of the Color by color', () => {
    const container = document.body;
    renderView();
    const heated = container.querySelector('[data-date="2026-09-01"]') as HTMLElement;
    // jsdom drops a color-mix() it cannot parse, so assert on the emotion class instead of the value
    // by comparing against an un-heated sibling: their generated classes must differ.
    const plain = container.querySelector('[data-date="2026-09-02"]') as HTMLElement;
    expect(heated.className).not.toEqual(plain.className);
  });
});

describe('UsageCalendarPageView week row', () => {
  const weekProps = (
    overrides: Partial<UsageCalendarDay> = {},
  ): Partial<UsageCalendarPageViewProps> => ({
    view: 'week',
    today: WEEK_END_TODAY,
    gridDates: periodRange('week', WEEK_END_TODAY).days,
    periodLabel: 'Sep 13 – 19, 2026',
    daysByDateKey: indexDaysByDateKey([
      makeDay('2026-09-16', {
        costUsd: 12.5,
        tokens: 50_000,
        skillCalls: 2,
        sessions: 1,
        activeSeconds: 2_700,
        ...overrides,
      }),
    ]),
    heatPercents: new Map(),
  });

  it('draws seven cells and adds sessions and active time to an active day', () => {
    renderView(weekProps());
    expect(document.body.querySelectorAll('[data-date]')).toHaveLength(7);
    const cell = screen.getByRole('button', { name: /September 16, 2026/ });
    expect(within(cell).getByText('$13')).toBeInTheDocument();
    expect(cell).toHaveTextContent('1 session');
    expect(cell).toHaveTextContent('45m active');
  });

  it('starts the week on Sunday', () => {
    renderView(weekProps());
    const dateKeys = Array.from(document.body.querySelectorAll('[data-date]')).map((cell) =>
      cell.getAttribute('data-date'),
    );
    expect(dateKeys[0]).toBe('2026-09-13');
    expect(dateKeys[6]).toBe('2026-09-19');
    const firstCell = document.body.querySelector('[data-date="2026-09-13"]') as HTMLElement;
    expect(within(firstCell).getByText('Sun')).toBeInTheDocument();
  });

  it('marks today with a tag and no future cell in this week is interactive', () => {
    renderView(weekProps());
    expect(screen.getByText('Today')).toBeInTheDocument();
    // Today is the week's last day, so nothing after it exists here; every day is a button.
    expect(screen.getAllByRole('button', { name: /September (13|14|15|16|17|18|19), 2026/ })).toHaveLength(7);
  });

  it('splits the day\'s spend by model in a bar, widest first', () => {
    renderView(
      weekProps({
        costByModel: [
          { model: 'claude-opus-4', costUsd: 7.5 },
          { model: 'claude-sonnet-4', costUsd: 5 },
        ],
      }),
    );
    const cell = screen.getByRole('button', { name: /September 16, 2026/ });
    expect(within(cell).getByRole('img', { name: 'Spend by model: Opus 4 60%, Sonnet 4 40%' })).toBeInTheDocument();
    expect(within(cell).getByText('Spend by model')).toBeInTheDocument();
  });

  it('draws no model bar on a day with no model-attributed spend', () => {
    renderView(weekProps());
    expect(screen.queryByRole('img', { name: /Spend by model/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Spend by model')).not.toBeInTheDocument();
  });

  it('draws no model bar in the month view, whatever the day carries', () => {
    renderView({
      daysByDateKey: indexDaysByDateKey([
        makeDay('2026-09-01', { costUsd: 5, costByModel: [{ model: 'claude-opus-4', costUsd: 5 }] }),
      ]),
    });
    expect(screen.queryByRole('img', { name: /Spend by model/ })).not.toBeInTheDocument();
  });

  it('shows commit and pull-request badges only when non-zero', () => {
    renderView(weekProps({ commits: 3, pullRequests: 1 }));
    const cell = screen.getByRole('button', { name: /September 16, 2026/ });
    expect(within(cell).getByText('3 commits')).toBeInTheDocument();
    expect(within(cell).getByText('1 PR')).toBeInTheDocument();
  });

  it('singularizes one commit and omits a zero badge', () => {
    renderView(weekProps({ commits: 1, pullRequests: 0 }));
    const cell = screen.getByRole('button', { name: /September 16, 2026/ });
    expect(within(cell).getByText('1 commit')).toBeInTheDocument();
    expect(within(cell).queryByText(/PR/)).not.toBeInTheDocument();
  });

  it('shows no badges when the day has neither commits nor pull requests', () => {
    renderView(weekProps());
    const cell = screen.getByRole('button', { name: /September 16, 2026/ });
    expect(within(cell).queryByText(/commit/)).not.toBeInTheDocument();
    expect(within(cell).queryByText(/PR/)).not.toBeInTheDocument();
  });
});

describe('UsageCalendarPageView day drawer', () => {
  const drawerProps = (): Partial<UsageCalendarPageViewProps> => ({
    selectedDateKey: '2026-09-01',
    isDrawerOpen: true,
    modelRows: [
      { label: 'Sonnet 4', value: '$40.10 · 200K', percentage: 54, colorIndex: 0 },
      { label: 'Opus 4', value: '$33.10 · 169K', percentage: 46, colorIndex: 1 },
    ],
    skillUsage,
    subagentUsage,
  });

  it('shows the day title, headline stats, all-metrics totals and both invocation lists', () => {
    renderView(drawerProps());
    const drawer = screen.getByRole('presentation');
    expect(within(drawer).getByText('Tuesday, September 1, 2026')).toBeInTheDocument();
    expect(within(drawer).getByText('Day → Detail')).toBeInTheDocument();
    // Headline stats.
    expect(within(drawer).getByText('2h 11m')).toBeInTheDocument();
    expect(within(drawer).getByText('Agent runs')).toBeInTheDocument();
    // All-metrics totals.
    expect(within(drawer).getByText('+284')).toBeInTheDocument();
    expect(within(drawer).getByText('-99')).toBeInTheDocument();
    expect(within(drawer).getByText('11 accepted')).toBeInTheDocument();
    expect(within(drawer).getByText('1 rejected')).toBeInTheDocument();
    // Model split.
    expect(within(drawer).getByText('Sonnet 4')).toBeInTheDocument();
    // Skills and subagents are separate sections, each with its own rows.
    expect(within(drawer).getByText('Skills invoked')).toBeInTheDocument();
    expect(within(drawer).getByText('Subagents invoked')).toBeInTheDocument();
    expect(within(drawer).getByText('code-reviewer')).toBeInTheDocument();
    expect(within(drawer).getByText('3 runs')).toBeInTheDocument();
    expect(within(drawer).getByText('1 run')).toBeInTheDocument();
    expect(within(drawer).getByText('Explore')).toBeInTheDocument();
  });

  it('says so when the day has no activity, and shows none of the sections', () => {
    renderView({ ...drawerProps(), selectedDateKey: '2026-09-02' });
    const drawer = screen.getByRole('presentation');
    expect(within(drawer).getByText(/No usage recorded for this day/)).toBeInTheDocument();
    expect(within(drawer).queryByText('Skills invoked')).not.toBeInTheDocument();
  });

  it('shows loading and empty states for the detail lists', () => {
    renderView({ ...drawerProps(), modelRows: [], skillUsage: [], subagentUsage: [], isDetailLoading: true });
    expect(within(screen.getByRole('presentation')).getAllByText('Loading…')).toHaveLength(3);
  });

  it('shows the empty copy once loading finishes with nothing to list', () => {
    renderView({ ...drawerProps(), modelRows: [], skillUsage: [], subagentUsage: [] });
    const drawer = screen.getByRole('presentation');
    expect(within(drawer).getByText('No skills were invoked this day.')).toBeInTheDocument();
    expect(within(drawer).getByText('No subagents were dispatched this day.')).toBeInTheDocument();
    expect(within(drawer).getByText('No model usage recorded for this day.')).toBeInTheDocument();
  });

  it('closes and hands off to the Metrics Explorer through its two controls', async () => {
    const user = userEvent.setup();
    const props = renderView(drawerProps());
    await user.click(screen.getByRole('button', { name: 'Open in Metrics Explorer' }));
    expect(props.onOpenInMetrics).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Close day detail' }));
    expect(props.onCloseDrawer).toHaveBeenCalledTimes(1);
  });

  it('renders no drawer content while nothing is selected', () => {
    renderView({ selectedDateKey: null, isDrawerOpen: false });
    expect(screen.queryByText('Day → Detail')).not.toBeInTheDocument();
  });
});
