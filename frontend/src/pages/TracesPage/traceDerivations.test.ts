import { describe, expect, it } from 'vitest';
import { formatUsd, isToolCallSpan } from './traceDerivations';

// Real Claude Code emits three spans per tool call — the `claude_code.tool` call
// span plus two children — so the tile this feeds counted 0 for months while an
// unprefixed `/^(tool\.|mcp\.)/` matched only the sample store's names.
describe('isToolCallSpan', () => {
  it('counts the real claude_code.tool call span', () => {
    expect(isToolCallSpan('claude_code.tool')).toBe(true);
  });

  it('does not count the sub-spans nested under a tool call', () => {
    expect(isToolCallSpan('claude_code.tool.execution')).toBe(false);
    expect(isToolCallSpan('claude_code.tool.blocked_on_user')).toBe(false);
  });

  it('counts one per call for a real tool-call span group', () => {
    const spanNames = [
      'claude_code.tool',
      'claude_code.tool.execution',
      'claude_code.tool.blocked_on_user',
    ];
    expect(spanNames.filter(isToolCallSpan)).toHaveLength(1);
  });

  it('counts the sample store\'s unprefixed tool and mcp names', () => {
    expect(isToolCallSpan('tool.execute')).toBe(true);
    expect(isToolCallSpan('tool.Read')).toBe(true);
    expect(isToolCallSpan('mcp.tool.call')).toBe(true);
    expect(isToolCallSpan('mcp.connect')).toBe(true);
  });

  it('ignores non-tool spans', () => {
    expect(isToolCallSpan('claude_code.llm_request')).toBe(false);
    expect(isToolCallSpan('claude_code.interaction')).toBe(false);
    expect(isToolCallSpan('model.completion')).toBe(false);
  });

  it('does not match a name that merely starts with the word tool', () => {
    expect(isToolCallSpan('toolbar.render')).toBe(false);
  });

  it('handles a missing span name', () => {
    expect(isToolCallSpan(null)).toBe(false);
    expect(isToolCallSpan(undefined)).toBe(false);
    expect(isToolCallSpan('')).toBe(false);
  });
});

// A page of cheap Haiku traces used to render as a column of "$0.000" —
// 3-decimal rounding made real sub-millidollar spend indistinguishable from
// the zero/absent "—" state despite the sibling `costUsd > 0` check styling
// it as a real figure.
describe('formatUsd', () => {
  it('renders zero and absent cost as the em dash', () => {
    expect(formatUsd(0)).toBe('—');
  });

  it('renders real sub-millidollar cost as "<$0.001" instead of rounding to zero', () => {
    expect(formatUsd(0.0002)).toBe('<$0.001');
  });

  it('renders exactly half a mil-dollar as the rounded $0.001, not the placeholder', () => {
    expect(formatUsd(0.0005)).toBe('$0.001');
  });

  it('renders sub-dollar cost with 3 decimals', () => {
    expect(formatUsd(0.001)).toBe('$0.001');
    expect(formatUsd(0.5)).toBe('$0.500');
  });

  it('renders dollar-plus cost with 2 decimals', () => {
    expect(formatUsd(1)).toBe('$1.00');
    expect(formatUsd(12.34)).toBe('$12.34');
  });
});
