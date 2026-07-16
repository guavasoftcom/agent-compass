package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PathNearMiss",
        description = "A failed Read whose path sits within a small edit distance of a path the same session "
                + "read successfully — almost always a retyped (typo'd) path")
public record PathNearMiss(
        @Schema(description = "session.id attribute (truncated for display)", example = "7af9c1…") String sessionId,
        @Schema(description = "Path the Read calls failed against", example = "/tmp/scratch/ee81…fecbb/review-diff.patch")
        String failedPath,
        @Schema(description = "Closest path the same session read successfully",
                example = "/tmp/scratch/ee81…fcdbb/review-diff.patch") String nearestSuccessfulPath,
        @Schema(description = "Levenshtein distance between the two paths", example = "2") long editDistance,
        @Schema(description = "Number of failed Read calls against the failed path", example = "7") long failures) {
}
