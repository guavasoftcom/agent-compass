import { Box, Grid } from '@mui/material';
import StatCard from '../../../../components/StatCard';
import Sparkline from '../../../../components/Sparkline';

export interface StatsRowViewProps {
  total: number;
  distinctCount: number;
  topTool: string | null;
  topShare: number | null;
  slowestTool: string | null;
  slowestP95Ms: number | null;
  toolNames: string[];
  spark: number[];
}

const MS_PER_SECOND = 1000;

const formatLatency = (latencyMs: number): string => {
  if (latencyMs < MS_PER_SECOND) {
    return `${latencyMs.toFixed(0)} ms`;
  }
  return `${(latencyMs / MS_PER_SECOND).toFixed(2)} s`;
};

const Accent = ({ children }: { children: React.ReactNode }) => (
  <Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}>
    {children}
  </Box>
);

const StatsRowView = ({
  total,
  distinctCount,
  topTool,
  topShare,
  slowestTool,
  slowestP95Ms,
  toolNames,
  spark,
}: StatsRowViewProps) => {
  const toolList = toolNames.slice(0, 3).join(' · ') || '—';

  return (
    <Grid container spacing={2}>
      <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
        <StatCard
          label="Total invocations"
          value={total.toLocaleString()}
          sub={<>across <Accent>{distinctCount}</Accent> distinct tools</>}
        >
          {spark.length > 0 && <Sparkline values={spark} />}
        </StatCard>
      </Grid>
      <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
        <StatCard
          label="Top tool"
          value={topTool ?? '—'}
          accent
          sub={
            topShare != null ? (
              <>
                <Accent>{topShare.toFixed(1)}%</Accent> of all calls
              </>
            ) : undefined
          }
        />
      </Grid>
      <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
        <StatCard label="Distinct tools" value={distinctCount} sub={toolList} />
      </Grid>
      <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
        <StatCard
          label="Slowest p95"
          value={slowestP95Ms != null ? formatLatency(slowestP95Ms) : '—'}
          sub={slowestTool != null ? <>tool · <Accent>{slowestTool}</Accent></> : undefined}
        />
      </Grid>
    </Grid>
  );
};

export default StatsRowView;
