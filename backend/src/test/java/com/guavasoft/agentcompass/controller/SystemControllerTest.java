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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.ConfigurationEntry;
import com.guavasoft.agentcompass.model.ConfigurationGroup;
import com.guavasoft.agentcompass.model.EffectiveConfiguration;
import com.guavasoft.agentcompass.model.EffectiveOllamaSettings;
import com.guavasoft.agentcompass.model.IngestHealth;
import com.guavasoft.agentcompass.model.OllamaConnectionTestResult;
import com.guavasoft.agentcompass.model.OllamaModelListResult;
import com.guavasoft.agentcompass.model.OllamaModelListResult.OllamaModelSummary;
import com.guavasoft.agentcompass.model.PurgePreview;
import com.guavasoft.agentcompass.model.PurgeResult;
import com.guavasoft.agentcompass.model.PurgeTableEstimate;
import com.guavasoft.agentcompass.model.PurgeTableResult;
import com.guavasoft.agentcompass.model.RepositoryUsage;
import com.guavasoft.agentcompass.model.SchemaMigration;
import com.guavasoft.agentcompass.model.SignalIngest;
import com.guavasoft.agentcompass.model.SqlMirroring;
import com.guavasoft.agentcompass.model.StorageOverview;
import com.guavasoft.agentcompass.model.SystemBuild;
import com.guavasoft.agentcompass.model.TableStorage;
import com.guavasoft.agentcompass.model.UpdateCheckStatus;
import com.guavasoft.agentcompass.service.OllamaSettingsService;
import com.guavasoft.agentcompass.service.SystemService;
import com.guavasoft.agentcompass.service.UpdateCheckService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemController.class)
class SystemControllerTest {

    private static final Instant MEASURED_AT = Instant.parse("2026-08-23T11:47:31Z");
    private static final Instant CUTOFF = Instant.parse("2026-07-24T11:47:31Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SystemService systemService;

    @MockitoBean
    OllamaSettingsService ollamaSettingsService;

    @MockitoBean
    UpdateCheckService updateCheckService;

    @Test
    void storageReturnsPerTableFootprintAndDatabaseTotal() throws Exception {
        when(systemService.storageOverview()).thenReturn(new StorageOverview(
                List.of(new TableStorage(
                        "metric_points", 4590123L, 3221225472L, 1073741824L, 1546188226L,
                        5841378122L, MEASURED_AT, MEASURED_AT, 331520L, 201326592L)),
                7699898368L, 245366784L, MEASURED_AT));

        mockMvc.perform(get("/api/system/storage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables", hasSize(1)))
                .andExpect(jsonPath("$.tables[0].tableName").value("metric_points"))
                .andExpect(jsonPath("$.tables[0].rowCount").value(4590123L))
                .andExpect(jsonPath("$.tables[0].toastBytes").value(1546188226L))
                .andExpect(jsonPath("$.tables[0].estimatedBytesPerDay").value(201326592L))
                .andExpect(jsonPath("$.databaseTotalBytes").value(7699898368L));
    }

    @Test
    void ingestReturnsOneEntryPerSignalWithNullSeriesCardinalityOffMetrics() throws Exception {
        when(systemService.ingestHealth()).thenReturn(new IngestHealth(
                List.of(
                        new SignalIngest("logs", "log_records", MEASURED_AT, MEASURED_AT,
                                12L, 340L, 2200L, 41L, "event_name", null),
                        new SignalIngest("metrics", "metric_points", MEASURED_AT, MEASURED_AT,
                                1842L, 44219L, 331520L, 8L, "metric_name", 4454L)),
                MEASURED_AT));

        mockMvc.perform(get("/api/system/ingest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signals", hasSize(2)))
                .andExpect(jsonPath("$.signals[0].signal").value("logs"))
                .andExpect(jsonPath("$.signals[0].nameCardinalityLabel").value("event_name"))
                .andExpect(jsonPath("$.signals[0].seriesCardinality").doesNotExist())
                .andExpect(jsonPath("$.signals[1].seriesCardinality").value(4454L))
                .andExpect(jsonPath("$.signals[1].rowsLastHour").value(1842L));
    }

    @Test
    void repositoriesReturnsRepositoryUsageNewestFirst() throws Exception {
        when(systemService.repositoryUsage()).thenReturn(List.of(
                new RepositoryUsage("https://github.com/guavasoftcom/agent-compass", MEASURED_AT, 18420L),
                new RepositoryUsage("https://github.com/guavasoftcom/spring-batch-dashboard", CUTOFF, 52L)));

        mockMvc.perform(get("/api/system/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].repositoryUrl")
                        .value("https://github.com/guavasoftcom/agent-compass"))
                .andExpect(jsonPath("$[0].count").value(18420L))
                .andExpect(jsonPath("$[1].repositoryUrl")
                        .value("https://github.com/guavasoftcom/spring-batch-dashboard"))
                .andExpect(jsonPath("$[1].count").value(52L));

        verify(systemService).repositoryUsage();
    }

    @Test
    void buildReturnsVersionsAndMigrationHistory() throws Exception {
        when(systemService.systemBuild()).thenReturn(new SystemBuild(
                "1.6.0-SNAPSHOT", MEASURED_AT, "21.0.5", "Eclipse Adoptium",
                "OpenJDK 64-Bit Server VM", "16.13",
                List.of(new SchemaMigration(19, "19", "event name column everywhere", "SQL",
                        "V19__event_name_column_everywhere.sql", true, 412, MEASURED_AT))));

        mockMvc.perform(get("/api/system/build"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationVersion").value("1.6.0-SNAPSHOT"))
                .andExpect(jsonPath("$.postgresVersion").value("16.13"))
                .andExpect(jsonPath("$.migrations", hasSize(1)))
                .andExpect(jsonPath("$.migrations[0].version").value("19"))
                .andExpect(jsonPath("$.migrations[0].success").value(true))
                .andExpect(jsonPath("$.migrations[0].executionTimeMillis").value(412));
    }

    @Test
    void configurationReturnsGroupedPropertiesCarryingTheMirroringFlag() throws Exception {
        when(systemService.effectiveConfiguration()).thenReturn(new EffectiveConfiguration(
                List.of(new ConfigurationGroup("LLM requests & cost", "api_request logs",
                        List.of(new ConfigurationEntry("tuning.api-request-event-name", "api_request",
                                false, SqlMirroring.MIRRORED, List.of("V14", "V15", "V19"),
                                "event.name of the per-request log")))),
                47, 0));

        mockMvc.perform(get("/api/system/configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyCount").value(47))
                .andExpect(jsonPath("$.overriddenCount").value(0))
                .andExpect(jsonPath("$.groups[0].entries[0].propertyName")
                        .value("tuning.api-request-event-name"))
                .andExpect(jsonPath("$.groups[0].entries[0].sqlMirroring").value("MIRRORED"))
                .andExpect(jsonPath("$.groups[0].entries[0].mirroredIn", hasSize(3)));
    }

    @Test
    void purgePreviewDefaultsToThirtyDays() throws Exception {
        when(systemService.purgePreview(anyInt())).thenReturn(samplePurgePreview());

        mockMvc.perform(get("/api/system/purge-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionDays").value(30))
                .andExpect(jsonPath("$.totalRowsToDelete").value(88412L))
                .andExpect(jsonPath("$.sql", containsString("DELETE FROM log_records")));

        verify(systemService).purgePreview(30);
    }

    @Test
    void purgePreviewHonoursAnExplicitRetentionWindow() throws Exception {
        when(systemService.purgePreview(anyInt())).thenReturn(samplePurgePreview());

        mockMvc.perform(get("/api/system/purge-preview").param("days", "90"))
                .andExpect(status().isOk());

        verify(systemService).purgePreview(90);
    }

    /**
     * The endpoint is a dry run in the strongest sense available to a slice test: nothing but the
     * read method is ever called, so no future refactor can quietly route a deletion through it.
     */
    @Test
    void purgePreviewNeverTouchesAnythingBeyondTheReadPath() throws Exception {
        when(systemService.purgePreview(anyInt())).thenReturn(samplePurgePreview());

        mockMvc.perform(get("/api/system/purge-preview").param("days", "30"))
                .andExpect(status().isOk());

        verify(systemService).purgePreview(30);
        verifyNoMoreInteractions(systemService);
    }

    @Test
    void purgePreviewRejectsARetentionWindowBelowOneDay() throws Exception {
        mockMvc.perform(get("/api/system/purge-preview").param("days", "0"))
                .andExpect(status().isBadRequest());

        verify(systemService, never()).purgePreview(anyInt());
    }

    @Test
    void purgePreviewRejectsARetentionWindowBeyondTenYears() throws Exception {
        mockMvc.perform(get("/api/system/purge-preview").param("days", "3651"))
                .andExpect(status().isBadRequest());

        verify(systemService, never()).purgePreview(anyInt());
    }

    @Test
    void purgeDeletesWhenGivenTheConfirmationPhrase() throws Exception {
        when(systemService.purge(anyInt(), anyString())).thenReturn(samplePurgeResult());

        mockMvc.perform(delete("/api/system/telemetry")
                        .param("days", "30")
                        .param("confirmation", "PURGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionDays").value(30))
                .andExpect(jsonPath("$.totalRowsDeleted").value(2380480L))
                .andExpect(jsonPath("$.preservedRows").value(4474L))
                .andExpect(jsonPath("$.tables", hasSize(1)))
                .andExpect(jsonPath("$.reclaimSpaceSql", containsString("VACUUM FULL")));

        verify(systemService).purge(30, "PURGE");
    }

    /**
     * The phrase is checked in the service, so the controller's job is only to pass it through
     * unaltered — including a wrong one, so the service is what refuses.
     */
    @Test
    void purgeForwardsTheConfirmationPhraseVerbatim() throws Exception {
        when(systemService.purge(anyInt(), anyString()))
                .thenThrow(new IllegalArgumentException("Purge requires the confirmation phrase 'PURGE'."));

        mockMvc.perform(delete("/api/system/telemetry")
                        .param("days", "30")
                        .param("confirmation", "purge"))
                .andExpect(status().isBadRequest());

        verify(systemService).purge(30, "purge");
    }

    @Test
    void purgeRejectsAMissingConfirmationPhraseWithoutReachingTheService() throws Exception {
        mockMvc.perform(delete("/api/system/telemetry").param("days", "30"))
                .andExpect(status().isBadRequest());

        verify(systemService, never()).purge(anyInt(), anyString());
    }

    @Test
    void purgeRejectsARetentionWindowOutsideTheAllowedRange() throws Exception {
        mockMvc.perform(delete("/api/system/telemetry")
                        .param("days", "0")
                        .param("confirmation", "PURGE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/system/telemetry")
                        .param("days", "3651")
                        .param("confirmation", "PURGE"))
                .andExpect(status().isBadRequest());

        verify(systemService, never()).purge(anyInt(), anyString());
    }

    /** A GET must never delete: the purge lives on DELETE and nothing else answers that path. */
    @Test
    void purgeIsNotReachableByGet() throws Exception {
        mockMvc.perform(get("/api/system/telemetry").param("confirmation", "PURGE"))
                .andExpect(status().is4xxClientError());

        verify(systemService, never()).purge(anyInt(), anyString());
    }

    // -------------------------------------------------------------------------
    // Ollama settings — GET/PUT effective, POST test-connection
    // -------------------------------------------------------------------------

    @Test
    void ollamaSettingsReturnsTheDefaultWhenNothingIsOverridden() throws Exception {
        when(ollamaSettingsService.effectiveSettings())
                .thenReturn(new EffectiveOllamaSettings("http://localhost:11434", "llama3.1", true, false));

        mockMvc.perform(get("/api/system/ollama-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("http://localhost:11434"))
                .andExpect(jsonPath("$.model").value("llama3.1"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.overridden").value(false));
    }

    @Test
    void putOllamaSettingsStoresAnOverrideAndReturnsTheUpdatedEffectiveSettings() throws Exception {
        when(ollamaSettingsService.updateSettings("http://localhost:22222", "qwen2.5:14b", false))
                .thenReturn(new EffectiveOllamaSettings("http://localhost:22222", "qwen2.5:14b", false, true));

        mockMvc.perform(put("/api/system/ollama-settings")
                        .contentType("application/json")
                        .content("{\"baseUrl\":\"http://localhost:22222\",\"model\":\"qwen2.5:14b\","
                                + "\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("http://localhost:22222"))
                .andExpect(jsonPath("$.model").value("qwen2.5:14b"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.overridden").value(true));

        verify(ollamaSettingsService).updateSettings("http://localhost:22222", "qwen2.5:14b", false);
    }

    /** Null fields clear the override; the service (not the controller) decides what that means. */
    @Test
    void putOllamaSettingsWithNullFieldsForwardsThemToTheServiceToClear() throws Exception {
        when(ollamaSettingsService.updateSettings(isNull(), isNull(), isNull()))
                .thenReturn(new EffectiveOllamaSettings("http://localhost:11434", "llama3.1", true, false));

        mockMvc.perform(put("/api/system/ollama-settings")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overridden").value(false));

        verify(ollamaSettingsService).updateSettings(isNull(), isNull(), isNull());
    }

    @Test
    void testOllamaConnectionReturns200WithSuccessTrueWhenOllamaAnswers() throws Exception {
        when(ollamaSettingsService.testConnection(isNull(), isNull()))
                .thenReturn(new OllamaConnectionTestResult(true, "Connected to Ollama at http://localhost:11434."));

        mockMvc.perform(post("/api/system/ollama/test-connection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** A failed connection is a normal outcome for this endpoint — 200, not 503. */
    @Test
    void testOllamaConnectionReturns200WithSuccessFalseWhenOllamaIsUnreachable() throws Exception {
        when(ollamaSettingsService.testConnection(isNull(), isNull()))
                .thenReturn(new OllamaConnectionTestResult(
                        false, "Could not reach Ollama at http://localhost:11434 — is it running?"));

        mockMvc.perform(post("/api/system/ollama/test-connection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("Could not reach Ollama")));
    }

    /** Inline overrides in the request body let the Settings page test an unsaved form value. */
    @Test
    void testOllamaConnectionForwardsAnInlineOverrideRatherThanTheSavedValue() throws Exception {
        when(ollamaSettingsService.testConnection(eq("http://localhost:9999"), isNull()))
                .thenReturn(new OllamaConnectionTestResult(true, "Connected to Ollama at http://localhost:9999."));

        mockMvc.perform(post("/api/system/ollama/test-connection")
                        .contentType("application/json")
                        .content("{\"baseUrl\":\"http://localhost:9999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(ollamaSettingsService).testConnection("http://localhost:9999", null);
    }

    @Test
    void listOllamaModelsReturns200WithTheInstalledModelNamesOnSuccess() throws Exception {
        when(ollamaSettingsService.listModels(isNull()))
                .thenReturn(new OllamaModelListResult(true, "Connected to Ollama at http://localhost:11434.",
                        List.of(new OllamaModelSummary("llama3.1:latest", "8.0B", 8.0),
                                new OllamaModelSummary("qwen2.5:14b", "13B", 13.0))));

        mockMvc.perform(post("/api/system/ollama/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.models", hasSize(2)))
                .andExpect(jsonPath("$.models[0].name").value("llama3.1:latest"))
                .andExpect(jsonPath("$.models[0].parameterSize").value("8.0B"))
                .andExpect(jsonPath("$.models[0].parameterCountBillions").value(8.0));
    }

    /** A failed connection is a normal outcome for this endpoint — 200, not 503, with an empty list. */
    @Test
    void listOllamaModelsReturns200WithSuccessFalseAndAnEmptyListWhenOllamaIsUnreachable() throws Exception {
        when(ollamaSettingsService.listModels(isNull()))
                .thenReturn(new OllamaModelListResult(
                        false, "Could not reach Ollama at http://localhost:11434 — is it running?", List.of()));

        mockMvc.perform(post("/api/system/ollama/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("Could not reach Ollama")))
                .andExpect(jsonPath("$.models", hasSize(0)));
    }

    /** Inline overrides in the request body let the Settings page list models for an unsaved form value. */
    @Test
    void listOllamaModelsForwardsAnInlineOverrideRatherThanTheSavedValue() throws Exception {
        when(ollamaSettingsService.listModels(eq("http://localhost:9999")))
                .thenReturn(new OllamaModelListResult(true, "Connected to Ollama at http://localhost:9999.",
                        List.of(new OllamaModelSummary("llama3.1:latest", "8.0B", 8.0))));

        mockMvc.perform(post("/api/system/ollama/models")
                        .contentType("application/json")
                        .content("{\"baseUrl\":\"http://localhost:9999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(ollamaSettingsService).listModels("http://localhost:9999");
    }

    @Test
    void updateCheckReportsANewerReleaseWithoutForcingARefreshByDefault() throws Exception {
        when(updateCheckService.status(false)).thenReturn(new UpdateCheckStatus(
                true, "2.7.1", "2.8.0", true, "https://github.com/guavasoftcom/agent-compass/releases/tag/v2.8.0",
                MEASURED_AT, MEASURED_AT, null));

        mockMvc.perform(get("/api/system/update-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.currentVersion").value("2.7.1"))
                .andExpect(jsonPath("$.latestVersion").value("2.8.0"))
                .andExpect(jsonPath("$.updateAvailable").value(true))
                .andExpect(jsonPath("$.releaseUrl").value(containsString("/releases/tag/v2.8.0")))
                .andExpect(jsonPath("$.message").doesNotExist());

        verify(updateCheckService).status(false);
    }

    @Test
    void updateCheckPassesTheRefreshFlagThroughForCheckNow() throws Exception {
        when(updateCheckService.status(true)).thenReturn(new UpdateCheckStatus(
                true, "2.7.1", null, false, null, null, MEASURED_AT, "Could not reach GitHub. Is this machine offline?"));

        mockMvc.perform(get("/api/system/update-check").param("refresh", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion").doesNotExist())
                .andExpect(jsonPath("$.updateAvailable").value(false))
                .andExpect(jsonPath("$.message").value("Could not reach GitHub. Is this machine offline?"));

        verify(updateCheckService).status(true);
    }

    @Test
    void putUpdateCheckStoresTheSwitchAndReturnsTheResultingStatus() throws Exception {
        when(updateCheckService.updateSettings(false))
                .thenReturn(new UpdateCheckStatus(false, "2.7.1", null, false, null, null, null, null));

        mockMvc.perform(put("/api/system/update-check")
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.latestVersion").doesNotExist());

        verify(updateCheckService).updateSettings(false);
    }

    /** A null switch clears the override; the service (not the controller) decides what that means. */
    @Test
    void putUpdateCheckWithNoValueForwardsNullToClearTheOverride() throws Exception {
        when(updateCheckService.updateSettings(isNull()))
                .thenReturn(new UpdateCheckStatus(true, "2.7.1", null, false, null, null, null, null));

        mockMvc.perform(put("/api/system/update-check")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());

        verify(updateCheckService).updateSettings(isNull());
    }

    private static PurgePreview samplePurgePreview() {
        return new PurgePreview(30, CUTOFF,
                List.of(new PurgeTableEstimate("log_records", "timestamp", 88412L, 0L, 132254L, 66.8,
                        1198765432L,
                        "DELETE FROM log_records WHERE timestamp < TIMESTAMPTZ '2026-07-24T11:47:31Z';")),
                88412L, 1198765432L,
                "DELETE FROM log_records WHERE timestamp < TIMESTAMPTZ '2026-07-24T11:47:31Z';");
    }

    private static PurgeResult samplePurgeResult() {
        return new PurgeResult(30, CUTOFF,
                List.of(new PurgeTableResult("metric_points", 2244556L, 2372485L)),
                2380480L, 4474L, 7822851095L, 7822851095L, 48213L,
                "VACUUM FULL log_records, metric_points, spans;", MEASURED_AT);
    }
}
