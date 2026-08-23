// ---- Aurora Trace Detail — waterfall + token gutter + inline span logs +
//      error-first nav + minimap zoom -------
(function () {
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  const TRACE_START = Date.now() - 47 * 60 * 1000;
  const traceId = '7ed9599a863b08c41f2b9d6e5a1c4087';
  const sessionId = 'sess_c1fb3015';
  // TraceRow.totalCostUsd from the backend `trace-summary` query — the
  // authoritative trace cost the header KPI states. Deliberately NOT the sum of
  // the per-span figures below: spans and the cost-bearing api_request logs
  // arrive over separate OTLP endpoints, so a request logged without a span id
  // counts toward the trace but has no span to attribute it to (here: $0.0385
  // of the total sits on no span).
  const TRACE_COST_USD = 0.4613;
  const FIRST_PROMPT = 'Refactor the auth middleware to use the new token store and make sure existing sessions stay valid during the rollout';

  // off/dur in ms relative to trace start. Includes deliberate parallelism
  // (tool.Glob ∥ tool.Read, mcp.auth nested) so the critical path is a subset.
  const SPANS = [
    // costUsd = the backend span_costs rollup: every api_request stamped with
    // this span. On the turn root that is the whole turn, so it is suppressed at
    // display time (see costOfSelectedSpan) rather than repeated under the KPI.
    { spanId: 'a10f', parent: null, name: 'session.turn', kind: 'server', off: 0, dur: 10185, status: 'ok', scope: 'claude_code.session', costUsd: 0.4228,
      attrs: { 'session.id': sessionId, 'gen_ai.system': 'anthropic', 'terminal.type': 'interactive', 'turn.index': 7, 'user.workspace': 'agent-compass' },
      events: [{ name: 'turn.start', off: 4, attrs: { 'prompt.chars': 1840, 'prompt.text': FIRST_PROMPT + '. Keep the existing cookie name so nothing signs out, and add a feature flag (auth.token_store.v2) so we can dark-launch it to the internal org first. Tests live in tests/auth/ — the middleware ones are the slow suite, run them with -q.' } }] },

    { spanId: 'b21c', parent: 'a10f', name: 'model.completion', kind: 'client', off: 120, dur: 1580, status: 'ok', scope: 'claude_code.models', effort: 'high',
      attrs: { 'gen_ai.request.model': 'claude-sonnet-4-6', 'gen_ai.usage.input_tokens': 18432, 'gen_ai.usage.output_tokens': 612, 'gen_ai.usage.cache_read_input_tokens': 142880, 'gen_ai.usage.cache_creation_input_tokens': 2048, 'gen_ai.response.stop_reason': 'tool_use', 'model': 'claude-sonnet-4-6', 'gen_ai.system.prompt': 'You are Claude Code, Anthropic\u2019s official CLI for Claude.', 'gen_ai.user.prompt': 'Refactor the auth middleware to use the new token store.' },
      events: [{ name: 'first_token', off: 340, attrs: { 'latency_ms': 220 } }] },
    { spanId: 'b21d', parent: 'b21c', name: 'model.sample', kind: 'internal', off: 280, dur: 949, status: 'ok', scope: 'claude_code.models',
      attrs: { 'sampler': 'top_p', 'temperature': 1, 'top_p': 0.95 }, events: [] },

    { spanId: 'c30a', parent: 'a10f', name: 'tool.Read', kind: 'internal', off: 1760, dur: 210, status: 'ok', scope: 'claude_code.tools',
      attrs: { 'tool_name': 'Read', 'tool.status': 'ok', 'file_path': 'src/pages/TracesPage/TracesPageView.tsx', 'result.lines': 169, 'duration_ms': 210 }, events: [] },
    { spanId: 'c30c', parent: 'a10f', name: 'tool.Glob', kind: 'internal', off: 1760, dur: 620, status: 'ok', scope: 'claude_code.tools',
      attrs: { 'tool_name': 'Glob', 'tool.status': 'ok', 'pattern': 'src/**/*.tsx', 'result.files': 38, 'duration_ms': 620 }, events: [] },

    { spanId: 'c31b', parent: 'a10f', name: 'tool.Edit', kind: 'internal', off: 2430, dur: 1230, status: 'ok', scope: 'claude_code.tools',
      attrs: { 'tool_name': 'Edit', 'tool.status': 'ok', 'file_path': 'src/pages/TracesPage/TracesPageView.tsx', 'lines_changed': 24, 'duration_ms': 1230 }, events: [] },

    { spanId: 'd42e', parent: 'a10f', name: 'model.completion', kind: 'client', off: 3720, dur: 1930, status: 'ok', scope: 'claude_code.models', effort: 'medium',
      attrs: { 'gen_ai.request.model': 'claude-sonnet-4-6', 'gen_ai.usage.input_tokens': 21044, 'gen_ai.usage.output_tokens': 1284, 'gen_ai.usage.cache_read_input_tokens': 158220, 'gen_ai.usage.cache_creation_input_tokens': 0, 'gen_ai.response.stop_reason': 'tool_use', 'model': 'claude-sonnet-4-6' },
      events: [{ name: 'first_token', off: 4010, attrs: { 'latency_ms': 290 } }] },
    { spanId: 'd42f', parent: 'd42e', name: 'model.sample', kind: 'internal', off: 3900, dur: 1160, status: 'ok', scope: 'claude_code.models',
      attrs: { 'sampler': 'top_p', 'temperature': 1, 'top_p': 0.95 }, events: [] },

    { spanId: 'e53c', parent: 'a10f', name: 'mcp.github.search_issues', kind: 'client', off: 5720, dur: 1390, status: 'ok', scope: 'mcp.client',
      attrs: { 'mcp.server': 'github', 'rpc.method': 'tools/call', 'http.status_code': 200, 'result.count': 12, 'duration_ms': 1390 }, events: [] },
    { spanId: 'e53b', parent: 'e53c', name: 'mcp.auth', kind: 'internal', off: 5900, dur: 180, status: 'ok', scope: 'mcp.client',
      attrs: { 'auth.scheme': 'oauth2', 'auth.cached': true }, events: [] },
    { spanId: 'e53d', parent: 'e53c', name: 'mcp.request', kind: 'internal', off: 5870, dur: 1090, status: 'ok', scope: 'mcp.client',
      attrs: { 'net.peer.name': 'api.github.com', 'request.bytes': 612, 'response.bytes': 18044 }, events: [] },

    { spanId: 'f64a', parent: 'a10f', name: 'tool.Bash', kind: 'internal', off: 7180, dur: 1240, status: 'error', scope: 'claude_code.tools',
      statusMessage: 'exit code 1: command not found: pytest',
      attrs: { 'tool_name': 'Bash', 'tool.status': 'error', 'tool_use_id': 'toolu_018Qh9G2pYeBVdBQAHxoJKVy', 'command': 'pytest tests/ -q', 'full_command': 'git commit -m "$(cat <<\'EOF\'\nfeat: refactor span cost display with per-request fallback and remove log-count badge\n\nIntroduces costOfSelectedSpan, which prefers the stamped span_costs figure but falls back to summing the span\'s own api_request logs — putting a per-call price on model rows that span_costs leaves at 0. Consolidates cost display to the drawer\'s meta grid and removes the redundant Tokens section row.\n\nCo-Authored-By: Claude <noreply@anthropic.com>\nEOF\n)"', 'exit_code': 1, 'duration_ms': 1240 },
      events: [{ name: 'process.exit', off: 8400, attrs: { 'exit_code': 1, 'signal': null, 'stderr': 'Traceback (most recent call last):\n  File "/Users/dtanaka/Library/Caches/pypoetry/virtualenvs/agent-compass-9f2b1c4d7e/bin/pytest", line 5, in <module>\n    from pytest import console_main\nModuleNotFoundError: No module named \'pytest\'\nzsh: command not found: pytest' } }] },

    { spanId: 'a875', parent: 'a10f', name: 'model.completion', kind: 'client', off: 8480, dur: 1700, status: 'ok', scope: 'claude_code.models',
      attrs: { 'gen_ai.request.model': 'claude-sonnet-4-6', 'gen_ai.usage.input_tokens': 23880, 'gen_ai.usage.output_tokens': 968, 'gen_ai.usage.cache_read_input_tokens': 171340, 'gen_ai.usage.cache_creation_input_tokens': 1024, 'gen_ai.response.stop_reason': 'end_turn', 'model': 'claude-sonnet-4-6' },
      events: [{ name: 'first_token', off: 8760, attrs: { 'latency_ms': 280 } }] },
  ];

  // github.search_issues returns a payload large enough to trip the
  // “view formatted” modal in the dock's log detail (mirrors real OTLP tool
  // results that ship a JSON blob truncated at ~60KB).
  const GH_ISSUES = [
    { number: 4821, title: 'Waterfall: duration label clips on full-width root span', state: 'open', labels: ['bug', 'traces', 'ui'], comments: 3, assignee: 'dtanaka' },
    { number: 4809, title: 'Token gutter should hide when no model spans present', state: 'open', labels: ['enhancement', 'traces'], comments: 1, assignee: null },
    { number: 4788, title: 'Minimap brush jumps when dragging past left edge', state: 'open', labels: ['bug', 'traces'], comments: 5, assignee: 'lchen' },
    { number: 4771, title: 'Add per-span log count badge to waterfall rows', state: 'closed', labels: ['enhancement', 'traces', 'ui'], comments: 2, assignee: 'dtanaka' },
    { number: 4744, title: 'Self-time bar uses primary instead of warning color', state: 'open', labels: ['bug', 'ui', 'good-first-issue'], comments: 1, assignee: null },
    { number: 4730, title: 'Long tool payloads should open in a formatted modal', state: 'closed', labels: ['enhancement', 'logs'], comments: 4, assignee: 'lchen' },
    { number: 4699, title: 'Error-first navigation skips nested error spans', state: 'open', labels: ['bug', 'traces'], comments: 6, assignee: 'dtanaka' },
    { number: 4662, title: 'Minimap legend missing service hue swatches', state: 'closed', labels: ['ui', 'traces'], comments: 0, assignee: null },
  ];

  const LOGS = [
    { off: 6, sev: 'INFO', scope: 'claude_code.session', event: 'turn.start', body: 'Turn 7 started — interactive session ' + sessionId, attrs: { 'prompt.chars': 1840, 'terminal.type': 'interactive', 'turn.index': 7 } },
    { off: 130, sev: 'DEBUG', scope: 'claude_code.models', event: 'gen_ai.request', body: 'model.completion request → claude-sonnet-4-6 (cache_read 142,880)', attrs: { 'gen_ai.request.model': 'claude-sonnet-4-6', 'gen_ai.usage.cache_read_input_tokens': 142880 } },
    // api_request logs carry the billed cost_usd. Claude Code stamps them with
    // the span that was active when the request went out, so span_costs credits
    // the turn root; the backend re-points each one onto the model span that
    // actually made the call before the frontend sees it, which is where a
    // model row's own cost comes from.
    { off: 1690, sev: 'DEBUG', scope: 'claude_code.models', event: 'api_request', body: 'api_request → claude-sonnet-4-6 completed in 1.58 s', attrs: { 'event.name': 'api_request', 'model': 'claude-sonnet-4-6', 'cost_usd': 0.1274, 'duration_ms': 1580, 'input_tokens': 18432, 'output_tokens': 612 } },
    { off: 1700, sev: 'INFO', scope: 'claude_code.tools', event: 'tool_result', tool: 'Read', body: 'Tool Read completed in 210ms (169 lines)', attrs: { 'file_path': 'src/pages/TracesPage/TracesPageView.tsx', 'result.lines': 169, 'duration_ms': 210 } },
    { off: 2360, sev: 'INFO', scope: 'claude_code.tools', event: 'tool_result', tool: 'Glob', body: 'Tool Glob matched 38 files for src/**/*.tsx', attrs: { 'pattern': 'src/**/*.tsx', 'result.files': 38, 'duration_ms': 620 } },
    { off: 3620, sev: 'INFO', scope: 'claude_code.tools', event: 'tool_result', tool: 'Edit', body: 'Tool Edit completed in 1230ms (24 lines changed)', attrs: { 'file_path': 'src/pages/TracesPage/TracesPageView.tsx', 'lines_changed': 24, 'duration_ms': 1230 } },
    { off: 5640, sev: 'DEBUG', scope: 'claude_code.models', event: 'api_request', body: 'api_request → claude-sonnet-4-6 completed in 1.93 s', attrs: { 'event.name': 'api_request', 'model': 'claude-sonnet-4-6', 'cost_usd': 0.1416, 'duration_ms': 1930, 'input_tokens': 21044, 'output_tokens': 1284 } },
    { off: 5710, sev: 'DEBUG', scope: 'claude_code.permissions', event: 'permission.decision', body: 'Permission auto-allowed for mcp.github via project policy', attrs: { 'decision': 'allow', 'rule': 'project policy', 'target': 'mcp.github' } },
    { off: 7080, sev: 'INFO', scope: 'mcp.client', event: 'tool_result', tool: 'github.search_issues', body: 'mcp.github.search_issues → 200 OK, 12 results', attrs: { 'mcp.server': 'github', 'http.status_code': 200, 'result.count': 12, 'result': JSON.stringify(GH_ISSUES) } },
    { off: 7200, sev: 'DEBUG', scope: 'claude_code.permissions', event: 'permission.decision', body: 'Permission auto-allowed for Bash via settings.json allow rule', attrs: { 'decision': 'allow', 'rule': 'settings.json allow' } },
    { off: 8400, sev: 'ERROR', scope: 'claude_code.tools', event: 'tool_result', tool: 'Bash', body: 'Tool Bash failed: exit code 1 — command not found: pytest', attrs: { 'command': 'pytest tests/ -q', 'exit_code': 1, 'stderr': '/bin/sh: pytest: command not found' } },
    { off: 10160, sev: 'DEBUG', scope: 'claude_code.models', event: 'api_request', body: 'api_request → claude-sonnet-4-6 completed in 1.70 s', attrs: { 'event.name': 'api_request', 'model': 'claude-sonnet-4-6', 'cost_usd': 0.1538, 'duration_ms': 1700, 'input_tokens': 23880, 'output_tokens': 968 } },
    // assistant_response bodies are prose markdown, not JSON — the drawer
    // always offers "view formatted" for the response attr (no length gate)
    // and renders it through the markdown library instead of the JSON modal.
    { off: 9820, sev: 'INFO', scope: 'com.anthropic.claude_code.events', event: 'assistant_response', body: 'claude_code.assistant_response', attrs: { 'event.name': 'assistant_response', 'model': 'claude-sonnet-4-6', 'user.id': 'c1eef54a91ea2a2ad93f203e31c0ba53d1bfe442c1b17c48965ab7db27212bc6', 'response': 'Done. Added markdown rendering for `assistant_response` log bodies in the trace drawer.\n\n- The **response** attribute now always shows a `view formatted` button, even under the normal 240-character clamp that gates every other attribute.\n- Opening it renders through the markdown library instead of the plain/JSON modal — headings, lists, `inline code`, and bold all come through styled instead of as raw syntax.\n- Every other long value (`full_command`, `stderr`, tool result JSON) is untouched: only `response` on this one event gets markdown treatment.\n\nNothing else in the dock changed — the body preview line above still shows the plain event name, same as any other log row.' } },
    { off: 10180, sev: 'INFO', scope: 'claude_code.session', event: 'turn.end', body: 'Turn 7 completed — 5 tools, 1 error, 3 model calls', attrs: { 'tools': 5, 'errors': 1, 'model_calls': 3 } },
  ];

  // ---- derive structure -----------------------------------------------------
  const byId = new Map(SPANS.map((s) => [s.spanId, s]));
  const childrenOf = new Map();
  SPANS.forEach((s) => { if (s.parent) { (childrenOf.get(s.parent) || childrenOf.set(s.parent, []).get(s.parent)).push(s); } });
  childrenOf.forEach((arr) => arr.sort((a, b) => a.off - b.off));
  const roots = SPANS.filter((s) => !s.parent);
  const depth = new Map(), index = new Map();
  let counter = 1;
  (function walk(list, d) { list.forEach((s) => { depth.set(s.spanId, d); index.set(s.spanId, counter++); walk(childrenOf.get(s.spanId) || [], d + 1); }); })(roots, 0);
  const TOTAL = Math.max(...SPANS.map((s) => s.off + s.dur));

  const TOKKEYS = { input: ['gen_ai.usage.input_tokens'], output: ['gen_ai.usage.output_tokens'], cacheCreate: ['gen_ai.usage.cache_creation_input_tokens'], cacheRead: ['gen_ai.usage.cache_read_input_tokens'] };
  function tokensOf(s) {
    const r = (keys) => keys.reduce((a, k) => a + (typeof s.attrs[k] === 'number' ? s.attrs[k] : 0), 0);
    const input = r(TOKKEYS.input), output = r(TOKKEYS.output), cc = r(TOKKEYS.cacheCreate), cr = r(TOKKEYS.cacheRead);
    return { input, output, cacheCreate: cc, cacheRead: cr, total: input + output + cc + cr };
  }
  // Cost is what the backend reports was billed — never a client-side estimate.
  // Pricing a span's tokens at published rates ran 2-3x off real spend and made
  // a prompt's cost disagree with the trace it links to, so the published-rate
  // table that used to live here is gone.
  const API_REQUEST_EVENT_NAME = 'api_request';
  // The turn root. Upstream matches `claude_code.interaction`; this fixture's
  // turn root is `session.turn`, and both normalize the same way.
  const TURN_ROOT_NAMES = new Set(['interaction', 'session.turn']);
  const isTurnRootSpan = (s) => TURN_ROOT_NAMES.has(String(s.name || '').toLowerCase().replace(/^claude_code\./, ''));
  // The span_costs rollup stamped on this span: every api_request made under it.
  function costOfSpan(s) { return typeof s.costUsd === 'number' && isFinite(s.costUsd) ? s.costUsd : 0; }
  // The cost of the calls this span itself made, from the api_request logs
  // bucketed onto it — which is what puts a figure on a model row, since
  // span_costs leaves those at 0. Summed: nothing guarantees one request a span.
  function costOfSpanRequests(logs) {
    if (!logs || !logs.length) { return 0; }
    return logs.reduce((a, l) => {
      if (!l.attrs || l.attrs['event.name'] !== API_REQUEST_EVENT_NAME) { return a; }
      const c = l.attrs['cost_usd'];
      return a + (typeof c === 'number' && isFinite(c) ? c : 0);
    }, 0);
  }
  // What a row and the inspector show. The stamped rollup wins when there is one
  // (it covers requests the log bucketing may not have reached); only when it is
  // 0 does the span's own request log answer. Never summed, so a turn's spend
  // can't be counted twice on one span. The turn root reports 0 regardless: its
  // rollup is the whole trace, which the header's Cost KPI already states —
  // repeating it on the root row invited summing it with the model rows below.
  function costOfSelectedSpan(s) {
    if (isTurnRootSpan(s)) { return 0; }
    const stamped = costOfSpan(s);
    return stamped > 0 ? stamped : costOfSpanRequests(logsBySpan.get(s.spanId));
  }
  // Short display name for a model id: "claude-sonnet-4-6" → "Sonnet 4 6".
  function shortModelName(model) {
    const parts = String(model).replace(/^claude-/, '').split('-');
    if (!parts.length || parts[0] === '') { return String(model); }
    return [parts[0].charAt(0).toUpperCase() + parts[0].slice(1), ...parts.slice(1)].join(' ');
  }
  const TOK_MAX = Math.max(1, ...SPANS.map((s) => tokensOf(s).total));
  function tokTotalTip(t) {
    return fmtTok(t.total) + ' total tokens'
      + '\nInput: ' + fmtTok(t.input)
      + '\nOutput: ' + fmtTok(t.output)
      + '\nCache creation: ' + fmtTok(t.cacheCreate)
      + '\nCache read: ' + fmtTok(t.cacheRead);
  }
  // Hover-card markup for the row's two token chips. The full-rate card breaks out
  // only the three counts the chip sums — cache read has its own chip and card
  // immediately beside it, so listing it here repeated the number and its 0.1×
  // note a few pixels apart.
  const tipRow = (k, v) => '<div class="trow"><span>' + k + '</span><b>' + v.toLocaleString() + '</b></div>';
  function tokTipHtml(t) {
    return '<div class="ttl">' + fmtTok(t.input + t.output + t.cacheCreate) + ' at full rate</div>'
      + tipRow('Input', t.input) + tipRow('Output', t.output) + tipRow('Cache creation', t.cacheCreate);
  }
  function crTipHtml(t) {
    const eligible = t.input + t.cacheCreate + t.cacheRead;
    const hit = eligible > 0 ? Math.round(t.cacheRead / eligible * 100) : null;
    return '<div class="ttl">' + fmtTok(t.cacheRead) + ' cache read</div>'
      + (hit === null ? '' : tipRow('Of cacheable tokens', hit + '%'))
      + '<div class="tnote">billed at 0.1× the input rate</div>';
  }
  // The tool chip's hover card: whichever attr carries what the tool was actually
  // asked to do. A full_command can run to several hundred characters (heredoc
  // commit messages especially), so the card clamps and points at the drawer,
  // which has the truncate-and-modal path for the whole string — a hover card
  // can't hold a button (it is pointer-events:none by design).
  const TOOL_ARG_KEYS = ['full_command', 'command', 'file_path', 'pattern', 'query', 'url'];
  const TIP_CMD_MAX = 300;
  function toolTipHtml(s) {
    const a = s.attrs || {};
    // Agent (subagent launch) spans carry a subagent_type, not a command — that's
    // what tells you which subagent ran, so it takes priority over full_command.
    const key = a.tool_name === 'Agent' && a.subagent_type !== undefined && a.subagent_type !== null && String(a.subagent_type) !== ''
      ? 'subagent_type'
      : TOOL_ARG_KEYS.find((k) => a[k] !== undefined && a[k] !== null && String(a[k]) !== '');
    let out = '<div class="ttl">' + esc(String(a.tool_name)) + (a['tool.status'] ? '<em>' + esc(String(a['tool.status'])) + '</em>' : '') + '</div>';
    if (!key) { return out + '<div class="tnote">no command recorded on this span</div>'; }
    const raw = attrStr(a[key]);
    const clipped = raw.length > TIP_CMD_MAX;
    out += '<div class="tkey">' + esc(key) + '</div>'
      + '<div class="tcmd">' + esc(clipped ? raw.slice(0, TIP_CMD_MAX).replace(/\s+$/, '') + '\u2026' : raw) + '</div>';
    if (clipped) { out += '<div class="tnote">' + raw.length.toLocaleString() + ' chars · open the span for the full command</div>'; }
    return out;
  }
  function descendantErrors(spanId) { let n = 0; (childrenOf.get(spanId) || []).forEach((c) => { if (c.status === 'error') { n++; } n += descendantErrors(c.spanId); }); return n; }
  function selfTime(s) {
    const kids = childrenOf.get(s.spanId) || [];
    if (!kids.length) { return s.dur; }
    const iv = kids.map((c) => [c.off, c.off + c.dur]).sort((a, b) => a[0] - b[0]);
    let union = 0, cs = Infinity, ce = -Infinity;
    iv.forEach(([a, b]) => { if (a > ce) { if (ce > cs) { union += ce - cs; } cs = a; ce = b; } else if (b > ce) { ce = b; } });
    if (ce > cs) { union += ce - cs; }
    return Math.max(0, s.dur - union);
  }

  // ---- logs bucketed onto the innermost containing span ---------------------
  const logsBySpan = new Map();
  LOGS.forEach((l) => {
    let best = null;
    SPANS.forEach((s) => { if (l.off >= s.off && l.off <= s.off + s.dur) { if (!best || s.dur < best.dur) { best = s; } } });
    const id = best ? best.spanId : roots[0].spanId;
    if (!logsBySpan.has(id)) { logsBySpan.set(id, []); }
    logsBySpan.get(id).push(l);
  });

  // ---- formatters -----------------------------------------------------------
  function fmtDur(ms) { if (ms == null) { return ''; } if (ms >= 1000) { return (ms / 1000).toFixed(2) + ' s'; } if (ms >= 10) { return Math.round(ms) + ' ms'; } return ms.toFixed(1) + ' ms'; }
  function fmtTok(n) { if (n >= 1e6) { return (n / 1e6).toFixed(2).replace(/\.?0+$/, '') + 'M'; } if (n >= 1e3) { return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K'; } return String(n); }
  function fmtUsd(n) { return '$' + (n < 1 ? n.toFixed(3) : n.toFixed(2)); }
  function clock(ms) { const d = new Date(ms); const p = (n) => String(n).padStart(2, '0'); return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds()) + '.' + String(d.getMilliseconds()).padStart(3, '0'); }
  const SVC_COLOR = { 'claude_code.session': '#8b5cff', 'claude_code.tools': '#13b6e6', 'claude_code.models': '#ff6ad5', 'mcp.client': '#e6952b', 'claude_code.permissions': '#9a93b6' };

  // ---- state ----------------------------------------------------------------
  const collapsed = new Set();
  let selected = null;
  let panelW = null;
  let viewStart = 0, viewEnd = TOTAL;
  const vspan = () => Math.max(1, viewEnd - viewStart);
  const px = (t) => ((t - viewStart) / vspan()) * 100;

  // ---- header + summary -----------------------------------------------------
  function idChip(elId, label, value) {
    const el = $(elId);
    if (!el) { return; }
    el.innerHTML = '<span class="idlabel">' + label + '</span><span class="idval">' + esc(value) + '</span>' +
      '<span class="cp"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg></span>';
    el.title = label + ': ' + value + ' — click to copy';
    el.onclick = () => {
      if (navigator.clipboard) {
        navigator.clipboard.writeText(value);
        const v = el.querySelector('.idval'); const prev = v.textContent;
        v.textContent = 'copied!'; setTimeout(() => { v.textContent = prev; }, 1200);
      }
    };
  }
  function renderHeader() {
    idChip('tracechip', 'trace', traceId);
    idChip('sessionchip', 'session', sessionId);
  }
  function renderSummary() {
    const errs = SPANS.filter((s) => s.status === 'error').length;
    const services = [...new Set(SPANS.map((s) => s.scope))];
    const tokAgg = SPANS.reduce((a, s) => { const t = tokensOf(s); a.input += t.input; a.output += t.output; a.cacheCreate += t.cacheCreate; a.cacheRead += t.cacheRead; a.total += t.total; return a; }, { input: 0, output: 0, cacheCreate: 0, cacheRead: 0, total: 0 });
    const tokTotal = tokAgg.total;
    const modelCalls = SPANS.filter((s) => s.name === 'model.completion').length;
    const toolCalls = SPANS.filter((s) => /^(tool\.|mcp\.)/.test(s.name) || (s.attrs && s.attrs.tool_name)).length;
    const maxDepth = Math.max(...depth.values()) + 1;
    const traceCost = TRACE_COST_USD;
    const svcFull = services.map((s) => s.replace('claude_code.', '')).join(' · ');
    const items = [
      { k: 'Cost', t: fmtUsd(traceCost), v: '<span class="v grad">' + fmtUsd(traceCost) + '</span>' },
      { k: 'Duration', t: fmtDur(TOTAL), v: '<span class="v mono">' + fmtDur(TOTAL) + '</span>' },
      { k: 'Spans', t: String(SPANS.length), v: '<span class="v">' + SPANS.length + '</span>' },
      { k: 'Tool calls', t: String(toolCalls), v: '<span class="v">' + toolCalls + '</span>' },
      { k: 'Depth', t: maxDepth + ' levels', v: '<span class="v">' + maxDepth + ' <small>levels</small></span>' },
      { k: 'Errors', t: (errs ? errs + ' error' + (errs === 1 ? '' : 's') : 'No errors'), v: '<span class="v ' + (errs ? 'bad' : '') + '">' + (errs || '—') + '</span>' },
    ];
    $('summary').innerHTML = items.map((it) => '<div class="si" data-full="' + esc(it.k + ': ' + it.t) + '"><div class="k">' + it.k + '</div>' + it.v + '</div>').join('');
    // only attach a tooltip to cells whose value is actually clipped
    [...$('summary').querySelectorAll('.si')].forEach((si) => {
      const v = si.querySelector('.v');
      if (v && v.scrollWidth > v.clientWidth) { si.title = si.dataset.full; }
    });
    renderTokCard(tokAgg, tokTotal, modelCalls, traceCost);
    renderMetaFoot(services, svcFull);
    renderPromptRow();
    $('sumcap').innerHTML = '<b>' + esc(fmtUsd(traceCost)) + '</b> · ' + esc(fmtDur(TOTAL)) + ' · ' + SPANS.length + ' spans · ' + toolCalls + ' tool calls · ' + esc(fmtTok(tokTotal)) + ' tokens' + (errs ? ' · ' + errs + ' error' + (errs === 1 ? '' : 's') : '');
    // Collapse is per-visit only — upstream dropped the persisted preference, so
    // the Overview always opens expanded on load.
    const wrap = $('summarywrap');
    $('sumhead').onclick = () => { wrap.classList.toggle('collapsed'); };
  }
  function renderTokCard(tokAgg, tokTotal, modelCalls, traceCost) {
    const el = $('tokcard');
    if (tokTotal <= 0) {
      // Spans (which carry the token attributes) and the cost-bearing api_request
      // logs land over separate OTLP endpoints, so a trace can have a real cost
      // before any token-bearing span is in. Saying "no model calls" there would
      // contradict the non-zero Cost tile directly above, so the two states are
      // distinguished and the cost keeps showing.
      el.innerHTML = traceCost > 0
        ? '<span class="toknone">Token counts aren\'t available yet for this trace.</span><span class="toknone tokcost">' + esc(fmtUsd(traceCost)) + '</span>'
        : '<span class="toknone">No model tokens — this trace made no model calls.</span>';
      return;
    }
    // rate tag communicates billing per row instead of splitting into two
    // separate bar tracks — cache read is billed at a tenth of the other three.
    const TOK_SEG = [['Cache read', tokAgg.cacheRead, '#7c4dff', '0.1×'], ['Input', tokAgg.input, '#1aa7dd', '1×'], ['Cache creation', tokAgg.cacheCreate, '#e84bc0', '1×'], ['Output', tokAgg.output, '#22b08a', '1×']];
    const callLabel = modelCalls ? modelCalls + ' model ' + (modelCalls === 1 ? 'call' : 'calls') : 'no model calls';
    const cacheEligible = tokAgg.cacheRead + tokAgg.input + tokAgg.cacheCreate;
    const rawHit = cacheEligible > 0 ? tokAgg.cacheRead / cacheEligible * 100 : null;
    // Never round a partial cache up to a flat 100% — on a real trace cache read
    // is 99.x% of everything, and "100% cached" reads as "nothing was billed".
    const hitLabel = rawHit === null ? null : (rawHit >= 100 ? '100%' : rawHit > 99 ? '>99%' : Math.round(rawHit) + '%');
    const hitChip = hitLabel === null ? '' : '<span class="chip cacheread" title="Cache read ÷ (cache read + input + cache creation)">' + hitLabel + ' cached</span>';
    const share = (v) => {
      if (v <= 0 || tokTotal <= 0) { return '0%'; }
      const p = v / tokTotal * 100;
      return (p < 0.1 ? '<0.1' : p >= 99.95 ? '>99.9' : p.toFixed(p < 10 ? 1 : 0)) + '%';
    };
    // One log-scaled list, not a split bar + a separate legend grid. Cache read
    // routinely runs 10-100x the other three, so a linear bar paints solid
    // purple and the numbers a reader actually wants vanish into a hairline —
    // the same distortion the Token usage over-time chart solves with a log
    // axis. Order by magnitude; a floor width keeps small-but-real values visible.
    const maxV = Math.max(1, ...TOK_SEG.map(([, v]) => v));
    const logMax = Math.log10(maxV + 1) || 1;
    const rows = TOK_SEG.filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]).map(([k, v, c, rate]) => {
      const w = Math.max(4, (Math.log10(v + 1) / logMax) * 100);
      return '<div class="tokrow"><span class="lk"><span class="sw" style="background:' + c + '"></span>' + k + (rate !== '1×' ? '<em>' + rate + '</em>' : '') + '</span>' +
        '<div class="rbar"><i style="width:' + w + '%;background:' + c + '"></i></div>' +
        '<span class="lv">' + fmtTok(v) + '</span><span class="lp">' + share(v) + '</span></div>';
    }).join('');
    el.innerHTML =
      '<div class="tctop"><span class="tctot">Token composition <b>' + fmtTok(tokTotal) + '</b>' + hitChip + '</span><span class="tccap">' + callLabel + ' · ' + fmtUsd(traceCost) + '</span></div>' +
      '<div class="toklist">' + rows + '</div>' +
      '<div class="toknote">Bars scaled logarithmically — cache read runs 10–100× the other categories, at a tenth of their rate.</div>';
  }
  function renderMetaFoot(services, svcFull) {
    $('metafoot').innerHTML =
      '<div class="mi"><span class="k">Root span</span><span class="svcdot" style="background:' + SVC_COLOR[roots[0].scope] + '"></span><span class="v">' + esc(roots[0].name) + '</span></div>' +
      '<div class="mi"><span class="k">Services</span><span class="v">' + services.length + ' <small>' + esc(svcFull) + '</small></span></div>' +
      '<div class="mi"><span class="k">Started</span><span class="v">' + new Date(TRACE_START).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }) + '</span></div>';
  }
  function renderPromptRow() {
    const el = $('promptrow');
    if (!FIRST_PROMPT) { el.style.display = 'none'; return; }
    el.innerHTML = '<span class="pk">Prompt</span><span class="pv" title="' + esc(FIRST_PROMPT) + '">' + esc(FIRST_PROMPT) + '</span>';
  }

  // ---- axis (view-aware) ----------------------------------------------------
  function renderAxis() {
    $('axis').innerHTML = [0, 0.25, 0.5, 0.75, 1].map((f) =>
      '<span class="' + (f === 1 ? 'last' : '') + '" style="left:' + (f * 100) + '%">' + fmtDur(viewStart + f * vspan()) + '</span>').join('');
  }

  // ---- minimap --------------------------------------------------------------
  function renderMinimap() {
    const mt = $('mmtrack');
    // Error ticks draw last (on top of same-position ok ticks) and get a taller,
    // brighter mark — the minimap's whole job is surfacing where the errors are,
    // so an error can't lose a z-order fight against a passing ok span.
    const ordered = [...SPANS].sort((a, b) => (a.status === 'error' ? 1 : 0) - (b.status === 'error' ? 1 : 0));
    const ticks = ordered.map((s) => {
      const left = (s.off / TOTAL) * 100, w = Math.max(0.6, (s.dur / TOTAL) * 100);
      const err = s.status === 'error';
      const top = 2 + Math.min(depth.get(s.spanId), 3) * 10;
      const h = 3;
      const col = err ? 'var(--bad)' : SVC_COLOR[s.scope] || 'var(--primary)';
      return '<div class="mmtick' + (err ? ' err' : '') + '" style="left:' + left + '%;width:' + w + '%;top:' + top + 'px;height:' + h + 'px;background:' + col + '"></div>';
    }).join('');
    const bl = (viewStart / TOTAL) * 100, bw = (vspan() / TOTAL) * 100;
    mt.innerHTML = ticks
      + '<div class="mmdim" id="dimL" style="left:0;width:' + bl + '%"></div>'
      + '<div class="mmdim" id="dimR" style="left:' + (bl + bw) + '%;width:' + (100 - bl - bw) + '%"></div>'
      + '<div class="brush" id="brush" style="left:' + bl + '%;width:' + bw + '%"><span class="bh l"></span><span class="bh r"></span></div>';
    wireBrush();
    renderZoomState();
  }
  // States the mm hint between the static instruction and the live zoomed range
  // — otherwise a zoomed track looks no different from a full one and the only
  // way to tell is to notice the brush isn't full-width.
  function renderZoomState() {
    const hint = $('mmhint');
    if (!hint) { return; }
    const zoomed = viewStart > 0 || viewEnd < TOTAL;
    hint.innerHTML = zoomed
      ? '<span class="zoomrange">' + esc(fmtDur(viewStart)) + '–' + esc(fmtDur(viewEnd)) + '<i> of ' + esc(fmtDur(TOTAL)) + '</i></span><span class="zoomreset">dbl-click resets</span>'
      : 'drag to select · drag edges to resize';
    hint.classList.toggle('zoomed', zoomed);
  }
  function wireBrush() {
    const mt = $('mmtrack'), brush = $('brush');
    let drag = null;
    const downAt = (e, mode, vs0, ve0) => {
      e.preventDefault(); e.stopPropagation();
      drag = { mode, x: e.clientX, vs: vs0, ve: ve0, w: mt.getBoundingClientRect().width };
      brush.classList.add('grab');
      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
    };
    const MIN = 400;
    const move = (e) => {
      if (!drag) { return; }
      const dt = ((e.clientX - drag.x) / drag.w) * TOTAL;
      let vs = drag.vs, ve = drag.ve;
      if (drag.mode === 'move') { vs = drag.vs + dt; ve = drag.ve + dt; if (vs < 0) { ve -= vs; vs = 0; } if (ve > TOTAL) { vs -= (ve - TOTAL); ve = TOTAL; } }
      else if (drag.mode === 'l') { vs = Math.min(drag.vs + dt, ve - MIN); vs = Math.max(0, vs); }
      else if (drag.mode === 'r') { ve = Math.max(drag.ve + dt, vs + MIN); ve = Math.min(TOTAL, ve); }
      else { // 'create' — anchor is drag.vs (the press point); the other edge follows the pointer
        const t = Math.max(0, Math.min(TOTAL, drag.vs + dt));
        vs = Math.min(drag.anchor, t); ve = Math.max(drag.anchor, t);
        if (ve - vs < MIN) { ve = Math.min(TOTAL, vs + MIN); vs = Math.max(0, ve - MIN); }
      }
      viewStart = vs; viewEnd = ve;
      renderAxis(); renderWaterfall(); updateBrush(); renderZoomState();
    };
    const up = () => { drag = null; brush.classList.remove('grab'); document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up); };
    brush.addEventListener('mousedown', (e) => downAt(e, 'move', viewStart, viewEnd));
    brush.querySelector('.bh.l').addEventListener('mousedown', (e) => downAt(e, 'l', viewStart, viewEnd));
    brush.querySelector('.bh.r').addEventListener('mousedown', (e) => downAt(e, 'r', viewStart, viewEnd));
    // Press on bare track (not the brush/handles, which stopPropagation above)
    // starts a fresh range select anchored at the press point — one drag instead
    // of aiming two handles in from a full-width brush.
    mt.addEventListener('mousedown', (e) => {
      const w = mt.getBoundingClientRect().width;
      const t = Math.max(0, Math.min(TOTAL, ((e.clientX - mt.getBoundingClientRect().left) / w) * TOTAL));
      drag = { mode: 'create', x: e.clientX, vs: t, anchor: t, w };
      brush.classList.add('grab');
      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
    });
  }
  function updateBrush() {
    const brush = $('brush'), dimL = $('dimL'), dimR = $('dimR');
    const bl = (viewStart / TOTAL) * 100, bw = (vspan() / TOTAL) * 100;
    if (brush) { brush.style.left = bl + '%'; brush.style.width = bw + '%'; }
    if (dimL) { dimL.style.width = bl + '%'; }
    if (dimR) { dimR.style.left = (bl + bw) + '%'; dimR.style.width = (100 - bl - bw) + '%'; }
  }
  $('minimap').addEventListener('dblclick', () => { viewStart = 0; viewEnd = TOTAL; renderAxis(); renderWaterfall(); updateBrush(); renderZoomState(); });

  // ---- waterfall ------------------------------------------------------------
  function visibleSpans() {
    const out = [];
    (function walk(list) { list.forEach((s) => { out.push(s); if (!collapsed.has(s.spanId)) { walk(childrenOf.get(s.spanId) || []); } }); })(roots);
    return out;
  }
  // When zoomed, drop spans that fall entirely outside the window. A child is
  // always time-contained by its parent, so any visible span keeps its ancestors.
  const inView = (s) => s.off < viewEnd && (s.off + s.dur) > viewStart;
  // Rendered row list — the same order/filtering the waterfall body draws. Span
  // nav (drawer ↑/↓, ArrowUp/ArrowDown) walks this, not the span's siblings: a
  // single-root trace's root has no siblings, an orphan-rooted span's parent
  // isn't in the tree, and a sibling hidden by a collapsed ancestor or the zoom
  // window has no row to scroll to — walking rendered rows sidesteps all three.
  function visibleFiltered() { return visibleSpans().filter(inView); }
  function renderWaterfall() {
    const wf = $('wf');
    wf.className = 'wfbody';
    wf.innerHTML = '';
    visibleFiltered().forEach((s) => {
      const d = depth.get(s.spanId);
      const kids = childrenOf.get(s.spanId) || [];
      const tok = tokensOf(s);
      const spanCost = costOfSelectedSpan(s);
      const isRollupCost = costOfSpan(s) > 0;
      const below = descendantErrors(s.spanId);

      const left = Math.max(0, px(s.off)), right = Math.min(100, px(s.off + s.dur));
      const w = Math.max(0, right - left);
      const visible = w > 0 && right > 0 && left < 100;
      // duration label stays inside the track: after the bar normally, but
      // tucked inside the right end (light text) for near-full-width bars.
      const insideBar = right >= 85;
      const dlStyle = insideBar ? 'right:calc(' + (100 - right) + '% + 8px);color:#fff' : 'left:calc(' + right + '% + 6px)';
      const barBg = s.status === 'error' ? 'var(--bad)' : 'linear-gradient(90deg,var(--primary),var(--primary-2))';

      let guides = '';
      for (let i = 0; i < d; i++) { guides += '<span class="guide"></span>'; }
      const disc = kids.length
        ? '<span class="disc" data-disc="1"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M6 9l6 6 6-6"/></svg></span>'
        : '<span class="disc leaf"><span class="lf"></span></span>';
      // no log-count chip: the drawer's Logs section is the place for that, and
      // the row already carries tokens, cache read, cost and the model pill.
      const modelName = (s.attrs && (s.attrs['model'] || s.attrs['gen_ai.request.model'])) || '';
      const effort = s.effort || '';
      const fullRateTok = tok.input + tok.output + tok.cacheCreate;
      const chips =
        (fullRateTok > 0 && !chipsOff.has('tok') ? '<span class="chip tok" data-tip><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3" fill="currentColor"/></svg>' + fmtTok(fullRateTok) + '</span>' : '') +
        // cache read rides in its own quiet chip: it runs 10-100x the full-rate
        // counts, so folding it into one total made every model row read as a
        // huge, and identical, token figure.
        (tok.cacheRead > 0 && !chipsOff.has('cr') ? '<span class="chip cr" data-tip><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 0 1 15-6.7L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-15 6.7L3 16"/><path d="M3 21v-5h5"/></svg>' + fmtTok(tok.cacheRead) + '</span>' : '') +
        (spanCost > 0 && !chipsOff.has('cost') ? '<span class="chip cost" title="' + (isRollupCost ? 'Cost of the requests made under this span' : 'Cost of this model call') + '">' + fmtUsd(spanCost) + '</span>' : '') +
        (modelName && !chipsOff.has('mdl') ? '<span class="chip mdl" title="' + esc(effort ? modelName + ' · ' + effort + ' effort' : modelName) + '">' + esc(shortModelName(modelName)) + (effort ? '<em>· ' + esc(effort) + '</em>' : '') + '</span>' : '') +
        (s.attrs && s.attrs.tool_name && !chipsOff.has('tool') ? '<span class="chip tool" data-tip>' + esc(s.attrs.tool_name) + '</span>' : '') +
        (s.status === 'error' && !chipsOff.has('err') ? '<span class="chip err">error</span>' : '') +
        (below > 0 && !chipsOff.has('err') ? '<span class="chip below">+' + below + ' below</span>' : '');

      const row = document.createElement('div');
      row.className = 'span' + (collapsed.has(s.spanId) ? ' collapsed' : '') + (selected === s.spanId ? ' sel' : '');
      row.dataset.id = s.spanId;
      row.innerHTML =
        '<div class="nm">' + guides + disc +
          '<span class="ix">' + index.get(s.spanId) + '</span>' +
          '<span class="sn" title="' + esc(s.name) + '">' + esc(s.name) + '</span>' + chips +
        '</div>' +
        '<div class="track">' +
          (visible ? '<div class="b" style="left:' + left + '%;width:' + w + '%;background:' + barBg + '"></div>' +
            '<div class="dl" style="' + dlStyle + '">' + fmtDur(s.dur) + '</div>' : '') +
        '</div>';
      row.onclick = (e) => {
        if (e.target.closest('.disc') && kids.length) {
          if (collapsed.has(s.spanId)) { collapsed.delete(s.spanId); } else { collapsed.add(s.spanId); }
          syncExpandLabel(); renderWaterfall(); return;
        }
        selectSpan(s.spanId);
      };
      // Hover cards are attached as properties, not title attributes: the row's
      // name column is masked/clipped, so a CSS tooltip can't escape it, and a
      // native title can't render the two-tier breakdown.
      const tokChip = row.querySelector('.chip.tok');
      if (tokChip) { tokChip._tip = tokTipHtml(tok); }
      const crChip = row.querySelector('.chip.cr');
      if (crChip) { crChip._tip = crTipHtml(tok); }
      const toolChip = row.querySelector('.chip.tool');
      if (toolChip) { toolChip._tip = toolTipHtml(s); }
      wf.appendChild(row);
    });
  }
  // Scrolls only as far as needed to bring the row inside the container's
  // visible band, leaving a couple of rows (or a quarter of the container,
  // whichever is smaller) of margin at whichever edge it entered from — a row
  // already comfortably in view doesn't move the list at all.
  function scrollSpan(spanId) {
    const container = $('wf');
    const row = container.querySelector('[data-id="' + spanId + '"]');
    if (!container || !row) { return; }
    const cb = container.getBoundingClientRect(), rb = row.getBoundingClientRect();
    const edgeMargin = Math.min(rb.height * 2, cb.height / 4);
    const visibleTop = cb.top + edgeMargin, visibleBottom = cb.bottom - edgeMargin;
    if (rb.top < visibleTop) { container.scrollTop -= (visibleTop - rb.top); }
    else if (rb.bottom > visibleBottom) { container.scrollTop += (rb.bottom - visibleBottom); }
  }
  function selectAdjacent(delta) {
    const vis = visibleFiltered();
    const idx = vis.findIndex((x) => x.spanId === selected);
    if (idx < 0) { return; }
    const next = idx + delta;
    if (next >= 0 && next < vis.length) { selectSpan(vis[next].spanId); scrollSpan(vis[next].spanId); }
  }

  // ---- dock log rendering (rich rows + long-value modal) --------------------
  const LOG_ATTR_STORE = new Map();
  let logAttrKey = 0;
  const LONG_VAL = 240;
  // The drawer's event attrs sit in a much narrower column than the dock's log
  // rows, so they clamp sooner — same store, same modal, just a tighter budget.
  const LONG_VAL_EV = 110;
  // Shared truncate-and-link: clamps `text` and registers the full string under a
  // fresh key so the "view formatted" button can open it in the JSON modal.
  function longValHTML(k, text, cls, limit, opts) {
    const cap = limit || LONG_VAL;
    const force = !!(opts && opts.force);
    const format = (opts && opts.format) || 'json';
    if (text.length <= cap && !force) { return '<span class="v ' + cls + '">' + esc(text) + '</span>'; }
    const key = 'lk' + (logAttrKey++);
    LOG_ATTR_STORE.set(key, { key: k, raw: text, format: format });
    const preview = text.length > cap ? esc(text.slice(0, cap).replace(/\s+$/, '')) + '\u2026 ' : esc(text) + ' ';
    return '<span class="v ' + cls + '">' + preview
      + '<button class="viewfmt" onclick="event.stopPropagation();openJsonModal(\'' + key + '\')">view formatted (' + text.length.toLocaleString() + ' chars)</button></span>';
  }
  const LOG_HEAD = new Set(['event.name', 'event', 'tool', 'session.id']);
  function attrStr(v) { return v === null || v === undefined ? String(v) : (typeof v === 'object' ? JSON.stringify(v) : String(v)); }
  function dockLogHTML(l, spanStartOff) {
    const off = fmtDur(Math.max(0, l.off - spanStartOff));
    const cl = clock(TRACE_START + l.off);
    const ev = l.event ? '<span class="lev">' + esc(l.event) + '</span>' : '';
    const tool = l.tool ? '<span class="ltool">' + esc(l.tool) + '</span>' : '';
    const entries = Object.entries(l.attrs || {}).filter(([k]) => !LOG_HEAD.has(k));
    const hasDetail = entries.length > 0 || !!l.scope;
    let detail = '';
    if (hasDetail) {
      let rows = '';
      if (l.scope) { rows += '<div class="lgd-row"><span class="k">scope</span><span class="v">' + esc(l.scope) + '</span></div>'; }
      entries.forEach(([k, v]) => {
        const text = attrStr(v);
        const cls = typeof v === 'number' ? 'num' : typeof v === 'string' ? 'str' : '';
        // response body on an assistant_response log is markdown prose: always
        // offer "view formatted" and render it through the markdown library.
        const md = l.event === 'assistant_response' && k === 'response';
        rows += '<div class="lgd-row"><span class="k">' + esc(k) + '</span>' + longValHTML(k, text, cls, undefined, md ? { force: true, format: 'markdown' } : undefined) + '</div>';
      });
      detail = '<div class="lgd">' + rows + '</div>';
    }
    const chev = hasDetail ? '<svg class="dkchev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M9 6l6 6-6 6"/></svg>' : '<span class="dkchev"></span>';
    return '<div class="dklog' + (hasDetail ? ' int' : '') + '"' + (hasDetail ? ' onclick="this.classList.toggle(\'exp\')"' : '') + '>' +
      '<div class="dktop">' + chev + '<span class="lt">T+' + off + ' \u00b7 ' + cl + '</span><span class="sev ' + l.sev + '">' + l.sev + '</span>' + ev + tool +
      (l.body ? '<span class="lbody">' + esc(l.body) + '</span>' : '') + '</div>' + detail + '</div>';
  }
  let jsonCurrent = '';
  window.openJsonModal = function (key) {
    const rec = LOG_ATTR_STORE.get(key); if (!rec) { return; }
    $('jmk').textContent = rec.key;
    if (rec.format === 'markdown') {
      jsonCurrent = rec.raw;
      $('jmpre').style.display = 'none';
      $('jmmd').style.display = 'block';
      $('jmmd').innerHTML = (window.marked ? marked.parse(rec.raw) : esc(rec.raw));
    } else {
      let t = rec.raw;
      try { t = JSON.stringify(JSON.parse(rec.raw), null, 2); } catch (e) { /* leave raw */ }
      jsonCurrent = t;
      $('jmmd').style.display = 'none';
      $('jmpre').style.display = 'block';
      $('jmpre').textContent = t;
    }
    $('jscrim').classList.add('show'); $('jmodal').classList.add('show');
  };
  window.closeJsonModal = function () { $('jscrim').classList.remove('show'); $('jmodal').classList.remove('show'); };
  window.copyJson = function () { if (navigator.clipboard) { navigator.clipboard.writeText(jsonCurrent); const b = $('jmcopy'); b.textContent = 'Copied!'; setTimeout(() => { b.textContent = 'Copy'; }, 1200); } };

  // ---- detail panel ---------------------------------------------------------
  let psecSeq = 0;
  function psec(o) {
    const id = 'psec' + (psecSeq++);
    const cls = 'psec' + (o.cls ? ' ' + o.cls : '') + (o.collapsed ? ' collapsed' : '');
    const chev = '<svg class="pchev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M6 9l6 6 6-6"/></svg>';
    const badge = o.badge != null ? ' <span class="ct"' + (o.badgeTitle ? ' title="' + esc(o.badgeTitle) + '"' : '') + '>' + o.badge + '</span>' : '';
    return '<div class="' + cls + '" id="' + id + '">' +
      '<div class="pt' + (o.ptcls ? ' ' + o.ptcls : '') + '" onclick="document.getElementById(\'' + id + '\').classList.toggle(\'collapsed\')">' + chev + (o.icon || '') + o.title + badge + '</div>' +
      '<div class="psecbody">' + o.bodyHtml + '</div>' +
    '</div>';
  }
  function attrRows(attrs) {
    return Object.entries(attrs).map(([k, v]) => {
      const cls = typeof v === 'number' ? 'num' : typeof v === 'boolean' ? 'bool' : v === null ? '' : 'str';
      const disp = v === null ? 'null' : typeof v === 'number' ? v.toLocaleString() : String(v);
      const hl = /prompt$/i.test(k) ? ' prompt' : '';
      // long values (a heredoc full_command, a stderr dump) clamp and hand off to
      // the JSON modal rather than pushing the drawer's grid out of shape
      const vhtml = typeof v === 'string' ? longValHTML(k, disp, cls, LONG_VAL_EV) : '<span class="v ' + cls + '">' + esc(disp) + '</span>';
      return '<div class="attr' + hl + '"><span class="k">' + esc(k) + '</span>' + vhtml + '</div>';
    }).join('');
  }
  function selectSpan(spanId) {
    if (selected === spanId) { closePanel(); return; }
    selected = spanId;
    const s = byId.get(spanId);
    const self = selfTime(s), tok = tokensOf(s);
    const panel = $('panel');
    panel.className = 'card panel';

    const cost = costOfSelectedSpan(s);
    let meta = '<div class="kk">span id</div><div class="vv">' + s.spanId + '</div>' +
      '<div class="kk">kind</div><div class="vv">' + s.kind + '</div>' +
      '<div class="kk">scope</div><div class="vv">' + s.scope + '</div>' +
      '<div class="kk">status</div><div class="vv ' + (s.status === 'error' ? 'bad' : 'ok') + '">' + s.status + '</div>' +
      '<div class="kk">started</div><div class="vv">' + clock(TRACE_START + s.off) + '</div>' +
      '<div class="kk">ended</div><div class="vv">' + clock(TRACE_START + s.off + s.dur) + '</div>' +
      '<div class="kk">duration</div><div class="vv">' + fmtDur(s.dur) + '</div>' +
      (cost > 0 ? '<div class="kk">cost</div><div class="vv cost">' + fmtUsd(cost) + '</div>' : '');
    const selfPct = Math.round((self / s.dur) * 100);
    let col1 = '<div class="kvgrid">' + meta + '</div>';
    if (self !== s.dur) { col1 += '<div class="selftime">self time <b>' + fmtDur(self) + '</b><span class="bar"><i style="width:' + selfPct + '%"></i></span><b>' + selfPct + '%</b></div>'; }
    if (s.status === 'error') {
      const errLog = (logsBySpan.get(spanId) || []).find((l) => l.sev === 'ERROR');
      const errAttrs = {};
      if (s.attrs.exit_code !== undefined) { errAttrs.exit_code = s.attrs.exit_code; }
      if (s.attrs.command) { errAttrs.command = s.attrs.command; }
      if (errLog && errLog.attrs && errLog.attrs.stderr) { errAttrs.stderr = errLog.attrs.stderr; }
      const errBody = '<div class="errmsg">' + esc(s.statusMessage || 'Span ended with an error') + '</div>' +
        (Object.keys(errAttrs).length ? '<div class="attrs">' + attrRows(errAttrs) + '</div>' : '') +
        '<button class="copyerrbtn" onclick="event.stopPropagation();copyErrorDetail(\'' + spanId + '\',this)">Copy error</button>';
      col1 += psec({ title: 'Error', cls: 'errpsec', ptcls: 'errt', bodyHtml: errBody });
    }
    if (tok.total > 0) {
      const cacheEligible = tok.input + tok.cacheCreate + tok.cacheRead;
      const cacheHitRate = cacheEligible > 0 ? Math.round(tok.cacheRead / cacheEligible * 100) : null;
      const cacheReadRow = '<div class="attr cacheread"><span class="k">cache_read</span><span class="v num">' + tok.cacheRead.toLocaleString() + (cacheHitRate === null ? '' : '<span class="rate">' + cacheHitRate + '% hit</span>') + '</span></div>';
      // cost is the meta grid's row above — carrying it here too put one number
      // in two places a few hundred pixels apart.
      const tokAttrs = { input: tok.input, output: tok.output, cache_creation: tok.cacheCreate };
      col1 += psec({ title: 'Tokens', badge: tok.total.toLocaleString(), badgeTitle: tokTotalTip(tok), cls: 'tokpsec',
        bodyHtml: '<div class="attrs">' + attrRows(tokAttrs) + cacheReadRow + '</div>' });
    }

    let col2 = '';
    // strip attrs that duplicate the left meta column (status, duration, kind, scope, …) and the Tokens section
    const LEFT_KEYS = new Set(['status', 'duration', 'kind', 'scope', 'started', 'ended', 'spanid']);
    const isRedundant = (k) => {
      const n = k.replace(/_(ms|us|ns|sec|seconds)$/i, '').split('.').pop().toLowerCase().replace(/\s+/g, '');
      return LEFT_KEYS.has(n) || /tokens?$/i.test(k);
    };
    const isToolAttr = (k) => /tool|path|command/i.test(k);
    const kept = Object.entries(s.attrs).filter(([k]) => !isRedundant(k));
    const toolEntries = kept.filter(([k]) => isToolAttr(k));
    const otherEntries = kept.filter(([k]) => !isToolAttr(k));
    const wrench = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14.7 6.3a4 4 0 0 0-5.2 5.2L3 18l3 3 6.5-6.5a4 4 0 0 0 5.2-5.2l-2.4 2.4-2.1-.6-.6-2.1 2.4-2.4z"/></svg>';
    if (toolEntries.length) { col2 += psec({ title: 'Tool', badge: toolEntries.length, icon: wrench, ptcls: 'toolt', bodyHtml: '<div class="attrs toolattrs">' + attrRows(Object.fromEntries(toolEntries)) + '</div>' }); }
    if (otherEntries.length) { col2 += psec({ title: 'Attributes', badge: otherEntries.length, bodyHtml: '<div class="attrs">' + attrRows(Object.fromEntries(otherEntries)) + '</div>' }); }

    let col3 = '';
    if (s.events && s.events.length) {
      col3 += psec({ title: 'Events', badge: s.events.length,
        bodyHtml: s.events.map((ev) => '<div class="ev"><div class="et"><span class="off">T+' + fmtDur(ev.off - s.off) + '</span><span class="en">' + esc(ev.name) + '</span></div>' +
          (Object.keys(ev.attrs || {}).length ? '<div class="ea">' + Object.entries(ev.attrs).map(([k, v]) => '<span class="k">' + esc(k) + '</span>' + longValHTML(k, attrStr(v), typeof v === 'number' ? 'num' : typeof v === 'string' ? 'str' : '', LONG_VAL_EV)).join('') + '</div>' : '') + '</div>').join('') });
    }
    const slogs = logsBySpan.get(spanId) || [];
    if (slogs.length) {
      col3 += psec({ title: 'Logs', badge: slogs.length, collapsed: true,
        bodyHtml: slogs.map((l) => dockLogHTML(l, s.off)).join('') });
    }

    const vis = visibleFiltered();
    const wIdx = vis.findIndex((x) => x.spanId === spanId);
    const wCount = wIdx >= 0 ? vis.length : 0;
    const navHtml = wCount > 1
      ? '<div class="pnav"><button class="pnavbtn" id="pnavprev"' + (wIdx <= 0 ? ' disabled' : '') + ' title="Previous span (\u2191)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M18 15l-6-6-6 6"/></svg></button><span class="pnavlabel">' + (wIdx + 1) + ' / ' + wCount + '</span><button class="pnavbtn" id="pnavnext"' + (wIdx >= wCount - 1 ? ' disabled' : '') + ' title="Next span (\u2193)"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M6 9l6 6 6-6"/></svg></button></div>'
      : '';
    const cols = '<div class="pcol">' + [col1, col2, col3].filter((c) => c).join('') + '</div>';
    panel.innerHTML =
      '<div class="pgripv" id="pgrip" title="Drag to resize"></div>' +
      '<div class="ph"><div class="pn">' + esc(s.name) + '</div>' + navHtml + '<button class="px" id="panelclose"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<div class="pbody" data-drawer-scroll="">' + cols + '</div>';
    panel.classList.add('show');
    if (panelW) { panel.style.width = panelW + 'px'; }
    $('panelclose').onclick = (e) => { e.stopPropagation(); closePanel(); };
    if (wCount > 1) {
      $('pnavprev').onclick = (e) => { e.stopPropagation(); selectAdjacent(-1); };
      $('pnavnext').onclick = (e) => { e.stopPropagation(); selectAdjacent(1); };
    }
    wireGrip();
    renderWaterfall();
  }
  window.copyErrorDetail = function (spanId, btn) {
    const s = byId.get(spanId);
    const errLog = (logsBySpan.get(spanId) || []).find((l) => l.sev === 'ERROR');
    const lines = [s.name + ' — ' + (s.statusMessage || 'error')];
    if (s.attrs.exit_code !== undefined) { lines.push('exit_code: ' + s.attrs.exit_code); }
    if (s.attrs.command) { lines.push('command: ' + s.attrs.command); }
    if (errLog && errLog.attrs && errLog.attrs.stderr) { lines.push('stderr: ' + errLog.attrs.stderr); }
    if (navigator.clipboard) { navigator.clipboard.writeText(lines.join('\n')); }
    if (btn) { const orig = btn.textContent; btn.textContent = 'Copied!'; setTimeout(() => { btn.textContent = orig; }, 1200); }
  };
  function closePanel() { selected = null; $('panel').classList.remove('show'); renderWaterfall(); }

  function wireGrip() {
    const grip = $('pgrip'); if (!grip) { return; }
    grip.addEventListener('mousedown', (e) => {
      e.preventDefault();
      const panel = $('panel');
      const startX = e.clientX, startW = panel.getBoundingClientRect().width;
      panel.classList.add('resizing');
      const onMove = (ev) => {
        let w = startW + (startX - ev.clientX);
        w = Math.max(340, Math.min(window.innerWidth * 0.62, w));
        panelW = w; panel.style.width = w + 'px';
      };
      const onUp = () => { panel.classList.remove('resizing'); document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); };
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', onUp);
    });
  }

  // ---- legend badge toggles -------------------------------------------------
  // The legend doubles as row-density control: each key hides its badge family
  // from every span row. Nothing is lost by switching one off — the bar colors
  // still carry status and the drawer still states every figure — so a reader
  // chasing one dimension (say cost) can mute the other three and scan a clean
  // column. `error` is deliberately not a toggle: it names the row's status (the
  // red bar), not an optional figure, and is the one thing never worth muting.
  const chipsOff = new Set(JSON.parse(localStorage.getItem('ac-wf-chips-off') || '[]'));
  function syncLegend() {
    [...document.querySelectorAll('.wflegend .lg[data-chip]')].forEach((el) => {
      const off = chipsOff.has(el.dataset.chip);
      el.classList.toggle('off', off);
      el.setAttribute('aria-pressed', String(!off));
      el.title = (off ? 'Show ' : 'Hide ') + el.dataset.label + ' badges on span rows';
    });
  }
  [...document.querySelectorAll('.wflegend .lg[data-chip]')].forEach((el) => {
    const toggle = () => {
      const k = el.dataset.chip;
      if (chipsOff.has(k)) { chipsOff.delete(k); } else { chipsOff.add(k); }
      localStorage.setItem('ac-wf-chips-off', JSON.stringify([...chipsOff]));
      syncLegend(); renderWaterfall();
    };
    el.onclick = toggle;
    el.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } };
  });
  syncLegend();

  // ---- expand / collapse ----------------------------------------------------
  const allParents = () => SPANS.filter((s) => (childrenOf.get(s.spanId) || []).length).map((s) => s.spanId);
  function syncExpandLabel() { const any = allParents().some((id) => collapsed.has(id)); $('expandlabel').textContent = any ? 'Expand all' : 'Collapse all'; }
  window.toggleAll = function () { const any = allParents().some((id) => collapsed.has(id)); if (any) { collapsed.clear(); } else { allParents().forEach((id) => collapsed.add(id)); } syncExpandLabel(); renderWaterfall(); };

  // ---- error-first nav ------------------------------------------------------
  const errorSpans = SPANS.filter((s) => s.status === 'error').map((s) => s.spanId);
  let errIdx = -1;
  window.nextError = function () { if (!errorSpans.length) { return; } errIdx = (errIdx + 1) % errorSpans.length; const id = errorSpans[errIdx]; selectSpan(id); scrollSpan(id); };

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { closeJsonModal(); return; }
    if (!selected) { return; }
    if (e.altKey || e.ctrlKey || e.metaKey) { return; }
    const target = e.target;
    const tag = (target.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'textarea' || tag === 'select') { return; }
    if (target.isContentEditable) { return; }
    if (target.closest && target.closest('.jmodal, [data-drawer-scroll]')) { return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); selectAdjacent(-1); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); selectAdjacent(1); }
  });

  // ---- mode -----------------------------------------------------------------
  const sun = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="4.5"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M19.1 4.9l-1.8 1.8M6.7 17.3l-1.8 1.8"/></svg>';
  const moon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>';
  window.toggleMode = function () { applyMode(document.documentElement.getAttribute('data-mode') === 'dark' ? 'light' : 'dark'); };
  function applyMode(m) { document.documentElement.setAttribute('data-mode', m); $('modebtn').innerHTML = (m === 'dark' ? sun : moon); $('modeflag').textContent = (m === 'dark' ? 'Dark mode' : 'Light mode'); try { localStorage.setItem('aurora-traces-mode', m); } catch (e) { /* noop */ } }

  // ---- chip hover cards -----------------------------------------------------
  const tipEl = document.createElement('div');
  tipEl.className = 'rowtip';
  tipEl.hidden = true;
  document.body.appendChild(tipEl);
  function showTip(target) {
    if (!target._tip) { return; }
    tipEl.innerHTML = target._tip;
    tipEl.hidden = false;
    const r = target.getBoundingClientRect(), t = tipEl.getBoundingClientRect();
    tipEl.style.left = Math.max(8, Math.min(r.left + r.width / 2 - t.width / 2, window.innerWidth - t.width - 8)) + 'px';
    tipEl.style.top = (r.top - t.height - 8 < 8 ? r.bottom + 8 : r.top - t.height - 8) + 'px';
  }
  const hideTip = () => { tipEl.hidden = true; };
  document.addEventListener('mouseover', (e) => {
    const t = e.target.closest ? e.target.closest('[data-tip]') : null;
    if (t) { showTip(t); } else if (!tipEl.hidden) { hideTip(); }
  });
  document.addEventListener('mouseleave', hideTip, true);
  document.addEventListener('scroll', hideTip, true);

  // ---- init -----------------------------------------------------------------
  let mode = 'light';
  try { mode = localStorage.getItem('aurora-traces-mode') || 'light'; } catch (e) { /* noop */ }
  applyMode(mode);
  renderHeader();
  renderSummary();
  renderAxis();
  renderMinimap();
  renderWaterfall();
  if (!errorSpans.length) { $('nexterr').classList.add('hide'); }
  // error-first: open the first error span on load for a failed trace
  if (errorSpans.length) { errIdx = 0; selectSpan(errorSpans[0]); setTimeout(() => scrollSpan(errorSpans[0]), 30); }
})();
