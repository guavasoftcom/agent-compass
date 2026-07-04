// Logs page — pure derivations: LogsFilters → query-string serialization, and the
// row-severity / event-name / tool-name helpers the presentation components use.
//
// severityOf is the important one: real telemetry has NULL severityText/
// severityNumber on every row, so severity is DERIVED from event semantics. This
// mirrors the backend SQL `derive_log_severity` exactly — keep the two in lockstep.

import type { LogRow } from '../../api';
import type { LogsFilters, Severity } from './logsTypes';

// serialize filters → query string (the real fetchers use this verbatim)
export const buildLogsQuery = (f: LogsFilters): URLSearchParams => {
  const p = new URLSearchParams();
  p.set('startTimestamp', f.startTimestamp);
  p.set('endTimestamp', f.endTimestamp);
  (f.filter ?? []).forEach((v) => p.append('filter', v));
  (f.severity ?? []).forEach((v) => p.append('severity', v));
  (f.event ?? []).forEach((v) => p.append('event', v));
  (f.tool ?? []).forEach((v) => p.append('tool', v));
  if (f.q) {
    p.set('q', f.q);
  }
  return p;
};

const ERROR_EVENTS = ['api_error', 'internal_error', 'api_retries_exhausted'];
const DEBUG_EVENTS = ['hook_execution_start', 'hook_execution_complete', 'hook_registered', 'api_request_body', 'api_response_body'];

export const severityOf = (r: LogRow): Severity => {
  // 1. explicit severityText wins
  const t = (r.severityText ?? '').toUpperCase();
  if (t === 'ERROR' || t === 'WARN' || t === 'INFO' || t === 'DEBUG') {
    return t;
  }
  // 2. numeric code, ONLY when present and > 0
  const n = r.severityNumber;
  if (typeof n === 'number' && n > 0) {
    return n >= 17 ? 'ERROR' : n >= 13 ? 'WARN' : n >= 9 ? 'INFO' : 'DEBUG';
  }
  // 3. derive from attributes (the common path on real data)
  const a = r.attributes ?? {};
  const event = a['event.name'] as string | undefined;
  const decision = a['decision'] as string | undefined;
  const nbe = a['num_non_blocking_error'];
  if (
    (event != null && ERROR_EVENTS.includes(event))
    || 'error' in a || 'error_type' in a || 'error_name' in a || 'error_code' in a
    || String(a['success']) === 'false'
    || decision === 'reject'
  ) {
    return 'ERROR';
  }
  if (event === 'compaction' || a['status'] === 'disconnected' || (nbe != null && String(nbe) !== '0')) {
    return 'WARN';
  }
  if ((event != null && DEBUG_EVENTS.includes(event)) || (event === 'tool_decision' && decision === 'accept')) {
    return 'DEBUG';
  }
  return 'INFO';
};
// OTLP kvlist/array attribute values arrive in the browser as untyped JSON, and
// unauthenticated ingest means an attacker can send an object/array where a
// string is expected. Guard with typeof at runtime — a bare `as string` cast is
// compile-time only and lets an object reach `<Tag>{event}</Tag>` in LogStream,
// which throws "Objects are not valid as a React child" and (with no
// ErrorBoundary above it) unmounts the whole page.
export const eventNameOf = (r: LogRow): string | null => {
  const value = r.attributes?.['event.name'];
  return typeof value === 'string' ? value : null;
};
export const toolNameOf = (r: LogRow): string | null => {
  const value = r.attributes?.['tool_name'];
  return typeof value === 'string' ? value : null;
};
