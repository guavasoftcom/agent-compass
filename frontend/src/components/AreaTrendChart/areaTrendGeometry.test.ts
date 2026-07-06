import { describe, expect, it } from 'vitest';
import {
  buildCoordinateFns,
  buildLayersAndYDomain,
  buildPathStrings,
  niceMax,
} from './areaTrendGeometry';
import type { AreaTrendSeries } from './AreaTrendChart';

// AreaTrendChart memoizes these three computations (layers/y-domain, coordinate
// mappers, path strings) on props that exclude `hover`, so a mouse-move never
// invalidates them. This file exercises the extracted pure functions directly —
// there's no jsdom/testing-library in this repo (see
// src/components/ErrorBoundary/ErrorBoundary.test.tsx), so a full component
// render isn't possible without adding a new dependency; testing the geometry
// as pure functions gives the same "path output unchanged" coverage without one.

const seriesA: AreaTrendSeries = { label: 'a', data: [1, 2, 3], color: '#111' };
const seriesB: AreaTrendSeries = { label: 'b', data: [4, 5, 6], color: '#222' };

describe('buildLayersAndYDomain', () => {
  it('stacks series into cumulative bands, first series at the bottom', () => {
    const { layers } = buildLayersAndYDomain([seriesA, seriesB], undefined, 3, true, false);

    expect(layers).toHaveLength(2);
    expect(layers[0].lower).toEqual([0, 0, 0]);
    expect(layers[0].upper).toEqual([1, 2, 3]);
    expect(layers[1].lower).toEqual([1, 2, 3]);
    expect(layers[1].upper).toEqual([5, 7, 9]);
  });

  it('drops inactive series from the layer list', () => {
    const { layers } = buildLayersAndYDomain([seriesA, seriesB], [true, false], 3, true, false);

    expect(layers).toHaveLength(1);
    expect(layers[0].seriesIndex).toBe(0);
  });

  it('gives unstacked layers an independent line and a shared axis-floor baseline', () => {
    const { layers, yFloor } = buildLayersAndYDomain([seriesA, seriesB], undefined, 3, false, false);

    expect(layers[0].upper).toEqual([1, 2, 3]);
    expect(layers[1].upper).toEqual([4, 5, 6]);
    expect(layers[0].lower).toEqual([yFloor, yFloor, yFloor]);
    expect(layers[1].lower).toEqual([yFloor, yFloor, yFloor]);
  });

  it('computes a linear y-ceiling that comfortably fits the peak value', () => {
    const { yFloor, yCeiling } = buildLayersAndYDomain([seriesA, seriesB], undefined, 3, true, false);

    expect(yFloor).toBe(0);
    // peak stacked value is 9 (last bucket); niceMax(9) rounds up to 10.
    expect(yCeiling).toBe(10);
  });

  it('computes a log floor a decade below the smallest positive value', () => {
    const sparse: AreaTrendSeries = { label: 'sparse', data: [0, 5, 500], color: '#333' };
    const { yFloor, yCeiling } = buildLayersAndYDomain([sparse], undefined, 3, false, true);

    // smallest positive value is 5 → floorExponent = floor(log10(5)) - 1 = -1 → 10^-1.
    expect(yFloor).toBeCloseTo(0.1);
    expect(yCeiling).toBeGreaterThanOrEqual(500);
  });

  it('falls back to a floor of 1 when every value is zero (no positive minimum)', () => {
    const empty: AreaTrendSeries = { label: 'empty', data: [0, 0, 0], color: '#444' };
    const { yFloor } = buildLayersAndYDomain([empty], undefined, 3, false, true);

    expect(Number.isFinite(yFloor)).toBe(true);
  });
});

describe('buildCoordinateFns', () => {
  it('places a single bucket at the left padding edge', () => {
    const { xCoordinateAt } = buildCoordinateFns(1, 400, 200, false, 0, 10);
    expect(xCoordinateAt(0)).toBe(52);
  });

  it('spreads multiple buckets evenly across the plot width', () => {
    const { xCoordinateAt } = buildCoordinateFns(3, 400, 200, false, 0, 10);
    expect(xCoordinateAt(0)).toBe(52);
    expect(xCoordinateAt(2)).toBe(52 + 400);
  });

  it('maps the linear y-ceiling to the top of the plot and 0 to the baseline', () => {
    const { yCoordinateAt } = buildCoordinateFns(3, 400, 200, false, 0, 10);
    expect(yCoordinateAt(10)).toBe(16);
    expect(yCoordinateAt(0)).toBe(16 + 200);
  });

  it('clamps log values below the floor instead of producing -Infinity', () => {
    const { yCoordinateAt } = buildCoordinateFns(3, 400, 200, true, 1, 100);
    expect(yCoordinateAt(0)).toBe(yCoordinateAt(1));
  });
});

describe('buildPathStrings', () => {
  it('returns an empty band path for a layer with no points (loading guard)', () => {
    const coordinateFns = buildCoordinateFns(0, 400, 200, false, 0, 10);
    const { bandPaths } = buildPathStrings(
      [{ seriesIndex: 0, label: 'a', color: '#111', lower: [], upper: [] }],
      coordinateFns,
    );
    expect(bandPaths).toEqual(['']);
  });

  it('builds a closed band path tracing the upper edge then the lower edge in reverse', () => {
    const coordinateFns = buildCoordinateFns(2, 400, 200, false, 0, 10);
    const { bandPaths, linePaths } = buildPathStrings(
      [{ seriesIndex: 0, label: 'a', color: '#111', lower: [0, 0], upper: [5, 10] }],
      coordinateFns,
    );

    expect(bandPaths[0]).toBe(
      `M${coordinateFns.xCoordinateAt(0)},${coordinateFns.yCoordinateAt(5)}` +
        `L${coordinateFns.xCoordinateAt(1)},${coordinateFns.yCoordinateAt(10)}` +
        `L${coordinateFns.xCoordinateAt(1)},${coordinateFns.yCoordinateAt(0)}` +
        `L${coordinateFns.xCoordinateAt(0)},${coordinateFns.yCoordinateAt(0)}Z`,
    );
    expect(linePaths[0]).toBe(
      `M${coordinateFns.xCoordinateAt(0)},${coordinateFns.yCoordinateAt(5)}` +
        `L${coordinateFns.xCoordinateAt(1)},${coordinateFns.yCoordinateAt(10)}`,
    );
  });
});

describe('niceMax', () => {
  it('returns 1 for non-positive input', () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(-5)).toBe(1);
  });

  it('rounds up to the next step on the nice-max ladder', () => {
    expect(niceMax(9)).toBe(10);
    expect(niceMax(1.1)).toBe(1.2);
  });
});
