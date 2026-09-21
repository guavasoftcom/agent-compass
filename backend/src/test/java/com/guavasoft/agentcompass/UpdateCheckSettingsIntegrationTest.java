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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.guavasoft.agentcompass.entity.UpdateCheckSettingsEntity;
import com.guavasoft.agentcompass.repository.UpdateCheckSettingsRepository;
import com.guavasoft.agentcompass.service.UpdateCheckService;
import com.guavasoft.agentcompass.update.GitHubReleaseClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Exercises the {@code update_check_settings} singleton row against a real Postgres — the V36
 * migration, the entity mapping Hibernate validates at startup, and the property the Settings page
 * relies on: a switch saved as off is still off on the next read, and while it is off nothing is
 * sent to GitHub. The GitHub client is a mock so this test can never reach the real network.
 */
@SpringBootTest
@Testcontainers
class UpdateCheckSettingsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.NAME);

    @MockitoBean
    GitHubReleaseClient gitHubReleaseClient;

    @Autowired
    UpdateCheckSettingsRepository updateCheckSettingsRepository;

    @Autowired
    UpdateCheckService updateCheckService;

    @BeforeEach
    void clearSettings() {
        updateCheckSettingsRepository.deleteAll();
    }

    @Test
    void savingTwiceAgainstTheSameIdOverwritesRatherThanInserting() {
        UpdateCheckSettingsEntity first = new UpdateCheckSettingsEntity();
        first.setEnabled(true);
        first.setUpdatedAt(Instant.now());
        updateCheckSettingsRepository.save(first);

        UpdateCheckSettingsEntity second = new UpdateCheckSettingsEntity();
        second.setEnabled(false);
        second.setUpdatedAt(Instant.now());
        updateCheckSettingsRepository.save(second);

        assertThat(updateCheckSettingsRepository.count()).isEqualTo(1);
        assertThat(updateCheckSettingsRepository.findById(UpdateCheckSettingsEntity.SINGLETON_ID).orElseThrow()
                .getEnabled()).isFalse();
    }

    @Test
    void aNullSwitchPersistsAsNullRatherThanBeingRejected() {
        UpdateCheckSettingsEntity entity = new UpdateCheckSettingsEntity();
        entity.setUpdatedAt(Instant.now());
        updateCheckSettingsRepository.save(entity);

        assertThat(updateCheckSettingsRepository.findById(UpdateCheckSettingsEntity.SINGLETON_ID).orElseThrow()
                .getEnabled()).isNull();
    }

    @Test
    void aSwitchSavedAsOffSurvivesTheNextReadAndSendsNothing() {
        assertThat(updateCheckService.updateSettings(false).enabled()).isFalse();

        assertThat(updateCheckService.status(true).enabled()).isFalse();
        verifyNoInteractions(gitHubReleaseClient);
    }

    @Test
    void aSwitchSavedAsOnIsOnAtTheNextRead() {
        assertThat(updateCheckService.updateSettings(true).enabled()).isTrue();

        assertThat(updateCheckService.status(false).enabled()).isTrue();
    }
}
