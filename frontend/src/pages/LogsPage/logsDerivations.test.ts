import { describe, expect, it } from 'vitest';
import type { LogRow } from '../../api';
import { eventNameOf, toolNameOf } from './logsDerivations';

// Telemetry attributes are attacker-influenceable (unauthenticated OTLP
// ingest) and the backend preserves OTLP kvlist/array structure into jsonb, so
// `event.name` / `tool_name` can arrive as an object or array instead of a
// string. eventNameOf/toolNameOf must guard at runtime — a bare `as string`
// cast would let a non-string value reach `<Tag>{event}</Tag>` in LogStream
// and crash the whole page (no ErrorBoundary existed before this fix).
const baseRow = (attributes: Record<string, unknown> | null): LogRow => ({
  id: 1,
  timestamp: '2026-06-15T12:00:00.000Z',
  severityNumber: null,
  severityText: null,
  body: 'hello',
  scopeName: 'claude_code',
  traceId: null,
  spanId: null,
  attributes,
  resourceAttributes: null,
});

describe('eventNameOf', () => {
  it('returns the string value when event.name is a string', () => {
    const row = baseRow({ 'event.name': 'tool_decision' });
    expect(eventNameOf(row)).toBe('tool_decision');
  });

  it('returns null when event.name is an object (poisoned OTLP kvlist)', () => {
    const row = baseRow({ 'event.name': { malicious: 'payload' } });
    expect(eventNameOf(row)).toBeNull();
  });

  it('returns null when event.name is an array', () => {
    const row = baseRow({ 'event.name': ['a', 'b'] });
    expect(eventNameOf(row)).toBeNull();
  });

  it('returns null when attributes is null or the key is absent', () => {
    expect(eventNameOf(baseRow(null))).toBeNull();
    expect(eventNameOf(baseRow({}))).toBeNull();
  });
});

describe('toolNameOf', () => {
  it('returns the string value when tool_name is a string', () => {
    const row = baseRow({ tool_name: 'Bash' });
    expect(toolNameOf(row)).toBe('Bash');
  });

  it('returns null when tool_name is an object (poisoned OTLP kvlist)', () => {
    const row = baseRow({ tool_name: { nested: true } });
    expect(toolNameOf(row)).toBeNull();
  });

  it('returns null when tool_name is an array', () => {
    const row = baseRow({ tool_name: [1, 2, 3] });
    expect(toolNameOf(row)).toBeNull();
  });

  it('returns null when attributes is null or the key is absent', () => {
    expect(toolNameOf(baseRow(null))).toBeNull();
    expect(toolNameOf(baseRow({}))).toBeNull();
  });
});
