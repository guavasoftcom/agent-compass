// Logs page — a 30-day synthetic OTel agent-log store + an in-memory query engine
// that emulates the three /api/logs/* endpoints. Set VITE_LOGS_SAMPLE=1 to force
// it (offline UI work); the return shapes are identical to the live endpoints.
// Imported only by ./logsApi.ts.

import type { LogRow } from '../../api';
import {
  SEVERITIES,
  type FacetKey,
  type FacetValue,
  type HistogramBucket,
  type LogCursor,
  type LogCursorPage,
  type LogFacets,
  type LogHistogram,
  type LogsFilters,
  type LogsListResult,
  type Severity,
} from './logsTypes';
import { createSampleRng, latency } from '../../lib/sampleData';
import { MS_PER_MINUTE, MS_PER_HOUR, MS_PER_DAY } from '../../lib/constants';

interface SampleRow extends LogRow {
  _ts: number;
  _sev: Severity;
  _event: string;
  _tool: string | null;
}

const { rnd, pick, ri, hx } = createSampleRng(1337);

const TOOLS = ['Read', 'Edit', 'Write', 'Bash', 'Grep', 'Glob', 'WebFetch', 'Task', 'TodoWrite'];
const MODELS = ['claude-sonnet-4-6', 'claude-opus-4-1', 'claude-haiku-4'];
const MCP = ['github', 'linear', 'postgres', 'sentry'];
const SEV_NUM: Record<Severity, number> = { ERROR: 17, WARN: 13, INFO: 9, DEBUG: 5 };

interface Gen {
  sev: Severity;
  scope: string;
  body: string;
  attrs: Record<string, unknown>;
  tool?: string | null;
}

const EVENTS: Record<string, () => Gen> = {
  tool_result: () => {
    const tool = pick(TOOLS);
    const dur = ri(8, 4200);
    const err = rnd() < 0.07;
    const sev: Severity = err ? 'ERROR' : dur > 2500 ? 'WARN' : 'INFO';
    return {
      sev,
      scope: 'claude_code.tools',
      tool,
      body: err
        ? `Tool ${tool} failed: ${pick(['ENOENT no such file', 'exit code 1', 'timeout exceeded'])}`
        : `Tool ${tool} completed in ${dur}ms`,
      attrs: { 'tool_name': tool, 'tool.status': err ? 'error' : 'ok', duration_ms: dur, 'tokens.tool_output': ri(40, 9000) },
    };
  },
  tool_decision: () => {
    const tool = pick(TOOLS);
    const allow = rnd() < 0.86;
    return {
      sev: allow ? 'DEBUG' : 'WARN',
      scope: 'claude_code.permissions',
      tool,
      body: `Permission ${allow ? 'auto-allowed' : 'prompted'} for ${tool}`,
      attrs: { 'tool_name': tool, decision: allow ? 'accept' : 'reject', 'permission.source': allow ? 'settings' : 'user' },
    };
  },
  permission_denied: () => {
    const tool = pick(['Bash', 'Write', 'Edit', 'WebFetch']);
    const rule = pick(['Bash(rm -rf:*)', 'Write(.env)', 'Bash(git push:*)', 'Edit(/etc/*)']);
    return {
      sev: 'ERROR',
      scope: 'claude_code.permissions',
      tool,
      body: `Permission denied: ${tool} blocked by settings.json rule "${rule}"`,
      attrs: { 'tool_name': tool, decision: 'reject', 'permission.rule': rule, blocking: true },
    };
  },
  // A large assistant payload the collector truncated at ~60kB → structurally
  // broken JSON. Exercises the attribute truncate + "View more" + repair modal.
  api_response_body: () => {
    const model = pick(MODELS);
    let sig = '';
    for (let i = 0; i < 1100; i += 1) {
      sig += 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'[Math.floor(rnd() * 64)];
    }
    // NOTE: intentionally cut off mid-"signature" string (no closing quote/braces)
    const json = `{"model":"${model}","id":"msg_${hx(24)}","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"<REDACTED>","signature":"${sig}`;
    return {
      sev: 'DEBUG',
      scope: 'com.anthropic.claude_code.events',
      tool: null,
      body: 'claude_code.api_response_body',
      attrs: {
        body: `${json} [truncated 60000 bytes]`,
        'gen_ai.response.model': model,
        'gen_ai.usage.output_tokens': ri(200, 4000),
        'http.status_code': 200,
      },
    };
  },
  api_request: () => {
    const model = pick(MODELS);
    const inTok = ri(900, 14000);
    const out = ri(120, 3800);
    const cacheR = ri(20000, 240000);
    const slow = rnd() < 0.12;
    return {
      sev: slow ? 'WARN' : 'INFO',
      scope: 'anthropic.api',
      tool: null,
      body: `api_request → ${model}  in=${inTok} out=${out} cache_read=${Math.round(cacheR / 1000)}k`,
      attrs: {
        'gen_ai.request.model': model,
        'gen_ai.usage.input_tokens': inTok,
        'gen_ai.usage.output_tokens': out,
        'gen_ai.usage.cache_read_tokens': cacheR,
        ttfb_ms: slow ? ri(9000, 28000) : ri(280, 1400),
        stop_reason: pick(['end_turn', 'tool_use', 'tool_use', 'max_tokens']),
      },
    };
  },
  hook_executed: () => {
    const hook = pick(['PreToolUse:Bash', 'PostToolUse:Edit', 'UserPromptSubmit', 'Stop']);
    const code = rnd() < 0.84 ? 0 : pick([1, 2]);
    return {
      sev: code === 0 ? 'DEBUG' : 'WARN',
      scope: 'claude_code.hooks',
      tool: null,
      body: `Hook ${hook} exited ${code}`,
      attrs: { 'hook.event': hook, 'hook.exit_code': code, duration_ms: ri(20, 1800) },
    };
  },
  mcp_request: () => {
    const server = pick(MCP);
    const fail = rnd() < 0.1;
    const dur = fail ? ri(30000, 31000) : ri(60, 2400);
    return {
      sev: fail ? 'ERROR' : 'INFO',
      scope: 'mcp.client',
      tool: server,
      body: fail ? `MCP server "${server}" request timed out after 30000ms` : `MCP ${server}.${pick(['search', 'query', 'list'])} ok in ${dur}ms`,
      attrs: { 'tool_name': server, duration_ms: dur, 'mcp.status': fail ? 'timeout' : 'ok' },
    };
  },
  user_prompt: () => ({
    sev: 'INFO',
    scope: 'claude_code.session',
    tool: null,
    body: `user_prompt submitted (${ri(40, 2200)} chars)`,
    attrs: { 'prompt.chars': ri(40, 2200) },
  }),
  compaction: () => {
    const before = ri(178000, 199000);
    const after = ri(40000, 90000);
    return {
      sev: 'WARN',
      scope: 'claude_code.context',
      tool: null,
      body: `Context compaction triggered: ${Math.round(before / 1000)}k → ${Math.round(after / 1000)}k tokens`,
      attrs: { 'tokens.before': before, 'tokens.after': after },
    };
  },
};

const WEIGHTS: Array<[string, number]> = [
  ['tool_result', 34],
  ['tool_decision', 14],
  ['api_request', 18],
  ['hook_executed', 9],
  ['user_prompt', 6],
  ['mcp_request', 7],
  ['permission_denied', 4],
  ['compaction', 2],
  ['api_response_body', 5],
];
const BAG: string[] = [];
WEIGHTS.forEach(([k, w]) => {
  for (let i = 0; i < w; i += 1) {
    BAG.push(k);
  }
});

const NOW = Date.now();
const SESSIONS = Array.from({ length: 40 }, () => `${hx(8)}-${hx(4)}`);

const mkRow = (t: number, forceEvent?: string): SampleRow => {
  const name = forceEvent ?? pick(BAG);
  const g = EVENTS[name]();
  const session = pick(SESSIONS);
  const attributes: Record<string, unknown> = { 'event.name': name, ...g.attrs, 'session.id': session };
  return {
    id: 0,
    timestamp: new Date(t).toISOString(),
    severityNumber: SEV_NUM[g.sev],
    severityText: g.sev,
    body: g.body,
    scopeName: g.scope,
    traceId: hx(32),
    spanId: hx(16),
    attributes,
    resourceAttributes: { 'service.name': 'claude-code' },
    _ts: t,
    _sev: g.sev,
    _event: name,
    _tool: g.tool ?? null,
  };
};

const STORE: SampleRow[] = (() => {
  const rows: SampleRow[] = [];
  for (let i = 0; i < 6000; i += 1) {
    rows.push(mkRow(NOW - Math.pow(rnd(), 2.2) * 30 * MS_PER_DAY));
  }
  // incident: a 40-min error burst ~5h back, so the histogram shows a spike
  for (let i = 0; i < 90; i += 1) {
    rows.push(mkRow(NOW - 5 * MS_PER_HOUR + (rnd() - 0.5) * 40 * MS_PER_MINUTE, pick(['permission_denied', 'mcp_request', 'tool_result'])));
  }
  rows.sort((a, b) => b._ts - a._ts);
  rows.forEach((r, i) => {
    r.id = rows.length - i;
  });
  return rows;
})();

const inWindow = (r: SampleRow, f: LogsFilters) => {
  const t = r._ts;
  return t >= Date.parse(f.startTimestamp) && t <= Date.parse(f.endTimestamp);
};

const matches = (r: SampleRow, f: LogsFilters, exclude: FacetKey | 'q' | null): boolean => {
  if (!inWindow(r, f)) {
    return false;
  }
  if (exclude !== 'q' && f.q) {
    const hay = `${r.body} ${JSON.stringify(r.attributes)}`.toLowerCase();
    if (!hay.includes(f.q.toLowerCase())) {
      return false;
    }
  }
  if (exclude !== 'severity' && f.severity?.length && !f.severity.includes(r._sev)) {
    return false;
  }
  if (exclude !== 'event' && f.event?.length && !f.event.includes(r._event)) {
    return false;
  }
  if (exclude !== 'tool' && f.tool?.length && (r._tool == null || !f.tool.includes(r._tool))) {
    return false;
  }
  return true;
};

const NICE = [MS_PER_MINUTE, 2 * MS_PER_MINUTE, 5 * MS_PER_MINUTE, 10 * MS_PER_MINUTE, 15 * MS_PER_MINUTE, 30 * MS_PER_MINUTE, MS_PER_HOUR, 2 * MS_PER_HOUR, 3 * MS_PER_HOUR, 6 * MS_PER_HOUR, 12 * MS_PER_HOUR, MS_PER_DAY, 2 * MS_PER_DAY];
const niceBucket = (spanMs: number, target: number) => {
  const raw = spanMs / target;
  return NICE.find((n) => n >= raw) ?? NICE[NICE.length - 1];
};

// ---- sample endpoint implementations ----
export const sampleHistogram = async (f: LogsFilters, target: number): Promise<LogHistogram> => {
  await latency(70);
  const start = Date.parse(f.startTimestamp);
  const end = Date.parse(f.endTimestamp);
  const bw = niceBucket(end - start, target);
  const nb = Math.max(1, Math.ceil((end - start) / bw));
  const buckets: HistogramBucket[] = Array.from({ length: nb }, (_, i) => ({
    t0: new Date(start + i * bw).toISOString(),
    t1: new Date(start + (i + 1) * bw).toISOString(),
    ERROR: 0,
    WARN: 0,
    INFO: 0,
    DEBUG: 0,
  }));
  // histogram applies all filters except severity (legend toggles client-side)
  const hf: LogsFilters = { ...f, severity: [] };
  STORE.forEach((r) => {
    if (!matches(r, hf, 'severity')) {
      return;
    }
    const idx = Math.floor((r._ts - start) / bw);
    if (idx >= 0 && idx < nb) {
      buckets[idx][r._sev] += 1;
    }
  });
  return { bucketMs: bw, buckets };
};

export const sampleFacets = async (f: LogsFilters): Promise<LogFacets> => {
  await latency(60);
  const count = (key: FacetKey, field: (r: SampleRow) => string | null) => {
    const m = new Map<string, number>();
    STORE.forEach((r) => {
      if (!matches(r, f, key)) {
        return;
      }
      const v = field(r);
      if (v == null) {
        return;
      }
      m.set(v, (m.get(v) ?? 0) + 1);
    });
    return m;
  };
  const sevMap = count('severity', (r) => r._sev);
  const severity: FacetValue[] = SEVERITIES.map((s) => ({ value: s, count: sevMap.get(s) ?? 0 }));
  const toRows = (m: Map<string, number>) =>
    [...m.entries()].map(([value, c]) => ({ value, count: c })).sort((a, b) => b.count - a.count);
  return {
    severity,
    event: toRows(count('event', (r) => r._event)),
    tool: toRows(count('tool', (r) => r._tool)),
  };
};

const stripInternal = (r: SampleRow): LogRow => {
  const { _ts, _sev, _event, _tool, ...row } = r;
  void _ts;
  void _sev;
  void _event;
  void _tool;
  return row;
};

export const sampleCursor = async (f: LogsFilters, cursor: LogCursor | null, limit: number): Promise<LogCursorPage> => {
  await latency(240);
  let total = 0;
  const page: SampleRow[] = [];
  for (const r of STORE) {
    if (!matches(r, f, null)) {
      continue;
    }
    total += 1;
    if (cursor) {
      const ct = Date.parse(cursor.ts);
      if (r._ts > ct || (r._ts === ct && r.id >= cursor.id)) {
        continue;
      }
    }
    if (page.length < limit) {
      page.push(r);
    }
  }
  const last = page[page.length - 1];
  return {
    items: page.map(stripInternal),
    nextCursor: last ? { ts: last.timestamp, id: last.id } : null,
    hasMore: !!last && page.length === limit,
    totalCount: total,
  };
};

export const samplePage = async (f: LogsFilters, page: number, size: number): Promise<LogsListResult> => {
  await latency(200);
  const all = STORE.filter((r) => matches(r, f, null));
  const start = page * size;
  return { items: all.slice(start, start + size).map(stripInternal), totalCount: all.length };
};

// live tail: mint 1-3 fresh rows "now", prepend to the store, return those that
// match the active filters (emulates GET /api/logs?after=<cursor>).
export const sampleTail = async (f: LogsFilters): Promise<LogCursorPage> => {
  await latency(40);
  const fresh: SampleRow[] = [];
  const n = 1 + Math.floor(Math.random() * 3);
  for (let i = 0; i < n; i += 1) {
    const r = mkRow(Date.now());
    r.id = STORE[0].id + 1;
    STORE.unshift(r);
    if (matches(r, f, null)) {
      fresh.push(r);
    }
  }
  if (STORE.length > 12000) {
    STORE.length = 12000;
  }
  return { items: fresh.map(stripInternal), nextCursor: null, hasMore: false, totalCount: fresh.length };
};
