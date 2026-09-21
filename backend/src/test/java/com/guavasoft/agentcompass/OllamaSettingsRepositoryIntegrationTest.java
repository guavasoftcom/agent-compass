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
package com.guavasoft.agentcompass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.guavasoft.agentcompass.entity.OllamaSettingsEntity;
import com.guavasoft.agentcompass.repository.OllamaSettingsRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@code ollama_settings} singleton row's upsert-by-fixed-id behavior against a real
 * Postgres — the CHECK (id = 1) constraint plus {@code save()} on the same id is what makes a
 * Settings-page edit a plain overwrite rather than a growing history table.
 */
@SpringBootTest
@Testcontainers
class OllamaSettingsRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.NAME);

    @Autowired
    OllamaSettingsRepository ollamaSettingsRepository;

    @BeforeEach
    void clearSettings() {
        ollamaSettingsRepository.deleteAll();
    }

    @Test
    void savingTwiceAgainstTheSameIdOverwritesRatherThanInserting() {
        OllamaSettingsEntity first = new OllamaSettingsEntity();
        first.setBaseUrl("http://localhost:11434");
        first.setModel("llama3.1");
        first.setUpdatedAt(Instant.now());
        ollamaSettingsRepository.save(first);

        OllamaSettingsEntity second = new OllamaSettingsEntity();
        second.setBaseUrl("http://localhost:22222");
        second.setModel("qwen2.5:14b");
        second.setUpdatedAt(Instant.now());
        ollamaSettingsRepository.save(second);

        assertThat(ollamaSettingsRepository.count()).isEqualTo(1);
        OllamaSettingsEntity stored = ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID).orElseThrow();
        assertThat(stored.getBaseUrl()).isEqualTo("http://localhost:22222");
        assertThat(stored.getModel()).isEqualTo("qwen2.5:14b");
    }

    @Test
    void aNullFieldPersistsAsNullRatherThanBeingRejected() {
        OllamaSettingsEntity entity = new OllamaSettingsEntity();
        entity.setModel("qwen2.5:14b");
        entity.setUpdatedAt(Instant.now());
        ollamaSettingsRepository.save(entity);

        OllamaSettingsEntity stored = ollamaSettingsRepository.findById(OllamaSettingsEntity.SINGLETON_ID).orElseThrow();
        assertThat(stored.getBaseUrl()).isNull();
        assertThat(stored.getModel()).isEqualTo("qwen2.5:14b");
    }
}
