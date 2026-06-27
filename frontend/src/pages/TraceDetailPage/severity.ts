const SEVERITY_TRACE_MAX = 4;
const SEVERITY_DEBUG_MAX = 8;
const SEVERITY_INFO_MAX = 12;
const SEVERITY_WARN_MAX = 16;
const SEVERITY_ERROR_MAX = 20;

export const severityLabel = (severityNumber: number | null): string => {
  if (severityNumber == null) {
    return 'UNSET';
  }
  if (severityNumber <= SEVERITY_TRACE_MAX) {
    return 'TRACE';
  }
  if (severityNumber <= SEVERITY_DEBUG_MAX) {
    return 'DEBUG';
  }
  if (severityNumber <= SEVERITY_INFO_MAX) {
    return 'INFO';
  }
  if (severityNumber <= SEVERITY_WARN_MAX) {
    return 'WARN';
  }
  if (severityNumber <= SEVERITY_ERROR_MAX) {
    return 'ERROR';
  }
  return 'FATAL';
};

export const severityColor = (
  severityNumber: number | null,
): 'default' | 'info' | 'warning' | 'error' => {
  if (severityNumber == null) {
    return 'default';
  }
  if (severityNumber <= SEVERITY_DEBUG_MAX) {
    return 'default';
  }
  if (severityNumber <= SEVERITY_INFO_MAX) {
    return 'info';
  }
  if (severityNumber <= SEVERITY_WARN_MAX) {
    return 'warning';
  }
  return 'error';
};
