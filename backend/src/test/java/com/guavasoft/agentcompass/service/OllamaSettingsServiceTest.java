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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.entity.OllamaSettingsEntity;
import com.guavasoft.agentcompass.model.EffectiveOllamaSettings;
import com.guavasoft.agentcompass.model.OllamaConnectionTestResult;
import com.guavasoft.agentcompass.model.OllamaModelListResult;
import com.guavasoft.agentcompass.model.OllamaModelListResult.OllamaModelSummary;
import com.guavasoft.agentcompass.ollama.OllamaClient;
import com.guavasoft.agentcompass.repository.OllamaSettingsRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OllamaSettingsServiceTest {

    @Mock
    OllamaSettingsRepository ollamaSettingsRepository;

    @Mock
    OllamaClient ollamaClient;

    OllamaSettingsService ollamaSettingsService;

    @BeforeEach
    void setUp() {
        ollamaSettingsService =
                new OllamaSettingsService(ollamaSettingsRepository, new OllamaProperties(), ollamaClient);
    }

    @Test
    void effectiveSettingsFallsBackToDefaultsWhenNoRowIsStored() {
        when(ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID)).thenReturn(Optional.empty());

        EffectiveOllamaSettings settings = ollamaSettingsService.effectiveSettings();

        assertThat(settings.baseUrl()).isEqualTo(new OllamaProperties().getBaseUrl());
        assertThat(settings.model()).isEqualTo(new OllamaProperties().getModel());
        assertThat(settings.enabled()).isEqualTo(new OllamaProperties().isEnabled());
        assertThat(settings.overridden()).isFalse();
    }

    @Test
    void effectiveSettingsAppliesAStoredOverride() {
        OllamaSettingsEntity stored = new OllamaSettingsEntity();
        stored.setBaseUrl("http://localhost:22222");
        stored.setModel("qwen2.5:14b");
        stored.setEnabled(false);
        when(ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID)).thenReturn(Optional.of(stored));

        EffectiveOllamaSettings settings = ollamaSettingsService.effectiveSettings();

        assertThat(settings.baseUrl()).isEqualTo("http://localhost:22222");
        assertThat(settings.model()).isEqualTo("qwen2.5:14b");
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.overridden()).isTrue();
    }

    /** The enabled override clears/falls back independently of baseUrl/model, same as the other two. */
    @Test
    void effectiveSettingsFallsBackPerFieldWhenOnlyEnabledIsOverridden() {
        OllamaSettingsEntity stored = new OllamaSettingsEntity();
        stored.setEnabled(false);
        when(ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID)).thenReturn(Optional.of(stored));

        EffectiveOllamaSettings settings = ollamaSettingsService.effectiveSettings();

        assertThat(settings.baseUrl()).isEqualTo(new OllamaProperties().getBaseUrl());
        assertThat(settings.model()).isEqualTo(new OllamaProperties().getModel());
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.overridden()).isTrue();
    }

    /** Each field falls back independently — overriding only the model leaves baseUrl on default. */
    @Test
    void effectiveSettingsFallsBackPerFieldWhenOnlyOneIsOverridden() {
        OllamaSettingsEntity stored = new OllamaSettingsEntity();
        stored.setModel("qwen2.5:14b");
        when(ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID)).thenReturn(Optional.of(stored));

        EffectiveOllamaSettings settings = ollamaSettingsService.effectiveSettings();

        assertThat(settings.baseUrl()).isEqualTo(new OllamaProperties().getBaseUrl());
        assertThat(settings.model()).isEqualTo("qwen2.5:14b");
        assertThat(settings.overridden()).isTrue();
    }

    @Test
    void updateSettingsUpsertsTheSingletonRowAndReturnsTheNewEffectiveSettings() {
        when(ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID)).thenReturn(Optional.empty());
        when(ollamaSettingsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EffectiveOllamaSettings settings =
                ollamaSettingsService.updateSettings("http://localhost:22222", "qwen2.5:14b", false);

        assertThat(settings.baseUrl()).isEqualTo("http://localhost:22222");
        assertThat(settings.model()).isEqualTo("qwen2.5:14b");
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.overridden()).isTrue();

        ArgumentCaptor<OllamaSettingsEntity> entityCaptor = ArgumentCaptor.forClass(OllamaSettingsEntity.class);
        verify(ollamaSettingsRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getId()).isEqualTo(OllamaSettingsEntity.SINGLETON_ID);
        assertThat(entityCaptor.getValue().getEnabled()).isFalse();
        assertThat(entityCaptor.getValue().getUpdatedAt()).isNotNull();
    }

    /** Null/blank baseUrl/model clear the override rather than storing an empty string. */
    @Test
    void updateSettingsWithBlankFieldsClearsTheOverrideBackToDefaults() {
        OllamaSettingsEntity existing = new OllamaSettingsEntity();
        existing.setBaseUrl("http://localhost:22222");
        existing.setModel("qwen2.5:14b");
        existing.setEnabled(false);
        when(ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID)).thenReturn(Optional.of(existing));
        when(ollamaSettingsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EffectiveOllamaSettings settings = ollamaSettingsService.updateSettings("  ", null, null);

        assertThat(settings.baseUrl()).isEqualTo(new OllamaProperties().getBaseUrl());
        assertThat(settings.model()).isEqualTo(new OllamaProperties().getModel());
        assertThat(settings.enabled()).isEqualTo(new OllamaProperties().isEnabled());
        assertThat(settings.overridden()).isFalse();

        ArgumentCaptor<OllamaSettingsEntity> entityCaptor = ArgumentCaptor.forClass(OllamaSettingsEntity.class);
        verify(ollamaSettingsRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getBaseUrl()).isNull();
        assertThat(entityCaptor.getValue().getModel()).isNull();
        assertThat(entityCaptor.getValue().getEnabled()).isNull();
    }

    @Test
    void testConnectionUsesTheInlineOverrideWhenGiven() {
        when(ollamaClient.testConnection("http://localhost:9999"))
                .thenReturn(new OllamaConnectionTestResult(true, "Connected to Ollama at http://localhost:9999."));

        OllamaConnectionTestResult result = ollamaSettingsService.testConnection("http://localhost:9999", null);

        assertThat(result.success()).isTrue();
        verify(ollamaClient).testConnection("http://localhost:9999");
    }

    @Test
    void testConnectionFallsBackToTheCurrentlyEffectiveBaseUrlWhenNoInlineOverrideIsGiven() {
        when(ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID)).thenReturn(Optional.empty());
        when(ollamaClient.testConnection(anyString()))
                .thenReturn(new OllamaConnectionTestResult(true, "Connected."));

        ollamaSettingsService.testConnection(null, null);

        verify(ollamaClient).testConnection(new OllamaProperties().getBaseUrl());
    }

    @Test
    void listModelsUsesTheInlineOverrideWhenGiven() {
        when(ollamaClient.listModels("http://localhost:9999"))
                .thenReturn(new OllamaModelListResult(true, "Connected to Ollama at http://localhost:9999.",
                        List.of(new OllamaModelSummary("llama3.1:latest", "8.0B", 8.0))));

        OllamaModelListResult result = ollamaSettingsService.listModels("http://localhost:9999");

        assertThat(result.success()).isTrue();
        assertThat(result.models()).containsExactly(new OllamaModelSummary("llama3.1:latest", "8.0B", 8.0));
        verify(ollamaClient).listModels("http://localhost:9999");
    }

    @Test
    void listModelsFallsBackToTheCurrentlyEffectiveBaseUrlWhenNoInlineOverrideIsGiven() {
        when(ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID)).thenReturn(Optional.empty());
        when(ollamaClient.listModels(anyString()))
                .thenReturn(new OllamaModelListResult(true, "Connected.", List.of()));

        ollamaSettingsService.listModels(null);

        verify(ollamaClient).listModels(new OllamaProperties().getBaseUrl());
    }
}
