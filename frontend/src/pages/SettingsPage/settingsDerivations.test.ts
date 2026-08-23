import { describe, expect, it } from 'vitest';
import {
  daysUntilSize,
  filterConfigurationGroups,
  ingestFreshness,
  matchesConfigurationQuery,
  retentionSpanDays,
  sqlMirroringExplanation,
  sqlMirroringLabel,
  storageSharePercent,
} from './settingsDerivations';
import type {
  ConfigurationEntry,
  ConfigurationGroup,
  PurgeTableEstimate,
  SignalIngest,
  TableStorage,
} from './settingsTypes';

const NOW = new Date('2026-08-23T12:00:00Z').getTime();

const signal = (overrides: Partial<SignalIngest> = {}): SignalIngest => ({
  signal: 'metrics',
  tableName: 'metric_points',
  newestTimestamp: '2026-08-23T11:59:00Z',
  newestReceivedAt: '2026-08-23T11:59:00Z',
  rowsLastHour: 10,
  rowsLastDay: 100,
  rowsLastWeek: 1000,
  nameCardinality: 8,
  nameCardinalityLabel: 'metric_name',
  seriesCardinality: 4454,
  ...overrides,
});

const table = (overrides: Partial<TableStorage> = {}): TableStorage => ({
  tableName: 'log_records',
  rowCount: 1000,
  heapBytes: 100,
  indexBytes: 50,
  toastBytes: 850,
  totalBytes: 1000,
  oldestTimestamp: '2026-05-22T00:00:00Z',
  newestTimestamp: '2026-08-23T00:00:00Z',
  rowsLastSevenDays: 70,
  estimatedBytesPerDay: 10,
  ...overrides,
});

const entry = (overrides: Partial<ConfigurationEntry> = {}): ConfigurationEntry => ({
  propertyName: 'tuning.api-request-event-name',
  value: 'api_request',
  overridden: false,
  sqlMirroring: 'MIRRORED',
  mirroredIn: ['V14', 'V19'],
  description: 'event.name of the per-request log',
  ...overrides,
});

describe('ingestFreshness', () => {
  it('reads a row received minutes ago as live', () => {
    expect(ingestFreshness(signal({ newestReceivedAt: '2026-08-23T11:52:00Z' }), NOW)).toBe('live');
  });

  it('reads nothing received for over 15 minutes as delayed', () => {
    expect(ingestFreshness(signal({ newestReceivedAt: '2026-08-23T11:00:00Z' }), NOW)).toBe(
      'delayed',
    );
  });

  it('reads nothing received for over a day as stale', () => {
    expect(ingestFreshness(signal({ newestReceivedAt: '2026-08-21T12:00:00Z' }), NOW)).toBe('stale');
  });

  it('reads a signal that has never been received as empty', () => {
    expect(ingestFreshness(signal({ newestReceivedAt: null }), NOW)).toBe('empty');
  });

  it('treats the boundaries as inclusive of the friendlier bucket', () => {
    expect(ingestFreshness(signal({ newestReceivedAt: '2026-08-23T11:45:00Z' }), NOW)).toBe('live');
    expect(ingestFreshness(signal({ newestReceivedAt: '2026-08-22T12:00:00Z' }), NOW)).toBe(
      'delayed',
    );
  });
});

describe('storageSharePercent', () => {
  it('reports a table as its share of the database total', () => {
    expect(storageSharePercent(table({ totalBytes: 250 }), 1000)).toBe(25);
  });

  it('returns zero rather than dividing by an unknown total', () => {
    expect(storageSharePercent(table(), 0)).toBe(0);
    expect(storageSharePercent(table(), -1)).toBe(0);
  });
});

describe('retentionSpanDays', () => {
  it('floors the span between the oldest and newest row', () => {
    expect(
      retentionSpanDays(
        table({
          oldestTimestamp: '2026-08-01T00:00:00Z',
          newestTimestamp: '2026-08-23T18:00:00Z',
        }),
      ),
    ).toBe(22);
  });

  it('returns null for an empty table', () => {
    expect(retentionSpanDays(table({ oldestTimestamp: null, newestTimestamp: null }))).toBeNull();
  });

  it('returns null when every row shares one instant', () => {
    expect(
      retentionSpanDays(
        table({
          oldestTimestamp: '2026-08-23T00:00:00Z',
          newestTimestamp: '2026-08-23T00:00:00Z',
        }),
      ),
    ).toBeNull();
  });
});

describe('daysUntilSize', () => {
  it('projects the days remaining at the current rate', () => {
    expect(daysUntilSize(1000, 3000, 100)).toBe(20);
  });

  it('returns null when nothing is growing', () => {
    expect(daysUntilSize(1000, 3000, 0)).toBeNull();
  });

  it('returns null when the ceiling is already behind us', () => {
    expect(daysUntilSize(4000, 3000, 100)).toBeNull();
  });
});

describe('matchesConfigurationQuery', () => {
  it('matches an empty query against everything', () => {
    expect(matchesConfigurationQuery(entry(), '   ')).toBe(true);
  });

  it('matches on the property name, value, and description, case-insensitively', () => {
    expect(matchesConfigurationQuery(entry(), 'API-REQUEST')).toBe(true);
    expect(matchesConfigurationQuery(entry(), 'api_request')).toBe(true);
    expect(matchesConfigurationQuery(entry(), 'per-request log')).toBe(true);
  });

  it('rejects a query that appears nowhere', () => {
    expect(matchesConfigurationQuery(entry(), 'zzz')).toBe(false);
  });
});

describe('filterConfigurationGroups', () => {
  const groups: ConfigurationGroup[] = [
    {
      name: 'LLM requests & cost',
      description: 'api_request logs',
      entries: [entry(), entry({ propertyName: 'tuning.model-attribute', value: 'model' })],
    },
    {
      name: 'Report tuning',
      description: 'markdown report',
      entries: [
        entry({
          propertyName: 'tuning.externally-determined-tools',
          value: 'Agent, WebSearch',
          description: 'tools the agent cannot tune',
          sqlMirroring: 'NOT_MIRRORED',
          mirroredIn: [],
        }),
      ],
    },
  ];

  it('keeps every group when the query is blank', () => {
    expect(filterConfigurationGroups(groups, '')).toHaveLength(2);
  });

  it('drops groups left with no matching entries', () => {
    const filtered = filterConfigurationGroups(groups, 'model');
    expect(filtered).toHaveLength(1);
    expect(filtered[0].name).toBe('LLM requests & cost');
    expect(filtered[0].entries).toHaveLength(1);
  });

  it('returns nothing when the query matches no entry at all', () => {
    expect(filterConfigurationGroups(groups, 'nonexistent')).toHaveLength(0);
  });

  it('leaves the source groups untouched', () => {
    filterConfigurationGroups(groups, 'model');
    expect(groups[0].entries).toHaveLength(2);
  });
});

describe('purge preview shape', () => {
  const estimate = (overrides: Partial<PurgeTableEstimate> = {}): PurgeTableEstimate => ({
    tableName: 'metric_points',
    timestampColumn: 'timestamp',
    rowsToDelete: 2244556,
    preservedRows: 4474,
    totalRows: 4617041,
    sharePercent: 48.6,
    estimatedReclaimableBytes: 2734517438,
    statement: 'DELETE FROM metric_points AS candidate ...',
    ...overrides,
  });

  it('reports preserved stream markers separately from the delete count', () => {
    const metricPoints = estimate();

    expect(metricPoints.preservedRows).toBeGreaterThan(0);
    expect(metricPoints.rowsToDelete + metricPoints.preservedRows).toBeLessThanOrEqual(
      metricPoints.totalRows,
    );
  });

  it('carries no preserved rows for the plain event tables', () => {
    expect(estimate({ tableName: 'log_records', preservedRows: 0 }).preservedRows).toBe(0);
    expect(estimate({ tableName: 'spans', preservedRows: 0 }).preservedRows).toBe(0);
  });
});

describe('sqlMirroringLabel and sqlMirroringExplanation', () => {
  it('labels each mirroring state distinctly', () => {
    expect(sqlMirroringLabel('MIRRORED')).toBe('Needs a migration');
    expect(sqlMirroringLabel('SHARED_LITERAL')).toBe('Shares a SQL literal');
    expect(sqlMirroringLabel('NOT_MIRRORED')).toBe('Safe to override');
  });

  it('names the migrations a mirrored property is duplicated into', () => {
    expect(sqlMirroringExplanation('MIRRORED', ['V14', 'V19'])).toContain('V14, V19');
    expect(sqlMirroringExplanation('MIRRORED', ['V14'])).toContain('without a new migration');
  });

  it('explains a shared literal as diverging rather than breaking', () => {
    const explanation = sqlMirroringExplanation('SHARED_LITERAL', ['V6']);
    expect(explanation).toContain('V6');
    expect(explanation).toContain('does not invalidate');
  });

  it('explains an unmirrored property as safe', () => {
    expect(sqlMirroringExplanation('NOT_MIRRORED', [])).toContain('bind parameters');
  });
});
