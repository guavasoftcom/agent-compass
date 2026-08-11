import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Box, Drawer, Tooltip, alpha, useTheme } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import type { SessionPromptRow, SessionSummaryRow } from '../../../../api';
import {
  cacheEfficiencyBand,
  cacheEfficiencyRatio,
  formatCacheEfficiency,
} from '../../../../lib/cacheEfficiency';
// Cross-page import, deliberately: the band→color mapping is shared with the
// grid's Cache eff. column and the Tokens page — see cacheEfficiencyBandColors.ts.
import { cacheEfficiencyBandColor } from '../../../TokensPage/components/cacheEfficiencyBandColors';
import { neutralColors } from '../../../../theme/colors';
import { fontFamilies } from '../../../../theme/typography';
import { backdropGradient, radii } from '../../../../theme/theme';
import PromptTimelinePanel, { TokenBreakdownTooltip } from '../PromptTimelinePanel';
import {
  USD_FORMATTER,
  formatRelativeTime,
  formatShortTimestamp,
  formatTimestamp,
  formatTokens,
} from '../sessionsFormat';

// Slide timing from the design handoff — noticeably faster than MUI's default
// drawer easing, so the panel lands before the eye leaves the clicked row.
const SLIDE_DURATION_MS = 260;
const SLIDE_EASING = 'cubic-bezier(.22,.8,.24,1)';

interface SessionDetailDrawerProps {
  // Null closes the drawer. The last non-null session stays rendered through
  // the slide-out so the panel doesn't blank mid-animation.
  session: SessionSummaryRow | null;
  onClose: () => void;
  prompts: SessionPromptRow[] | null;
  promptsLoading: boolean;
  promptsError: Error | null;
  // Active dashboard window (epoch ms), forwarded to the timeline so it can dim
  // turns outside the selected window (the prompts endpoint returns the whole
  // session, not just the windowed slice).
  windowStartMs?: number;
  windowEndMs?: number;
}

// One "·"-separated fact on the header's metadata line. The separator is drawn
// by the parent's `& > span::after` rule so the last item never trails one.
const MetaItem = ({
  children,
  title,
}: {
  children: ReactNode;
  title?: string;
}) => {
  const item = (
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
      {children}
    </Box>
  );
  return title ? (
    <Tooltip title={title} placement="top" arrow>
      {item}
    </Tooltip>
  ) : (
    item
  );
};

// Header identity + the four facts the grid row showed, so the drawer stands on
// its own once the table scrolls behind it: start, whole-session cost, last
// activity, and the window-scoped token total with its cache efficiency.
const SessionDetailHeader = ({
  session,
  onClose,
}: {
  session: SessionSummaryRow;
  onClose: () => void;
}) => {
  const theme = useTheme();
  const ratio = cacheEfficiencyRatio(session.tokenBreakdown);
  const efficiencyColor = cacheEfficiencyBandColor(cacheEfficiencyBand(ratio), theme);

  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'space-between',
        gap: 1.75,
        px: 2.75,
        py: 2,
        flexShrink: 0,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        <Box
          sx={{
            fontFamily: fontFamilies.mono,
            fontSize: 12,
            color: 'text.secondary',
            wordBreak: 'break-all',
          }}
        >
          Session{' '}
          <Box component="b" sx={{ color: 'text.primary', fontWeight: 600 }}>
            {session.sessionId}
          </Box>
        </Box>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            flexWrap: 'wrap',
            gap: 1,
            fontSize: 11.5,
            color: 'text.disabled',
            '& > span::after': {
              content: '"\\00b7"',
              ml: 1,
              color: (t) => t.palette.divider,
            },
            '& > span:last-of-type::after': { content: '""', ml: 0 },
          }}
        >
          <MetaItem>{formatShortTimestamp(session.startTimestamp)}</MetaItem>
          <MetaItem>{USD_FORMATTER.format(session.costUsd)}</MetaItem>
          <MetaItem title={formatTimestamp(session.endTimestamp)}>
            {`active ${formatRelativeTime(session.endTimestamp)}`}
          </MetaItem>
          <MetaItem>
            <TokenBreakdownTooltip tokens={session.tokenBreakdown}>
              <Box
                component="span"
                sx={{
                  cursor: 'help',
                  borderBottom: (t) => `1px dotted ${t.palette.text.disabled}`,
                  pb: '1px',
                }}
              >
                {`${formatTokens(session.tokens)} tok`}
              </Box>
            </TokenBreakdownTooltip>
            <Box component="span" sx={{ color: (t) => t.palette.divider }}>
              ·
            </Box>
            <Box component="span" sx={{ color: efficiencyColor, fontWeight: 600 }}>
              {formatCacheEfficiency(ratio)}
            </Box>
            cache
          </MetaItem>
        </Box>
      </Box>
      <Box
        component="button"
        type="button"
        onClick={onClose}
        aria-label="Close session detail"
        sx={{
          display: 'grid',
          placeItems: 'center',
          width: 32,
          height: 32,
          flexShrink: 0,
          border: 1,
          borderColor: 'divider',
          borderRadius: radii.xs,
          bgcolor: 'background.paper',
          color: 'text.secondary',
          cursor: 'pointer',
          '&:hover': {
            color: 'primary.main',
            borderColor: (t) => alpha(t.palette.primary.main, 0.32),
          },
        }}
      >
        <CloseIcon sx={{ fontSize: 18 }} />
      </Box>
    </Box>
  );
};

/**
 * Right-side session detail drawer: the session's whole prompt timeline over a
 * scrim, opened by clicking a row in the sessions grid.
 *
 * It replaced an inline row expansion. The point of the swap is that the table
 * underneath keeps its sort, page, and scroll position while sessions are
 * inspected one after another — an expansion row pushed everything below it
 * down and re-flowed the grid on every open.
 */
const SessionDetailDrawer = ({
  session,
  onClose,
  prompts,
  promptsLoading,
  promptsError,
  windowStartMs,
  windowEndMs,
}: SessionDetailDrawerProps) => {
  const open = session != null;
  const bodyRef = useRef<HTMLDivElement | null>(null);

  // Keep the last session rendered while the drawer slides closed, so the
  // content leaves with the panel instead of vanishing the instant the row is
  // deselected. Guarded render-phase state adjustment (React's documented
  // pattern for previous-render information), compared by session id rather
  // than object identity: the parent resolves a fresh row object out of the
  // query result on every render.
  const [lastSession, setLastSession] = useState<SessionSummaryRow | null>(null);
  if (session != null && session.sessionId !== lastSession?.sessionId) {
    setLastSession(session);
  }
  const rendered = session ?? lastSession;

  // Open on the most recent turn: a long session's interesting end is the
  // bottom of the timeline, and scrolling there by hand on every open gets old.
  const scrollToLatestTurn = () => {
    const body = bodyRef.current;
    if (body != null) {
      body.scrollTop = body.scrollHeight;
    }
  };

  // Two triggers, because either one alone misses a case: the timeline usually
  // resolves after the drawer is already open (this effect catches it), but a
  // session opened a second time has its prompts cached and renders them during
  // the entering slide, when the panel isn't laid out yet and scrollTop won't
  // take — the transition's onEntered below covers that one.
  useEffect(() => {
    if (!open) {
      return;
    }
    scrollToLatestTurn();
  }, [open, prompts]);

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      transitionDuration={SLIDE_DURATION_MS}
      slotProps={{
        transition: {
          easing: SLIDE_EASING,
          onEntered: scrollToLatestTurn,
          // The closed session is only dropped once the panel is fully gone.
          onExited: () => setLastSession(null),
        },
        backdrop: {
          sx: {
            bgcolor: (t) => alpha(neutralColors.shadowDeep, t.palette.mode === 'dark' ? 0.6 : 0.45),
          },
        },
        paper: {
          sx: {
            width: 560,
            maxWidth: '92vw',
            display: 'flex',
            flexDirection: 'column',
            borderRadius: 0,
            borderLeft: 1,
            borderColor: 'divider',
            bgcolor: 'background.default',
            // The page's aurora glow is painted on <body> and fixed, so a panel
            // stacked above it would otherwise read as a flat gray slab.
            backgroundImage: (t) => backdropGradient(t.palette.mode),
            backgroundRepeat: 'no-repeat',
            boxShadow: (t) =>
              `-28px 0 60px ${alpha(
                t.palette.mode === 'dark' ? neutralColors.black : neutralColors.shadowIndigo,
                t.palette.mode === 'dark' ? 0.5 : 0.3,
              )}`,
          },
        },
      }}
    >
      {rendered ? (
        <>
          <SessionDetailHeader session={rendered} onClose={onClose} />
          <Box ref={bodyRef} sx={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
            <PromptTimelinePanel
              prompts={prompts}
              loading={promptsLoading}
              error={promptsError}
              windowStartMs={windowStartMs}
              windowEndMs={windowEndMs}
            />
          </Box>
        </>
      ) : null}
    </Drawer>
  );
};

export default SessionDetailDrawer;
