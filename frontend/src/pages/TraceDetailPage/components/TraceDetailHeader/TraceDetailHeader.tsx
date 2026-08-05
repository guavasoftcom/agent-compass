import type { SpanRow } from '../../../../api';
import { tokenBreakdownForSpan } from '../../../TracesPage/tokenBreakdown';
import TraceDetailHeaderView from './TraceDetailHeaderView';

interface Props {
  traceId: string;
  sessionId: string | null;
  spans: SpanRow[];
  rootName: string;
  earliestStartMs: number;
  totalMs: number;
  errorCount: number;
  // Aurora sync: TraceRow.firstUserPrompt (pending backend field) — see
  // TraceDetailHeaderView's doc comment.
  firstUserPrompt?: string | null;
}

// Container: aggregates the per-span summary data (unique services, total
// tokens) and hands plain values to the presentational view.
const TraceDetailHeader = ({
  traceId,
  sessionId,
  spans,
  rootName,
  earliestStartMs,
  totalMs,
  errorCount,
  firstUserPrompt,
}: Props) => {
  const serviceLabels = [...new Set(spans.map((s) => s.scopeName ?? '—'))].map(
    (scopeName) => scopeName.replace('claude_code.', ''),
  );
  const totalTokens = spans.reduce(
    (runningTotal, s) => runningTotal + tokenBreakdownForSpan(s).total,
    0,
  );

  return (
    <TraceDetailHeaderView
      traceId={traceId}
      sessionId={sessionId}
      rootName={rootName}
      earliestStartMs={earliestStartMs}
      totalMs={totalMs}
      errorCount={errorCount}
      spanCount={spans.length}
      serviceLabels={serviceLabels}
      totalTokens={totalTokens}
      firstUserPrompt={firstUserPrompt}
    />
  );
};

export default TraceDetailHeader;
