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
package com.guavasoft.agentcompass.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.entity.OllamaSettingsEntity;
import com.guavasoft.agentcompass.model.EffectiveOllamaSettings;
import com.guavasoft.agentcompass.model.OllamaConnectionTestResult;
import com.guavasoft.agentcompass.model.OllamaModelListResult;
import com.guavasoft.agentcompass.ollama.OllamaClient;
import com.guavasoft.agentcompass.repository.OllamaSettingsRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * Resolves and edits the Settings page's runtime Ollama connection overrides, layered on top of the
 * env/YAML-only {@link OllamaProperties} defaults. {@code base-url} and {@code model} each fall back
 * independently: a stored {@code ollama_settings} row with only {@code model} set overrides just the
 * model, leaving {@code baseUrl} on the {@code application.yml} default — chosen over an
 * all-or-nothing row because trying a different model without also having to restate the default
 * host is the more common edit, and treating "not set" as "no opinion" per field is the less
 * surprising reading of a nullable column.
 *
 * <p>Deliberately not {@code @Transactional(readOnly = true)} at the class level, the same reasoning
 * as {@link TraceAnalysisService}: {@link #testConnection} calls {@link OllamaClient#testConnection}
 * over the network and must not hold a pooled connection open for that.
 */
@Service
public class OllamaSettingsService {

    private final OllamaSettingsRepository ollamaSettingsRepository;
    private final OllamaProperties ollamaProperties;
    private final OllamaClient ollamaClient;

    public OllamaSettingsService(
            OllamaSettingsRepository ollamaSettingsRepository,
            OllamaProperties ollamaProperties,
            OllamaClient ollamaClient) {
        this.ollamaSettingsRepository = ollamaSettingsRepository;
        this.ollamaProperties = ollamaProperties;
        this.ollamaClient = ollamaClient;
    }

    /** The base URL and model actually in effect right now — stored override, else the YAML default. */
    @Transactional(readOnly = true)
    public EffectiveOllamaSettings effectiveSettings() {
        return resolve(ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID).orElse(null));
    }

    /**
     * Upserts the singleton override row. A blank/null baseUrl/model clears that field's override
     * back to the default rather than storing an empty string — an operator clearing the "Base URL"
     * text field on the Settings page should not accidentally pin an override to {@code ""}. {@code
     * enabled} has no "blank" analogue (a toggle is always on or off); a null value clears the
     * override back to {@code ollama.enabled}, and the Settings page's toggle always sends an
     * explicit {@code true}/{@code false} once touched.
     */
    @Transactional
    public EffectiveOllamaSettings updateSettings(String baseUrl, String model, Boolean enabled) {
        OllamaSettingsEntity entity = ollamaSettingsRepository
                .findById(OllamaSettingsEntity.SINGLETON_ID)
                .orElseGet(OllamaSettingsEntity::new);
        entity.setId(OllamaSettingsEntity.SINGLETON_ID);
        entity.setBaseUrl(StringUtils.trimToNull(baseUrl));
        entity.setModel(StringUtils.trimToNull(model));
        entity.setEnabled(enabled);
        entity.setUpdatedAt(Instant.now());
        return resolve(ollamaSettingsRepository.save(entity));
    }

    /**
     * Pings Ollama without saving anything. {@code baseUrlOverride}/{@code modelOverride} let the
     * Settings page test unsaved form values before clicking Save — a blank/null override falls back
     * to whatever is currently effective (stored override, else default) rather than being treated
     * as "clear". {@code model} is accepted for symmetry with the rest of this class but unused here:
     * {@code GET /api/tags} lists installed models without needing to name one, so there is nothing
     * model-specific for a connectivity check to fail on.
     */
    public OllamaConnectionTestResult testConnection(String baseUrlOverride, String modelOverride) {
        String baseUrl = StringUtils.isNotBlank(baseUrlOverride)
                ? baseUrlOverride.trim()
                : effectiveSettings().baseUrl();
        return ollamaClient.testConnection(baseUrl);
    }

    /**
     * Lists the models installed on Ollama without saving anything. {@code baseUrlOverride} lets the
     * Settings page list models for an unsaved form value before clicking Save — a blank/null
     * override falls back to the currently effective base URL, the same resolution
     * {@link #testConnection} applies.
     */
    public OllamaModelListResult listModels(String baseUrlOverride) {
        String baseUrl = StringUtils.isNotBlank(baseUrlOverride)
                ? baseUrlOverride.trim()
                : effectiveSettings().baseUrl();
        return ollamaClient.listModels(baseUrl);
    }

    private EffectiveOllamaSettings resolve(OllamaSettingsEntity entity) {
        String storedBaseUrl = entity == null ? null : entity.getBaseUrl();
        String storedModel = entity == null ? null : entity.getModel();
        Boolean storedEnabled = entity == null ? null : entity.getEnabled();
        String effectiveBaseUrl = Optional.ofNullable(storedBaseUrl).orElseGet(ollamaProperties::getBaseUrl);
        String effectiveModel = Optional.ofNullable(storedModel).orElseGet(ollamaProperties::getModel);
        boolean effectiveEnabled = Optional.ofNullable(storedEnabled).orElseGet(ollamaProperties::isEnabled);
        boolean overridden = storedBaseUrl != null || storedModel != null || storedEnabled != null;
        return new EffectiveOllamaSettings(effectiveBaseUrl, effectiveModel, effectiveEnabled, overridden);
    }
}
