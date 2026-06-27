package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Keyset cursor for cursor-paged trace queries. Serialized as a JSON object
 * {@code {"ts":"…","id":"…"}} matching the {@code TraceCursor} TypeScript interface
 * in {@code tracesApi.ts}. The frontend encodes it as {@code "ts,id"} in the
 * {@code before}/{@code after} query params; the service parses that string — it is
 * never surfaced in this serialized form as a raw string.
 *
 * <p>Note: unlike {@link LogCursor} where {@code id} is a long (database PK),
 * the trace cursor {@code id} is the {@code traceId} hex string because the
 * traces list is aggregated and has no single-row PK. The sort-key value for
 * non-time sorts is resolved server-side from the traceId on each paging call.
 */
@Schema(description = "Keyset cursor for deterministic trace paging")
public record TraceCursor(
        @Schema(description = "Start timestamp of the boundary trace row",
                example = "2026-06-12T18:42:11.004Z")
        Instant ts,

        @Schema(description = "traceId hex string of the boundary row",
                example = "7ed9599a86cafe01beefcafe01234567")
        String id) {
}
