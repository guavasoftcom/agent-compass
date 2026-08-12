import type { AreaTrendSeries } from './AreaTrendChart';

export const PLOT_PADDING = { left: 52, right: 16, top: 16, bottom: 44 };

// Fine "nice-max" ladder so the data peak fills most of the plot height
// (a coarse 1/2/5/10 ladder leaves big dead space above the curve).
const NICE_STEPS = [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];
export const niceMax = (value: number): number => {
  if (value <= 0) {
    return 1;
  }
  const powerOfTen = 10 ** Math.floor(Math.log10(value));
  const mantissa = value / powerOfTen;
  const step = NICE_STEPS.find((candidate) => mantissa <= candidate) ?? 10;
  return step * powerOfTen;
};

export interface Layer {
  seriesIndex: number;
  label: string;
  color: string;
  /** Lower edge of this layer's band (cumulative floor when stacked; axis floor when not). */
  lower: number[];
  /** Drawn top of this layer (cumulative top when stacked; the series value when not). */
  upper: number[];
}

export interface LayersAndYDomain {
  layers: Layer[];
  yFloor: number;
  yCeiling: number;
  yTicks: number[];
}

/**
 * Builds the visible stacked/unstacked layers and the y-axis domain from raw
 * series data. Pure function (no React) so `AreaTrendChart` can wrap it in a
 * `useMemo` keyed on its actual inputs, and so this exact geometry is directly
 * unit-testable without a DOM renderer.
 */
export const buildLayersAndYDomain = (
  series: AreaTrendSeries[],
  activeStates: boolean[] | undefined,
  bucketCount: number,
  stacked: boolean,
  isLogarithmic: boolean,
  /**
   * Sparse whole-number series (drawn as bars). Steps the axis by whole numbers
   * and adds a bucket of headroom above the peak. Ignored on a log scale.
   */
  isDiscrete: boolean = false,
): LayersAndYDomain => {
  const isSeriesActive = (seriesIndex: number): boolean =>
    activeStates ? activeStates[seriesIndex] !== false : true;

  // Build the visible layers. Stacked → cumulative bands; unstacked → each series
  // is its own line, its band filled down to the shared baseline (set below once
  // the domain is known).
  const builtLayers: Layer[] = [];
  const runningTotals = new Array(bucketCount).fill(0);
  series.forEach((seriesItem, seriesIndex) => {
    if (!isSeriesActive(seriesIndex)) {
      return;
    }
    if (stacked) {
      const lower = runningTotals.slice();
      const upper = runningTotals.map(
        (total, i) => total + (seriesItem.data[i] ?? 0),
      );
      builtLayers.push({
        seriesIndex,
        label: seriesItem.label,
        color: seriesItem.color,
        lower,
        upper,
      });
      for (let i = 0; i < bucketCount; i += 1) {
        runningTotals[i] = upper[i];
      }
    } else {
      builtLayers.push({
        seriesIndex,
        label: seriesItem.label,
        color: seriesItem.color,
        lower: [],
        upper: (seriesItem.data ?? [])
          .slice(0, bucketCount)
          .map((value) => value ?? 0),
      });
    }
  });

  // --- Y domain --------------------------------------------------------------
  let floor: number;
  let ceiling: number;
  let ticks: number[];

  if (isLogarithmic) {
    let minPositive = Infinity;
    let maxValue = 1;
    for (const layer of builtLayers) {
      for (const value of layer.upper) {
        if (value > 0 && value < minPositive) {
          minPositive = value;
        }
        if (value > maxValue) {
          maxValue = value;
        }
      }
    }
    if (!Number.isFinite(minPositive)) {
      minPositive = 1;
    }
    // Floor a decade below the smallest positive value; ceiling at/above the max.
    const floorExponent = Math.floor(Math.log10(minPositive)) - 1;
    const ceilingExponent = Math.max(
      floorExponent + 1,
      Math.ceil(Math.log10(maxValue)),
    );
    floor = 10 ** floorExponent;
    ceiling = 10 ** ceilingExponent;
    ticks = [];
    for (
      let exponent = floorExponent;
      exponent <= ceilingExponent;
      exponent += 1
    ) {
      ticks.push(10 ** exponent);
    }
  } else {
    floor = 0;
    let peakValue = 1;
    for (const layer of builtLayers) {
      for (const value of layer.upper) {
        if (value > peakValue) {
          peakValue = value;
        }
      }
    }

    if (isDiscrete) {
      // A counter that only ever reaches a handful deserves honest ticks: five
      // evenly-spaced fractions of a ceiling of 1 all round to "1, 1, 1, 0, 0".
      // Step by whole numbers instead, one tick per integer. The peak is rounded
      // because a stacked split reconstitutes it through floating-point shares
      // (0.73 + 0.27 of an integer bucket), so it can arrive a hair off.
      // One bucket of headroom keeps the busiest bar off the ceiling and puts a
      // labelled tick above the peak.
      const peakInteger = Math.max(1, Math.round(peakValue));
      ceiling = peakInteger + 1;
      ticks = Array.from({ length: ceiling + 1 }, (_, i) => i);
    } else {
      ceiling = niceMax(peakValue);
      ticks = Array.from({ length: 6 }, (_, i) => (ceiling * i) / 5);
    }
  }

  // Shared baseline for unstacked bands (axis floor).
  if (!stacked) {
    for (const layer of builtLayers) {
      layer.lower = layer.upper.map(() => floor);
    }
  }

  return { layers: builtLayers, yFloor: floor, yCeiling: ceiling, yTicks: ticks };
};

export interface CoordinateFns {
  xCoordinateAt: (i: number) => number;
  yCoordinateAt: (value: number) => number;
}

/** Pixel-coordinate mappers for a given plot size + y-domain. Pure, no React. */
export const buildCoordinateFns = (
  bucketCount: number,
  plotWidth: number,
  plotHeight: number,
  isLogarithmic: boolean,
  yFloor: number,
  yCeiling: number,
): CoordinateFns => {
  const xCoordinateAt = (i: number) =>
    bucketCount <= 1
      ? PLOT_PADDING.left
      : PLOT_PADDING.left + (i * plotWidth) / (bucketCount - 1);
  const yCoordinateAt = (value: number): number => {
    if (isLogarithmic) {
      const clamped = Math.max(value, yFloor);
      const fraction =
        (Math.log10(clamped) - Math.log10(yFloor)) /
        (Math.log10(yCeiling) - Math.log10(yFloor));
      return PLOT_PADDING.top + (1 - fraction) * plotHeight;
    }
    return PLOT_PADDING.top + (1 - value / yCeiling) * plotHeight;
  };
  return { xCoordinateAt, yCoordinateAt };
};

export interface PathStrings {
  bandPaths: string[];
  linePaths: string[];
}

/** Band/line SVG path strings for each layer. Pure, no React. */
export const buildPathStrings = (
  layers: Layer[],
  coordinateFns: CoordinateFns,
): PathStrings => {
  const bandPath = (layer: Layer): string => {
    // No points → empty path (avoids an invalid lone "Z" during loading).
    if (layer.upper.length === 0) {
      return '';
    }
    let pathData = layer.upper
      .map(
        (value, i) =>
          `${i ? 'L' : 'M'}${coordinateFns.xCoordinateAt(i)},${coordinateFns.yCoordinateAt(value)}`,
      )
      .join('');
    for (let i = layer.lower.length - 1; i >= 0; i -= 1) {
      pathData += `L${coordinateFns.xCoordinateAt(i)},${coordinateFns.yCoordinateAt(layer.lower[i])}`;
    }
    return `${pathData}Z`;
  };

  const linePath = (layer: Layer): string =>
    layer.upper
      .map(
        (value, i) =>
          `${i ? 'L' : 'M'}${coordinateFns.xCoordinateAt(i)},${coordinateFns.yCoordinateAt(value)}`,
      )
      .join('');

  return {
    bandPaths: layers.map((layer) => bandPath(layer)),
    linePaths: layers.map((layer) => linePath(layer)),
  };
};
