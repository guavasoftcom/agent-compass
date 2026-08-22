import { Box, useTheme } from '@mui/material';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlined';
import GhostButton from '../../../../components/GhostButton';
import { tokenFigureColor } from '../../../../theme/colors';
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
}

interface LegendKey {
  // Absent for `error`: it names the row's status (the red bar), not an
  // optional figure, so it's the one key that isn't a toggle.
  family?: ChipFamily;
  label: string;
  // Longer noun used in the toggle's title/aria text; falls back to `label`.
  toggleNoun?: string;
  color: string;
}

const WaterfallToolbar = ({
  anyCollapsed,
  canToggleAll,
  errorCount,
  onToggleAll,
  onNextError,
  chipsOff,
  onToggleChipFamily,
}: Props) => {
  const theme = useTheme();

  // One key per badge family that can appear on a row. `ok` (the bar's default
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
            return (
              <Box
                key={key.label}
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
      {errorCount ? (
        <GhostButton tone="danger" onClick={onNextError} sx={{ px: 1.5 }}>
          <ErrorOutlineIcon /> Next error
        </GhostButton>
      ) : null}
    </Box>
  );
};

export default WaterfallToolbar;
