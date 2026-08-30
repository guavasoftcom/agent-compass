import { useState } from 'react';
import {
  Box,
  Button,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Stack,
} from '@mui/material';
import { useTheme } from '@mui/material/styles';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import CloseIcon from '@mui/icons-material/Close';
import { Link as RouterLink } from 'react-router-dom';
import type { CostCategory, CostSessionShare } from '../../../../api';
import KpiTile from '../../../../components/KpiTile';
import SegmentedBar from '../../../../components/SegmentedBar';
import { USD_FORMATTER } from '../../../../lib/format';
import { radii } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import { sessionsDeepLink } from '../../../SessionsPage/SessionsPage';
import {
  CATEGORY_LABELS,
  CATEGORY_ORDER,
  categoryColor,
} from '../../costDerivations';

export interface SessionCostDialogProps {
  /** The selected session, or null when the dialog is closed. */
  session: CostSessionShare | null;
  /** `CostBreakdown.totalCostUsd` for the window — the share-of-spend denominator. */
  totalCostUsd: number;
  onClose: () => void;
}

const CATEGORY_TO_SESSION_FIELD: Record<CostCategory, keyof CostSessionShare> =
  {
    MAIN_LOOP: 'mainLoopCostUsd',
    SUBAGENT: 'subagentCostUsd',
    SKILL: 'skillCostUsd',
    AUXILIARY: 'auxiliaryCostUsd',
  };

interface DialogBodyProps {
  session: CostSessionShare;
  totalCostUsd: number;
  onClose: () => void;
}

/**
 * The dialog's content, split out so it only ever renders against a real,
 * non-null session — no fabricated `session?.` fallbacks. `SessionCostDialog`
 * renders this against `session ?? lastSession` so the body keeps showing the
 * last selection while the Dialog's exit transition runs (see the `lastSession`
 * note on the outer component — same idiom as the Tokens page's
 * `SessionCacheEfficiencyDialog`).
 */
const DialogBody = ({ session, totalCostUsd, onClose }: DialogBodyProps) => {
  const theme = useTheme();
  const shareOfSpend =
    totalCostUsd === 0 ? 0 : (session.costUsd / totalCostUsd) * 100;

  // Same four categories, same colors, as the money map and trend chart elsewhere
  // on this page — CATEGORY_ORDER/categoryColor from costDerivations.ts, not a
  // page-local palette, so a category never wears two colors on the Cost page.
  const segments = CATEGORY_ORDER.map((category) => ({
    label: CATEGORY_LABELS[category],
    value: session[CATEGORY_TO_SESSION_FIELD[category]] as number,
    color: categoryColor(category),
  }));

  return (
    <>
      <DialogTitle sx={{ pr: 6, pb: 2 }}>
        <Box
          sx={{
            mt: 0.5,
            fontFamily: fontFamilies.mono,
            fontSize: 12,
            color: 'text.secondary',
            wordBreak: 'break-all',
          }}
        >
          {session.sessionId}
        </Box>
        <IconButton
          aria-label="close"
          size="small"
          onClick={onClose}
          sx={{ position: 'absolute', right: 12, top: 12 }}
        >
          <CloseIcon fontSize="small" />
        </IconButton>
      </DialogTitle>

      <DialogContent dividers>
        <Stack spacing={2}>
          <Box
            sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.5 }}
          >
            <KpiTile
              label="Cost"
              value={USD_FORMATTER.format(session.costUsd)}
            />
            <KpiTile
              label="Share of spend"
              value={`${shareOfSpend.toFixed(1)}%`}
            />
          </Box>

          <SegmentedBar
            segments={segments}
            formatValue={(value) => USD_FORMATTER.format(value)}
            trackColor={
              theme.custom?.progressTrack ?? theme.palette.action.hover
            }
            hideZeroSegmentsInLegend
          />

          {/* Deep link, not a plain /sessions link: the Sessions page reads
              ?sessionId= and opens that row's prompt timeline on arrival. */}
          <Button
            component={RouterLink}
            to={sessionsDeepLink(session.sessionId)}
            variant="outlined"
            color="inherit"
            fullWidth
            endIcon={<ArrowForwardIcon />}
            sx={{
              height: 40,
              borderRadius: radii.sm,
              fontFamily: fontFamilies.display,
            }}
          >
            View in Sessions
          </Button>
        </Stack>
      </DialogContent>
    </>
  );
};

/**
 * One session's cost picture, opened from a row of the "Most expensive sessions"
 * ranking: its cost, its share of the window's total spend, and the four-way
 * work-category split of that spend (Main loop / Subagents / Skills / Auxiliary),
 * using the same colors as the money map and trend chart elsewhere on this page.
 *
 * Everything shown comes from the row the caller already has
 * (`CostSessionShare` carries all four category fields, guaranteed to sum to
 * `costUsd`) — opening this costs no fetch.
 */
const SessionCostDialog = ({
  session,
  totalCostUsd,
  onClose,
}: SessionCostDialogProps) => {
  // Keep the last selection rendered while the Dialog's exit transition runs, so
  // the body doesn't flash to an empty state during the ~200ms fade-out — same
  // idiom as SessionCacheEfficiencyDialog's lastRow / SpanInspectorDrawer.
  // Compared by session id, not object identity.
  const [lastSession, setLastSession] = useState<CostSessionShare | null>(null);
  if (session != null && session.sessionId !== lastSession?.sessionId) {
    setLastSession(session);
  }
  const effectiveSession = session ?? lastSession;

  return (
    <Dialog open={session != null} onClose={onClose} maxWidth="xs" fullWidth>
      {effectiveSession != null ? (
        <DialogBody
          session={effectiveSession}
          totalCostUsd={totalCostUsd}
          onClose={onClose}
        />
      ) : null}
    </Dialog>
  );
};

export default SessionCostDialog;
