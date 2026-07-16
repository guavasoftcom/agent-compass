package com.guavasoft.agentcompass.service;

/**
 * Levenshtein distance with an early-abandon bound, used by the tuning report's
 * path near-miss (typo) detection. Kept in-process instead of Postgres'
 * fuzzystrmatch extension: the extension would need a migration on every
 * deployment and its {@code levenshtein()} caps inputs at 255 characters, which
 * scratchpad paths regularly exceed.
 */
final class BoundedEditDistance {

  private static final int OVER_LIMIT = -1;

  private BoundedEditDistance() {
  }

  /**
   * Returns the Levenshtein distance between {@code left} and {@code right}, or
   * {@value #OVER_LIMIT} when the distance exceeds {@code maxDistance} (the
   * computation abandons early, so far-apart strings stay cheap).
   */
  static int compute(String left, String right, int maxDistance) {
    if (left == null || right == null) {
      return OVER_LIMIT;
    }
    int leftLength = left.length();
    int rightLength = right.length();
    if (Math.abs(leftLength - rightLength) > maxDistance) {
      return OVER_LIMIT;
    }

    int[] previousRow = new int[rightLength + 1];
    int[] currentRow = new int[rightLength + 1];
    for (int rightIndex = 0; rightIndex <= rightLength; rightIndex++) {
      previousRow[rightIndex] = rightIndex;
    }

    for (int leftIndex = 1; leftIndex <= leftLength; leftIndex++) {
      currentRow[0] = leftIndex;
      int rowMinimum = currentRow[0];
      for (int rightIndex = 1; rightIndex <= rightLength; rightIndex++) {
        int substitutionCost = left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1;
        currentRow[rightIndex] = Math.min(
            Math.min(currentRow[rightIndex - 1] + 1, previousRow[rightIndex] + 1),
            previousRow[rightIndex - 1] + substitutionCost);
        rowMinimum = Math.min(rowMinimum, currentRow[rightIndex]);
      }
      if (rowMinimum > maxDistance) {
        return OVER_LIMIT;
      }
      int[] swap = previousRow;
      previousRow = currentRow;
      currentRow = swap;
    }

    int distance = previousRow[rightLength];
    return distance > maxDistance ? OVER_LIMIT : distance;
  }
}
