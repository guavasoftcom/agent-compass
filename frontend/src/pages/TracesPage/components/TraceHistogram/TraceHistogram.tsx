import { useTracesExplorerContext } from '../../TracesExplorerContext';
import TraceHistogramView from './TraceHistogramView';

// (window-wide p95 is supplied by the histogram endpoint)
const TraceHistogram = () => {
  const {
    histogramData,
    hiddenHistogramSeries,
    zoom,
    windowLabel: windowLabelForRange,
    toggleHistogramSeries,
    zoomToBucket,
  } = useTracesExplorerContext();
  const windowLabel = zoom ? zoom.label : windowLabelForRange;

  return (
    <TraceHistogramView
      data={histogramData}
      hiddenSeries={hiddenHistogramSeries}
      windowLabel={windowLabel}
      onToggleSeries={toggleHistogramSeries}
      onBarClick={zoomToBucket}
    />
  );
};

export default TraceHistogram;
