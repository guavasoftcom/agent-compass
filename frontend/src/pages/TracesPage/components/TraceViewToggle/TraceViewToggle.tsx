import StreamTableToggle, { type StreamTableView } from '../../../../components/StreamTableToggle';

export type TraceView = StreamTableView;

export interface TraceViewToggleProps {
  view: TraceView;
  onViewChange: (view: TraceView) => void;
}

const TraceViewToggle = ({ view, onViewChange }: TraceViewToggleProps) => {
  return <StreamTableToggle value={view} onChange={onViewChange} />;
};

export default TraceViewToggle;
