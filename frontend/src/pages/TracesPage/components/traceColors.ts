import { auroraColors } from '../../../theme/colors';

// Fixed service hues — chosen to read on both Aurora Light and Dark. These are the
// service categories Claude Code actually emits as spans: the session/interaction
// root, tool executions, and model (LLM) requests. (MCP calls and subagent runs are
// themselves `claude_code.tool*` spans, and permission decisions are log events, not
// spans — so they don't get their own span hue.)
const SERVICE_HUE: Record<string, string> = {
  'claude_code.session': auroraColors.violetLight,
  'claude_code.tools': auroraColors.cyanBright,
  'claude_code.models': auroraColors.pinkBright,
};

export const serviceColor = (service: string): string => SERVICE_HUE[service] ?? auroraColors.mutedSlate;

// Map a span to a service hue from its NAME, not its OTel scope. Real Claude Code
// spans all share one scope (com.anthropic.claude_code.tracing), so the operation
// lives in the name ("claude_code.tool.execution", "claude_code.llm_request",
// "claude_code.interaction"). Strip the `claude_code.` prefix, then match — which
// also handles the bare operation names the sample store uses ("tool.execute",
// "model.completion"). Mirrors serviceOf() in traceDerivations.ts.
export const spanColor = (spanName: string | null | undefined): string => {
  if (!spanName) {
    return SERVICE_HUE['claude_code.session'];
  }
  const operation = spanName.toLowerCase().replace(/^claude_code\./, '');
  if (operation.startsWith('tool')) {
    return SERVICE_HUE['claude_code.tools'];
  }
  if (operation.startsWith('llm') || operation.startsWith('model')) {
    return SERVICE_HUE['claude_code.models'];
  }
  return SERVICE_HUE['claude_code.session'];
};

// Legend for the minimap's per-operation hues — kept in lockstep with SERVICE_HUE
// and the categories spanColor() assigns (error spans override to red separately).
export const SERVICE_LEGEND: ReadonlyArray<{ label: string; color: string }> = [
  { label: 'Session', color: SERVICE_HUE['claude_code.session'] },
  { label: 'Tools', color: SERVICE_HUE['claude_code.tools'] },
  { label: 'Model', color: SERVICE_HUE['claude_code.models'] },
];
