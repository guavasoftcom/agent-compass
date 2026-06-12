# Backend conventions

Project-wide conventions for `backend/` (Spring Boot 3.5, Java 21). Read [../AGENTS.md](../AGENTS.md) for repo-wide context.

## Module layout

- `otlp/` — the OTLP ingest slice, with its own sub-packages: `otlp/controller` (`OtlpLogController` / `OtlpMetricController` / `OtlpTraceController` accept `application/x-protobuf` on `POST /v1/logs`, `/v1/metrics`, `/v1/traces`), `otlp/mapper` (`OtlpLogMapper` / `OtlpMetricMapper` / `OtlpTraceMapper` — `@Component`s, not MapStruct — translate OTLP proto into `*Entity` rows including the attribute jsonb maps), and `otlp/service` (`OtlpLogService` / `OtlpMetricService` / `OtlpTraceService` write paths).
- `entity/` — JPA entities (`LogRecordEntity`, `MetricPointEntity`, `SpanEntity`). Attribute payloads use `@JdbcTypeCode(SqlTypes.JSON)` with `columnDefinition = "jsonb"` and a `Map<String, Object>` field.
- `repository/` — Spring Data JPA. Cross-row aggregations are native SQL (`@Query(..., nativeQuery = true)`); simple JPQL stays in repositories that don't need jsonb operators or window functions.
- `service/` — `LogService` / `MetricService` / `MetricSeriesService` / `TraceService` are `@Transactional(readOnly = true)` and own the parsing/cursor/range logic for dashboard endpoints; `ReportService` renders the markdown report from a Mustache template.
- `controller/` — per-domain REST controllers for the React app under `/api`: `LogsController`, `MetricsController`, `SessionController`, `ToolActivityController`, `TracesController`; `ReportController` serves the markdown report.
- `model/` — record DTOs (Lombok `@Data @Builder` on older shapes like `ToolCallCount` / `TraceSummary`); MapStruct mappers in `mapper/` (`spring` component model, `lombok-mapstruct-binding` keeps them compatible) handle entity → DTO mapping for `LogRecord` / `Span` / `EventRow`.
- `config/` — `TuningProperties` (overridable event/attribute/metric names), `OpenApiConfig`, `MustacheConfig`.

## Naming

- **All variables — method parameters, fields, and locals defined inside methods — use full descriptive names.** Never single letters or two-letter abbreviations. Use `anyValue` not `v`, `keyValue` not `kv`, `hexBuilder` not `sb`, `byteValue` not `b`, `request` not `req`. This rule applies to enhanced-for loop variables (`for (byte byteValue : bytes)`), not just declarations.
- Short names are allowed **only** in: lambda parameters, generic type parameters (`T`, `K`, `V`), standard index loops (`i`, `j`, `k`), and `e` in `catch (SomeException e)`.
- Prefer expressive names over abbreviations everywhere else: `throughputBars` over `bars`, `stepDetails` over `details`.

## Java style

- Java 21, `--release 21`. Don't lower the source level.
- Prefer `MediaType.APPLICATION_*` / `APPLICATION_*_VALUE` constants over string literals like `"application/x-protobuf"`.
- **Extract string literals defined inside methods as `private static final String` constants** at the top of the class — format specifiers (`HEX_BYTE_FORMAT = "%02x"`), header names, attribute keys, magic values, etc. Carve-out: one-off `log.*` and exception messages stay inline (extracting them adds noise without payoff).
- **Extract numeric literals defined inside methods as `private static final` constants whenever the number carries domain meaning** — conversion factors (`NANOS_PER_SECOND = 1_000_000_000L`), per-unit counts (`HEX_CHARS_PER_BYTE = 2`), thresholds, timeouts, sizes, ports, retry counts, etc. Small values like `2` are NOT exempt when they mean something specific. Carve-outs (stay inline): `0`/`1`/`-1` as identities, bit masks like `0xff`, generic index/loop arithmetic, test fixture data, and counts that are obvious from immediate context (`new ArrayList<>(events.size())`).
- Records (not Lombok-annotated classes) for new response DTOs; the legacy `@Data @Builder` shape stays for `ToolCallCount`, `TraceSummary`, etc. until they're touched for other reasons.
- Lombok + MapStruct are both on the annotation-processor path; new mappers use `@Mapper(componentModel = "spring")`.
- Checkstyle runs in the `validate` phase and fails the build. Max line length is 140; tabs are forbidden; `NeedBraces` is enforced (every `if`/`else`/`for`/`while` body wrapped, even one-liners).

## Web layer

- **Thin controllers.** Controllers dispatch to services and shape the HTTP response (headers, status, content-type) — no parsing, no cursor math, no validation logic. The dual-window endpoints follow a single shape: `if (startTimestamp != null && endTimestamp != null) return service.xxxInRange(start, end); return service.xxx(minutes);`. Mirror this pattern on every new dashboard endpoint so the frontend can always pick either window form.
- Annotate new endpoints with `@Tag` / `@Operation` (and `@ApiResponses` with concrete `@Schema`) so springdoc OpenAPI stays useful for the React client.
- When both Spring's `@RequestBody` and Swagger's `@RequestBody` are needed, import Spring's and fully-qualify Swagger's (it's documentation metadata, used less in code).
- Don't return JPA entities from controllers — map to a record (or the legacy `@Data` DTO) through MapStruct first.
- OTLP ingest controllers consume `application/x-protobuf` and respond with the matching `ExportXxxServiceResponse` protobuf body, returning `400` with a `partialSuccess.errorMessage` set on `InvalidProtocolBufferException`.

## Data

- **Schema lives in Flyway migrations** under `src/main/resources/db/migration/` (`V1__init.sql` is the base, `V2__attribute_indexes.sql` adds the GIN indexes). `spring.jpa.hibernate.ddl-auto=validate` — Hibernate will fail startup if the entity model and DB drift, so every schema change goes through a new `V{n}__*.sql` migration.
- **Attribute payloads stay in `jsonb` columns** (`attributes`, `resource_attributes`, `scope_attributes`, plus `events` on spans); use the existing `@JdbcTypeCode(SqlTypes.JSON)` + `Map<String, Object>` converter pattern rather than flattening into columns. Index containment-style filters with `USING gin (attributes jsonb_path_ops)`.
- **Native SQL** is the norm in repositories — JPQL has no `jsonb_each_text`, `date_bin`, `percentile_cont`, or window-function support, and almost every aggregation needs at least one of them. Always parameterize values with `@Param` (no string concatenation into the query).
- **Range-and-fallback query pattern.** Every dashboard aggregation has two repository methods: `aggregateXxx(..., Instant since)` for the `?minutes=` form and `aggregateXxxInRange(..., Instant start, Instant end)` for the `?startTimestamp=&endTimestamp=` form. Repositories that also serve the DataGrid filters use a single method with `(CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp) AND (... endTimestamp ...)` so empty/one-sided/two-sided bounds all flow through one query.
- **Configurable event/attribute names.** Dashboard aggregations are driven by `TuningProperties` (`tuning.*` in `application.yml`) — `toolEventName`, `toolAttribute`, `tokenUsageMetric`, etc. Defaults match Claude Code's emission shape. Don't hardcode `"tool_result"` / `"tool_name"` / `"claude_code.token.usage"` in queries or services; read them from `TuningProperties`.
- **Derived severity.** Log severity is computed, not taken from OTLP: the `derive_log_severity()` function (`V6`) feeds the stored generated `derived_severity` column (`V8`). The classification defaults are documented on `TuningProperties` and mirrored client-side by `severityOf` in `frontend/src/pages/LogsPage/logsApi.ts` — redefining the function requires a migration that also rebuilds the stored column, so change all three in lockstep.

## Skills

Project skills under `.claude/skills/` worth invoking proactively here:

- `/postgresql-optimization` — when writing or tuning native SQL in `repository/`, especially anything touching the `jsonb` attribute columns: containment (`@>`), GIN/`jsonb_path_ops` indexes, window functions (`date_bin`, `percentile_cont`, `LAG`), and range filters. The aggregations in [LogRecordRepository.java](src/main/java/com/guavasoft/agentcompass/repository/LogRecordRepository.java) are the canonical examples.
- `/java-refactoring-extract-method` — when a Java method exceeds the skill's thresholds (LOC > 15, NOM > 10, CC > 10). Most relevant in `service/` (multi-step aggregation pipelines, `ReportService.buildSuggestions`) and `otlp/mapper` (OTLP proto → entity mapping).
- `/java-refactoring-remove-parameter` — when a method signature carries a parameter that callers never need or that's derivable from a field/constant. Apply before extending an already-redundant signature.
