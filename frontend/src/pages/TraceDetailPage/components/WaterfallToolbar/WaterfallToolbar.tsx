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
import { Box, useTheme } from '@mui/material';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlined';
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome';
import GhostButton from '../../../../components/GhostButton';
import { tokenFigureColor } from '../../../../theme/colors';
import type { AgentDispatchLegendEntry } from '../../agentDispatch';
import type { ChipFamily } from '../../chipVisibility';

interface Props {
  anyCollapsed: boolean;
  // False when nothing is collapsed and the trace has no tool-call span to
  // collapse — "Collapse all" would be a no-op, so it isn't rendered at all.
  canToggleAll: boolean;
  errorCount: number;
  onToggleAll: () => void;
  onNextError: () => void;
  // Badge families currently hidden from every span row.
  chipsOff: Set<ChipFamily>;
  onToggleChipFamily: (family: ChipFamily) => void;
  onAnalyzeTrace: () => void;
  // Whether a stored analysis exists for this trace, and whether it's stale —
  // drives the small dot on the Analyze trace button so a reader doesn't have
  // to open the dialog just to find out. Both come from the page's own
  // ['trace-analysis', traceId] query (see TraceDetailPage.tsx), which now
  // runs unconditionally rather than only while the dialog is mounted.
  hasAnalysis: boolean;
  analysisOutdated: boolean;
  // Effective Ollama `enabled` setting — hides "Analyze trace" entirely when
  // false, the same way "Collapse all" hides when canToggleAll is false.
  ollamaAnalysisEnabled: boolean;
  // Extra, non-toggling legend swatches for the trace's dispatched subagents (agentDispatch.ts),
  // one per distinct agent type — empty when the trace dispatched none, which renders nothing new.
  agentLegend?: AgentDispatchLegendEntry[];
  // Clicking an agent-dispatch legend swatch jumps the waterfall to one of that label's dispatch
  // spans, cycling through them on repeat clicks when a type was dispatched more than once. Absent
  // (or agentLegend absent/empty) means those swatches render inert, same as 'error' always has.
  onAgentLegendClick?: (label: string, dispatchSpanIds: string[]) => void;
}

interface LegendKey {
  // Absent for 'error' and for an agent-dispatch entry: both name a status/identity rather than
  // an optional figure, so neither is a toggle.
  family?: ChipFamily;
  label: string;
  // Longer noun used in the toggle's title/aria text; falls back to 'label'.
  toggleNoun?: string;
  color: string;
  // React list key. Falls back to `label` — safe for the six fixed keys, which never collide with
  // each other — but an agent-dispatch entry's label is a subagent_type string from live data, so
  // it's given an `agent:`-prefixed key instead to rule out a collision rather than trust one won't
  // happen.
  reactKey?: string;
  // Set only on agent-dispatch-derived entries — makes that swatch clickable (jump-to-dispatch);
  // 'error' and the five toggle keys never set this, so they keep their existing rendering exactly.
  onClick?: () => void;
  // Hover text for a clickable entry, naming the target and, when there is more than one dispatch
  // of that type, that the click cycles through them.
  title?: string;
}

const WaterfallToolbar = ({
  anyCollapsed,
  canToggleAll,
  errorCount,
  onToggleAll,
  onNextError,
  chipsOff,
  onToggleChipFamily,
  onAnalyzeTrace,
  hasAnalysis,
  analysisOutdated,
  ollamaAnalysisEnabled,
  agentLegend,
  onAgentLegendClick,
}: Props) => {
  const theme = useTheme();

  // One key per badge family that can appear on a row. 'ok' (the bar's default
  // color) named every span and carried no information, so it's gone — red
  // still reads as the exception against the default bar color without it.
  // The other five double as row-density controls: click one to hide that
  // badge on every span row (state lives in chipVisibility.ts, keyed by
  // family, and survives navigating between traces).
  const legendKeys: LegendKey[] = [
    { label: 'error', color: theme.palette.error.main },
    {
      family: 'tok',
      label: 'tokens',
      toggleNoun: 'full-rate token',
      color: tokenFigureColor(theme.palette.mode),
    },
    {
      family: 'cr',
      label: 'cache',
      toggleNoun: 'cache-read',
      color: theme.palette.text.disabled,
    },
    { family: 'cost', label: 'cost', color: theme.palette.warning.main },
    { family: 'mdl', label: 'model', color: theme.palette.primary.main },
    { family: 'tool', label: 'tool', color: theme.palette.info.main },
    // Extra, non-toggling swatches for the trace's dispatched subagents — one per distinct
    // agent type (agentDispatch.ts), rendered through the same !key.family branch as 'error'
    // below. Nothing is added when the trace dispatched no subagent.
    ...(agentLegend ?? []).map((entry) => ({
      label: entry.label,
      color: entry.color,
      reactKey: `agent:${entry.label}`,
      title:
        entry.dispatchSpanIds.length > 1
          ? `Jump to ${entry.label} — cycles through ${entry.dispatchSpanIds.length} dispatches`
          : `Jump to ${entry.label} dispatch`,
      onClick: () => onAgentLegendClick?.(entry.label, entry.dispatchSpanIds),
    })),
  ];

  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        px: 2,
        py: 1.1,
        borderBottom: 1,
        borderColor: 'divider',
        flexShrink: 0,
        flexWrap: 'wrap',
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1.1,
          typography: 'eyebrow',
          color: 'text.secondary',
        }}
      >
        <Box
          component="svg"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={2}
          sx={{ width: 15, height: 15 }}
        >
          <path d="M3 6h13M3 12h18M3 18h9" />
        </Box>
        Span waterfall
      </Box>
      <Box
        sx={{
          ml: 'auto',
          display: 'flex',
          alignItems: 'center',
          gap: 1.1,
          fontSize: 11,
          color: 'text.secondary',
          flexWrap: 'wrap',
        }}
      >
        {legendKeys.map((key) => {
          if (!key.family) {
            if (key.onClick) {
              const activate = key.onClick;
              return (
                <Box
                  key={key.reactKey ?? key.label}
                  component="span"
                  role="button"
                  tabIndex={0}
                  title={key.title}
                  onClick={activate}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      activate();
                    }
                  }}
                  sx={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 0.6,
                    cursor: 'pointer',
                    userSelect: 'none',
                    borderRadius: '6px',
                    px: 0.75,
                    py: 0.25,
                    mx: -0.75,
                    my: -0.25,
                    transition: 'background .12s',
                    '&:hover': { bgcolor: 'action.hover' },
                    '&:focus-visible': {
                      outline: (t) => `2px solid ${t.palette.primary.main}`,
                      outlineOffset: '1px',
                    },
                  }}
                >
                  <Box
                    sx={{
                      width: 9,
                      height: 9,
                      borderRadius: '3px',
                      bgcolor: key.color,
                      flexShrink: 0,
                    }}
                  />
                  {key.label}
                </Box>
              );
            }
            return (
              <Box
                key={key.reactKey ?? key.label}
                component="span"
                sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.6 }}
              >
                <Box
                  sx={{
                    width: 9,
                    height: 9,
                    borderRadius: '3px',
                    bgcolor: key.color,
                    flexShrink: 0,
                  }}
                />
                {key.label}
              </Box>
            );
          }
          const family = key.family;
          const isOff = chipsOff.has(family);
          const toggle = () => onToggleChipFamily(family);
          return (
            <Box
              key={family}
              component="span"
              role="button"
              tabIndex={0}
              aria-pressed={!isOff}
              title={`${isOff ? 'Show' : 'Hide'} ${key.toggleNoun ?? key.label} badges on span rows`}
              onClick={toggle}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  toggle();
                }
              }}
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.6,
                cursor: 'pointer',
                userSelect: 'none',
                borderRadius: '6px',
                px: 0.75,
                py: 0.25,
                mx: -0.75,
                my: -0.25,
                opacity: isOff ? 0.45 : 1,
                transition: 'background .12s, opacity .12s',
                '&:hover': { bgcolor: 'action.hover' },
                '&:focus-visible': {
                  outline: (t) => `2px solid ${t.palette.primary.main}`,
                  outlineOffset: '1px',
                },
              }}
            >
              <Box
                sx={{
                  width: 9,
                  height: 9,
                  borderRadius: '3px',
                  flexShrink: 0,
                  bgcolor: isOff ? 'transparent' : key.color,
                  boxShadow: isOff ? 'inset 0 0 0 1.5px currentColor' : 'none',
                }}
              />
              {key.label}
            </Box>
          );
        })}
      </Box>
      {canToggleAll ? (
        <GhostButton onClick={onToggleAll}>
          {anyCollapsed ? 'Expand all' : 'Collapse all'}
        </GhostButton>
      ) : null}
      {ollamaAnalysisEnabled ? (
        <GhostButton onClick={onAnalyzeTrace} sx={{ px: 1.5 }}>
          <AutoAwesomeIcon /> Analyze trace
          {hasAnalysis ? (
            <Box
              component="span"
              title={
                analysisOutdated
                  ? 'Analysis may be outdated — new activity since it was generated'
                  : 'Analysis available'
              }
              sx={{
                width: 6,
                height: 6,
                borderRadius: '50%',
                ml: 0.3,
                bgcolor: analysisOutdated ? 'warning.main' : 'primary.main',
                flexShrink: 0,
              }}
            />
          ) : null}
        </GhostButton>
      ) : null}
      {errorCount ? (
        <GhostButton tone="danger" onClick={onNextError} sx={{ px: 1.5 }}>
          <ErrorOutlineIcon /> Next error
        </GhostButton>
      ) : null}
    </Box>
  );
};

export default WaterfallToolbar;
