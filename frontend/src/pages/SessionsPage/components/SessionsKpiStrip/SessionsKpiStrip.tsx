import { Box, Tooltip } from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import StatCard from '../../../../components/StatCard';
import LineSparkline from '../../../../components/LineSparkline';
import type { SessionsKpis } from '../../SessionsPageView';
import { USD_FORMATTER, USD_PER_MINUTE_FORMATTER } from '../sessionsFormat';

interface SessionsKpiStripProps {
  kpis: SessionsKpis;
}

const SessionsKpiStrip = ({ kpis }: SessionsKpiStripProps) => {
  // P95 ⇒ ~5% of sessions exceed it; surfaced as the card caption.
  const sessionsAboveP95 = Math.round(kpis.totalSessions * 0.05);

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)' },
        gap: 2,
      }}
    >
      <StatCard
        label="Total sessions"
        value={kpis.totalSessions.toLocaleString()}
      >
        <LineSparkline values={kpis.sessionsTrend} height={36} />
      </StatCard>
      <StatCard
        label="Median cost / session"
        value={USD_FORMATTER.format(kpis.medianCostUsd)}
        sub="half of sessions cost less"
        accent
      />
      <StatCard
        label={
          <Box
            component="span"
            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}
          >
            P95 cost / session
            <Tooltip
              title="95th percentile: 1 in 20 sessions cost more than this. Tracks the expensive tail rather than the typical session (the median)."
              placement="top"
              arrow
            >
              <InfoOutlinedIcon
                sx={{ fontSize: 15, color: 'text.disabled', cursor: 'help' }}
              />
            </Tooltip>
          </Box>
        }
        value={USD_FORMATTER.format(kpis.p95CostUsd)}
        sub={
          <>
            <Box
              component="span"
              sx={{ color: 'primary.main', fontWeight: 600 }}
            >
              {sessionsAboveP95}
            </Box>
            {' sessions above this'}
          </>
        }
      />
      <StatCard
        label={
          <Box
            component="span"
            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}
          >
            Median $/active min
            <Tooltip
              title="Burn rate: cost divided by active time, per minute. Independent of idle — surfaces sessions that spend more per minute of real work."
              placement="top"
              arrow
            >
              <InfoOutlinedIcon
                sx={{ fontSize: 15, color: 'text.disabled', cursor: 'help' }}
              />
            </Tooltip>
          </Box>
        }
        value={USD_PER_MINUTE_FORMATTER.format(
          kpis.medianCostPerActiveMinuteUsd,
        )}
        sub="cost per minute of work"
      />
    </Box>
  );
};

export default SessionsKpiStrip;
