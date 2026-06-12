package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Keyset cursor for cursor-paged log queries. Serialized as a JSON object
 * {@code {"ts":"…","id":…}} matching the {@code LogCursor} TypeScript interface
 * in {@code logsApi.ts}. The frontend encodes it as {@code "ts,id"} in the
 * {@code before}/{@code after} query params; the service parses that string —
 * it is never surfaced in this serialized form as a raw string.
 */
@Schema(description = "Keyset cursor for deterministic (timestamp, id) paging")
public record LogCursor(
        @Schema(description = "Timestamp of the boundary row", example = "2026-06-07T18:42:11.004Z")
        Instant ts,

        @Schema(description = "Database id of the boundary row", example = "84213")
        long id) {
}
