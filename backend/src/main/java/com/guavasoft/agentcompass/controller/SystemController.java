/*
 * Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not,
 * see <https://www.gnu.org/licenses/>.
 */
package com.guavasoft.agentcompass.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.guavasoft.agentcompass.model.EffectiveConfiguration;
import com.guavasoft.agentcompass.model.EffectiveOllamaSettings;
import com.guavasoft.agentcompass.model.IngestHealth;
import com.guavasoft.agentcompass.model.OllamaConnectionProbe;
import com.guavasoft.agentcompass.model.OllamaConnectionTestResult;
import com.guavasoft.agentcompass.model.OllamaModelListResult;
import com.guavasoft.agentcompass.model.OllamaSettingsRequest;
import com.guavasoft.agentcompass.model.PurgePreview;
import com.guavasoft.agentcompass.model.PurgeResult;
import com.guavasoft.agentcompass.model.RepositoryUsage;
import com.guavasoft.agentcompass.model.StorageOverview;
import com.guavasoft.agentcompass.model.SystemBuild;
import com.guavasoft.agentcompass.model.UpdateCheckSettingsRequest;
import com.guavasoft.agentcompass.model.UpdateCheckStatus;
import com.guavasoft.agentcompass.service.OllamaSettingsService;
import com.guavasoft.agentcompass.service.SystemService;
import com.guavasoft.agentcompass.service.UpdateCheckService;

import java.util.List;

/**
 * Operational diagnostics for the Settings page, plus the one action in the dashboard that mutates
 * data.
 *
 * <p>Every endpoint but one is a GET. {@link #purgePreview(int)} measures what a retention cutoff
 * would delete and renders the SQL to do it, without executing anything.
 * {@link #purge(int, String)} is the endpoint that does: {@code DELETE /api/system/telemetry},
 * gated on an exact confirmation phrase so nothing can trigger it by accident. Both gate on whole
 * sessions rather than individual row ages — a row belonging to a session still active in any
 * signal survives however old it is, so a session is always purged entirely or not at all.
 * {@code VACUUM FULL} to actually reclaim disk space is left to the operator; the purge result hands
 * over that statement rather than running it, since it needs an exclusive lock this endpoint has no
 * business taking.
 *
 * <p>Unlike the dashboard controllers these endpoints take no time window. The figures describe the
 * database as it stands, not a slice of it.
 */
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/system")
@Tag(name = "System",
        description = "Storage, ingest, schema, and configuration diagnostics for the Settings page. "
                + "Every endpoint is read-only except DELETE /api/system/telemetry, the confirmed "
                + "retention purge.")
public class SystemController {

  private static final String DEFAULT_RETENTION_DAYS_VALUE = "30";
  private static final int MINIMUM_RETENTION_DAYS = 1;
  private static final int MAXIMUM_RETENTION_DAYS = 3650;

  private final SystemService systemService;
  private final OllamaSettingsService ollamaSettingsService;
  private final UpdateCheckService updateCheckService;

  @GetMapping("/storage")
  @Operation(
          summary = "Per-table disk footprint, row counts, and growth estimate",
          description = "Nothing prunes old telemetry — there is no retention window, TTL, or cleanup "
                  + "job — so these figures only grow until an operator deletes rows by hand. Sizes "
                  + "decompose exactly into heap, index, and TOAST. Row counts are exact rather than "
                  + "planner estimates, which are unreliable on this schema. Growth is an estimate "
                  + "derived from average row size and the last seven days' insert rate, since no "
                  + "historical size samples are retained.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "Storage overview",
          content = @Content(schema = @Schema(implementation = StorageOverview.class))))
  public StorageOverview storage() {
    return systemService.storageOverview();
  }

  @GetMapping("/ingest")
  @Operation(
          summary = "Whether telemetry is still arriving, per OTLP signal",
          description = "Reports the newest row per signal, when this server received it, volume over "
                  + "the last hour, day, and week, and name cardinality. A stale received-at means the "
                  + "agent stopped exporting or the collector cannot reach this server — the dashboard "
                  + "pages would keep rendering the last data they have without saying so.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "Ingest health per signal",
          content = @Content(schema = @Schema(implementation = IngestHealth.class))))
  public IngestHealth ingest() {
    return systemService.ingestHealth();
  }

  @GetMapping("/build")
  @Operation(
          summary = "Running version, JVM, Postgres version, and applied migrations",
          description = "The application version comes from build-info.properties, generated at "
                  + "package time; it reads 'dev' for an IDE-launched run that skipped that step. "
                  + "Hibernate runs with ddl-auto=validate, so the migration list is the complete "
                  + "story of how the schema reached its current shape.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "Build and schema information",
          content = @Content(schema = @Schema(implementation = SystemBuild.class))))
  public SystemBuild build() {
    return systemService.systemBuild();
  }

  @GetMapping("/update-check")
  @Operation(
          summary = "Whether a newer release than the running one has been published",
          description = "Compares the running version with the latest GitHub release of this "
                  + "project. The only call this application makes to a third party, and it can be "
                  + "switched off (PUT /api/system/update-check): with the switch off nothing is "
                  + "sent and every field but enabled and currentVersion is empty. The answer is "
                  + "cached for hours, so this is cheap to poll; refresh=true bypasses the cache and "
                  + "is for an explicit 'Check now'. Always returns 200 — an offline machine, a "
                  + "rate-limited network or a development build is reported in 'message', not as "
                  + "an error.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "Update-check status",
          content = @Content(schema = @Schema(implementation = UpdateCheckStatus.class))))
  public UpdateCheckStatus updateCheck(
          @Parameter(description = "Skip the cache and ask GitHub now", example = "false")
          @RequestParam(defaultValue = "false") boolean refresh) {
    return updateCheckService.status(refresh);
  }

  @PutMapping("/update-check")
  @Operation(
          summary = "Turn the update check on or off",
          description = "Upserts the singleton update_check_settings row. A null enabled clears the "
                  + "override back to the update-check.enabled application.yml default. Enforced "
                  + "server-side: with the check off, GET /api/system/update-check makes no "
                  + "outbound request at all. Returns the resulting status, so turning the check on "
                  + "answers in the same round trip.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "The status after the change",
          content = @Content(schema = @Schema(implementation = UpdateCheckStatus.class))))
  public UpdateCheckStatus setUpdateCheckEnabled(@RequestBody UpdateCheckSettingsRequest request) {
    return updateCheckService.updateSettings(request.enabled());
  }

  @GetMapping("/configuration")
  @Operation(
          summary = "Every effective tuning.* property, flagged for SQL mirroring",
          description = "Several of these values are duplicated as literals inside Flyway migrations "
                  + "— generated columns, views, the severity function, and partial index predicates "
                  + "— because native SQL cannot read Spring properties at parse time. Overriding one "
                  + "of those without a matching migration makes the affected pages read the wrong "
                  + "rows silently rather than fail, which is what the mirroring flag exists to warn "
                  + "about. Only tuning.* is rendered; the Spring Environment is never walked, so no "
                  + "credential can appear here.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "Grouped effective configuration",
          content = @Content(schema = @Schema(implementation = EffectiveConfiguration.class))))
  public EffectiveConfiguration configuration() {
    return systemService.effectiveConfiguration();
  }

  @GetMapping("/purge-preview")
  @Operation(
          summary = "Dry run of a retention cutoff — measures, never deletes",
          description = "Reports the exact rows and estimated bytes a purge of the given retention "
                  + "window would reclaim, per table, plus the SQL script to run yourself instead of "
                  + "calling DELETE /api/system/telemetry. This endpoint executes nothing. The purge "
                  + "it previews is gated on whole sessions, not row age alone: a row belonging to a "
                  + "session still active in any signal is kept however old it is, so preservedRows "
                  + "on the response is almost entirely that protection, not an edge case. Byte "
                  + "figures are proportional estimates; a DELETE also makes space reusable rather "
                  + "than returning it to the operating system, which needs VACUUM FULL.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Purge estimate and the SQL to perform it",
              content = @Content(schema = @Schema(implementation = PurgePreview.class))),
      @ApiResponse(responseCode = "400", description = "days outside 1..3650", content = @Content)})
  public PurgePreview purgePreview(
          @Parameter(description = "Retention window in days; rows older than this would be deleted",
                  example = DEFAULT_RETENTION_DAYS_VALUE)
          @RequestParam(defaultValue = DEFAULT_RETENTION_DAYS_VALUE)
          @Min(MINIMUM_RETENTION_DAYS) @Max(MAXIMUM_RETENTION_DAYS) int days) {
    return systemService.purgePreview(days);
  }

  @DeleteMapping("/telemetry")
  @Operation(
          summary = "Permanently delete dormant telemetry for the given retention window",
          description = "The only endpoint in this application that deletes data, and it cannot be "
                  + "undone — call GET /api/system/purge-preview first and act on what it reports. "
                  + "Requires the exact confirmation phrase, so nothing can trigger this by accident. "
                  + "Deletion is gated on whole sessions, not individual row timestamps: a session's "
                  + "rows across all three tables are removed only once its last activity anywhere is "
                  + "older than the cutoff, and are then removed entirely — never split, with old "
                  + "turns gone and recent ones kept, which a plain row-age delete would do to any "
                  + "session straddling the cutoff. A metric point with no session id at all falls "
                  + "back to keeping the newest row of its stream, because a stream left without a "
                  + "predecessor would record its entire cumulative counter as a single increment on "
                  + "its next emission. All three tables are cut in one transaction. Prefer to run "
                  + "this while no agent is actively exporting: the delete holds locks that block "
                  + "ingest, and figures on the other pages shift underneath anyone reading them. "
                  + "Space is marked reusable but not returned to the operating system; the response "
                  + "carries the VACUUM FULL statement for that.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Purge completed",
              content = @Content(schema = @Schema(implementation = PurgeResult.class))),
      @ApiResponse(responseCode = "400",
              description = "Missing or incorrect confirmation phrase, or days outside 1..3650",
              content = @Content)})
  public PurgeResult purge(
          @Parameter(description = "Retention window in days; rows older than this are deleted",
                  example = DEFAULT_RETENTION_DAYS_VALUE)
          @RequestParam(defaultValue = DEFAULT_RETENTION_DAYS_VALUE)
          @Min(MINIMUM_RETENTION_DAYS) @Max(MAXIMUM_RETENTION_DAYS) int days,
          @Parameter(description = "Must be exactly 'PURGE'. Guards against accidental invocation.",
                  required = true, example = "PURGE")
          @RequestParam String confirmation) {
    return systemService.purge(days, confirmation);
  }

  @GetMapping("/repositories")
  @Operation(
          summary = "Every repository seen in telemetry, for the repository picker",
          description = "Distinct repository_url values unioned across spans, log_records, and "
                  + "metric_points, each with its newest timestamp and total row count, newest first. "
                  + "Takes no time window — see the class-level note. Only repositories with a "
                  + "non-null repository_url are listed; the frontend adds its own synthetic 'All "
                  + "repositories' and 'Unattributed' entries around this list.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "Repositories seen in telemetry",
          content = @Content(schema = @Schema(implementation = RepositoryUsage.class))))
  public List<RepositoryUsage> repositories() {
    return systemService.repositoryUsage();
  }

  @GetMapping("/ollama-settings")
  @Operation(
          summary = "Effective Ollama connection settings for the 'Analyze trace' feature",
          description = "baseUrl/model are each independently either a stored Settings-page override "
                  + "or the ollama.base-url/ollama.model application.yml default. 'overridden' is true "
                  + "when at least one field is a stored override.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "Effective Ollama settings",
          content = @Content(schema = @Schema(implementation = EffectiveOllamaSettings.class))))
  public EffectiveOllamaSettings ollamaSettings() {
    return ollamaSettingsService.effectiveSettings();
  }

  @PutMapping("/ollama-settings")
  @Operation(
          summary = "Set (or clear) the Ollama connection override",
          description = "Upserts the singleton ollama_settings row. A null or blank baseUrl/model clears "
                  + "that field's override back to the application.yml default rather than storing an "
                  + "empty string; a null enabled clears its own override back to ollama.enabled. All "
                  + "three fields clear independently. Takes effect on the very next 'Analyze trace' call "
                  + "— OllamaClient/TraceAnalysisService resolve the effective settings per request, not "
                  + "once at startup.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "The updated effective settings",
          content = @Content(schema = @Schema(implementation = EffectiveOllamaSettings.class))))
  public EffectiveOllamaSettings updateOllamaSettings(
          @RequestBody OllamaSettingsRequest request) {
    return ollamaSettingsService.updateSettings(request.baseUrl(), request.model(), request.enabled());
  }

  @PostMapping("/ollama/test-connection")
  @Operation(
          summary = "Ping Ollama without running inference",
          description = "Calls GET /api/tags (lists installed models) rather than /api/generate, so "
                  + "this is fast and cheap regardless of ollama.read-timeout. A null/blank baseUrl in "
                  + "the request body means 'test the currently effective value' rather than 'use no "
                  + "base URL' — this lets the Settings page test an unsaved form value before Save, or "
                  + "omit the body entirely to test what is already saved. Always returns 200: a failed "
                  + "connection is a normal outcome for this check, reported as success=false, not a "
                  + "503 — unlike POST /api/traces/{traceId}/analysis, which does 503 on the same "
                  + "underlying failure because there the caller asked for a real analysis, not a probe.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "Always 200 — see success/message",
          content = @Content(schema = @Schema(implementation = OllamaConnectionTestResult.class))))
  public OllamaConnectionTestResult testOllamaConnection(
          @RequestBody(required = false) OllamaConnectionProbe probe) {
    OllamaConnectionProbe effectiveProbe = OllamaConnectionProbe.orEmpty(probe);
    return ollamaSettingsService.testConnection(effectiveProbe.baseUrl(), effectiveProbe.model());
  }

  @PostMapping("/ollama/models")
  @Operation(
          summary = "List models installed on Ollama",
          description = "Calls GET /api/tags to list installed models, so this is fast and cheap "
                  + "regardless of ollama.read-timeout — no inference runs. A null/blank baseUrl in "
                  + "the request body means 'list models for the currently effective base URL' rather "
                  + "than 'use no base URL' — this lets the Settings page populate the model dropdown "
                  + "for an unsaved form value before Save, or omit the body entirely to list models "
                  + "for what is already saved. Always returns 200: a failed connection is a normal "
                  + "outcome for this check, reported as success=false with an empty models list, not "
                  + "a 503.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "Always 200 — see success/message",
          content = @Content(schema = @Schema(implementation = OllamaModelListResult.class))))
  public OllamaModelListResult listOllamaModels(
          @RequestBody(required = false) OllamaConnectionProbe probe) {
    OllamaConnectionProbe effectiveProbe = OllamaConnectionProbe.orEmpty(probe);
    return ollamaSettingsService.listModels(effectiveProbe.baseUrl());
  }
}
