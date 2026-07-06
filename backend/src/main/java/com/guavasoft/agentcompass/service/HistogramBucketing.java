package com.guavasoft.agentcompass.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared bucket-width picker for the explorer histograms. Extracted from
 * identical copies in {@link LogService} and {@link TraceExplorerService}.
 * Each page keeps its own "nice" step ladder — mirroring its frontend NICE
 * array, which is why the ladders deliberately differ — and passes it in, so
 * only the ladder walk lives here.
 */
public final class HistogramBucketing {

  private HistogramBucketing() {
  }

  /**
   * Picks the smallest ladder step wide enough to cover the window in at most
   * {@code targetBuckets} buckets, falling back to the largest step for
   * windows wider than the ladder covers. {@code ladderSeconds} must be sorted
   * ascending and non-empty.
   */
  public static long pickBucketSeconds(
      Instant windowStart, Instant windowEnd, int targetBuckets, long[] ladderSeconds) {
    long windowSeconds = Math.max(1L, Duration.between(windowStart, windowEnd).getSeconds());
    long rawSeconds = windowSeconds / Math.max(1, targetBuckets);
    for (long stepSeconds : ladderSeconds) {
      if (stepSeconds >= rawSeconds) {
        return stepSeconds;
      }
    }
    return ladderSeconds[ladderSeconds.length - 1];
  }
}
