import LiveTailToggle, { type LiveTailToggleProps } from '../../../../components/LiveTailToggle';

export interface TraceTailToggleProps {
  active: boolean;
  locked: boolean;
  tooltip?: string;
  onToggle: () => void;
}

// Thin page-scoped wrapper around the shared LiveTailToggle.
// Keeps the import surface stable for TracesPageView while delegating
// all styling and behaviour to the shared component.
const TraceTailToggle = (props: TraceTailToggleProps) => {
  const liveTailProps: LiveTailToggleProps = {
    active: props.active,
    locked: props.locked,
    tooltip: props.tooltip,
    onToggle: props.onToggle,
  };
  return <LiveTailToggle {...liveTailProps} />;
};

export default TraceTailToggle;
