import type { Theme } from '@mui/material';
import type { TraceStatus } from '../tracesApi';

// Fixed service hues — chosen to read on both Aurora Light and Dark (the same
// set the mockup uses). Operation dots inherit their service's hue.
const SERVICE_HUE: Record<string, string> = {
  'claude_code.session': '#8b5cff',
  'claude_code.tools': '#13b6e6',
  'claude_code.models': '#ff6ad5',
  'mcp.client': '#e6952b',
  'claude_code.subagents': '#1f9d6b',
  'claude_code.permissions': '#9a93b6',
};

export const serviceColor = (service: string): string => SERVICE_HUE[service] ?? '#938cae';

// Map a span to a service hue from its NAME, not its OTel scope. Real Claude Code
// spans all share one scope (com.anthropic.claude_code.tracing), so the operation
// lives in the name ("claude_code.tool.execution", "claude_code.llm_request", …).
// Also handles the bare operation names used in sample data ("tool.Read",
// "model.completion", "mcp.github.search"). Mirrors the mockup's per-service tints.
export const spanColor = (spanName: string | null | undefined): string => {
  if (!spanName) {
    return SERVICE_HUE['claude_code.session'];
  }
  const n = spanName.toLowerCase().replace(/^claude_code\./, '');
  if (n.startsWith('mcp')) {
    return SERVICE_HUE['mcp.client'];
  }
  if (n.startsWith('subagent')) {
    return SERVICE_HUE['claude_code.subagents'];
  }
  if (n.startsWith('tool')) {
    return SERVICE_HUE['claude_code.tools'];
  }
  if (n.startsWith('model') || n.startsWith('llm') || n.startsWith('api') || n.includes('completion')) {
    return SERVICE_HUE['claude_code.models'];
  }
  if (n.startsWith('permission')) {
    return SERVICE_HUE['claude_code.permissions'];
  }
  return SERVICE_HUE['claude_code.session'];
};

export const statusColor = (theme: Theme, status: TraceStatus): string =>
  status === 'error' ? theme.palette.error.main : theme.palette.primary.main;
