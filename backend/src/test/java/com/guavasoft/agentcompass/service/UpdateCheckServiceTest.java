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
import org.springframework.boot.info.BuildProperties;

import com.guavasoft.agentcompass.config.UpdateCheckProperties;
import com.guavasoft.agentcompass.entity.UpdateCheckSettingsEntity;
import com.guavasoft.agentcompass.model.UpdateCheckStatus;
import com.guavasoft.agentcompass.repository.UpdateCheckSettingsRepository;
import com.guavasoft.agentcompass.update.GitHubReleaseClient;
import com.guavasoft.agentcompass.update.LatestRelease;
import com.guavasoft.agentcompass.update.ReleaseVersion;
import com.guavasoft.agentcompass.update.UpdateCheckException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateCheckServiceTest {

    private static final Instant START = Instant.parse("2026-09-21T09:00:00Z");
    private static final String RELEASE_URL = "https://github.com/guavasoftcom/agent-compass/releases/tag/v2.8.0";
    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-20T18:31:04Z");

    @Mock
    UpdateCheckSettingsRepository updateCheckSettingsRepository;

    @Mock
    GitHubReleaseClient gitHubReleaseClient;

    UpdateCheckProperties updateCheckProperties = new UpdateCheckProperties();
    AdvanceableClock clock = new AdvanceableClock(START);

    @BeforeEach
    void noStoredOverrideByDefault() {
        lenient().when(updateCheckSettingsRepository.findById(UpdateCheckSettingsEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());
    }

    @Test
    void reportsAnUpdateWhenTheLatestReleaseIsNewerThanTheRunningVersion() {
        when(gitHubReleaseClient.fetchLatestRelease()).thenReturn(release(2, 8, 0));

        UpdateCheckStatus status = serviceRunning("2.7.1-SNAPSHOT").status(false);

        assertThat(status.enabled()).isTrue();
        assertThat(status.currentVersion()).isEqualTo("2.7.1");
        assertThat(status.latestVersion()).isEqualTo("2.8.0");
        assertThat(status.updateAvailable()).isTrue();
        assertThat(status.releaseUrl()).isEqualTo(RELEASE_URL);
        assertThat(status.publishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(status.checkedAt()).isEqualTo(START);
        assertThat(status.message()).isNull();
    }

    /** A released image of vX.Y.Z reports X.Y.Z-SNAPSHOT; being on the latest release is not an update. */
    @Test
    void reportsUpToDateWhenTheRunningSnapshotIsTheLatestRelease() {
        when(gitHubReleaseClient.fetchLatestRelease()).thenReturn(release(2, 8, 0));

        UpdateCheckStatus status = serviceRunning("2.8.0-SNAPSHOT").status(false);

        assertThat(status.updateAvailable()).isFalse();
        assertThat(status.latestVersion()).isEqualTo("2.8.0");
    }

    @Test
    void doesNotOfferADowngradeWhenRunningAheadOfTheLatestRelease() {
        when(gitHubReleaseClient.fetchLatestRelease()).thenReturn(release(2, 8, 0));

        assertThat(serviceRunning("2.9.0-SNAPSHOT").status(false).updateAvailable()).isFalse();
    }

    /** The switch is enforced server-side: off means no outbound request, whatever the client asks for. */
    @Test
    void makesNoRequestAtAllWhenTheCheckIsSwitchedOff() {
        UpdateCheckSettingsEntity stored = new UpdateCheckSettingsEntity();
        stored.setEnabled(false);
        when(updateCheckSettingsRepository.findById(UpdateCheckSettingsEntity.SINGLETON_ID))
                .thenReturn(Optional.of(stored));

        UpdateCheckStatus status = serviceRunning("2.7.1-SNAPSHOT").status(true);

        assertThat(status.enabled()).isFalse();
        assertThat(status.currentVersion()).isEqualTo("2.7.1");
        assertThat(status.latestVersion()).isNull();
        assertThat(status.updateAvailable()).isFalse();
        verifyNoInteractions(gitHubReleaseClient);
    }

    @Test
    void aStoredOverrideBeatsTheApplicationDefault() {
        updateCheckProperties.setEnabled(false);
        UpdateCheckSettingsEntity stored = new UpdateCheckSettingsEntity();
        stored.setEnabled(true);
        when(updateCheckSettingsRepository.findById(UpdateCheckSettingsEntity.SINGLETON_ID))
                .thenReturn(Optional.of(stored));
        when(gitHubReleaseClient.fetchLatestRelease()).thenReturn(release(2, 8, 0));

        assertThat(serviceRunning("2.7.1-SNAPSHOT").status(false).enabled()).isTrue();
    }

    @Test
    void fallsBackToTheApplicationDefaultWhenTheStoredRowHoldsNoOverride() {
        updateCheckProperties.setEnabled(false);
        when(updateCheckSettingsRepository.findById(UpdateCheckSettingsEntity.SINGLETON_ID))
                .thenReturn(Optional.of(new UpdateCheckSettingsEntity()));

        assertThat(serviceRunning("2.7.1-SNAPSHOT").status(false).enabled()).isFalse();
        verifyNoInteractions(gitHubReleaseClient);
    }

    @Test
    void aDevelopmentBuildHasNothingToCompareAgainstAndMakesNoRequest() {
        UpdateCheckStatus status = serviceWithNoBuildInfo().status(false);

        assertThat(status.enabled()).isTrue();
        assertThat(status.currentVersion()).isEqualTo("dev");
        assertThat(status.updateAvailable()).isFalse();
        assertThat(status.message()).contains("development build");
        verifyNoInteractions(gitHubReleaseClient);
    }

    @Test
    void reusesASuccessfulAnswerUntilTheCacheTtlLapses() {
        when(gitHubReleaseClient.fetchLatestRelease()).thenReturn(release(2, 8, 0));
        UpdateCheckService service = serviceRunning("2.7.1-SNAPSHOT");

        service.status(false);
        clock.advance(updateCheckProperties.getCacheTtl().minusSeconds(1));
        service.status(false);
        verify(gitHubReleaseClient, times(1)).fetchLatestRelease();

        clock.advance(Duration.ofSeconds(1));
        service.status(false);
        verify(gitHubReleaseClient, times(2)).fetchLatestRelease();
    }

    @Test
    void forceRefreshBypassesAFreshCache() {
        when(gitHubReleaseClient.fetchLatestRelease()).thenReturn(release(2, 8, 0));
        UpdateCheckService service = serviceRunning("2.7.1-SNAPSHOT");

        service.status(false);
        service.status(true);

        verify(gitHubReleaseClient, times(2)).fetchLatestRelease();
    }

    @Test
    void reportsAFailureAsAMessageNotAnExceptionAndRetriesOnlyAfterTheFailureDelay() {
        when(gitHubReleaseClient.fetchLatestRelease())
                .thenThrow(new UpdateCheckException("Could not reach GitHub. Is this machine offline?"))
                .thenReturn(release(2, 8, 0));
        UpdateCheckService service = serviceRunning("2.7.1-SNAPSHOT");

        UpdateCheckStatus failed = service.status(false);
        assertThat(failed.enabled()).isTrue();
        assertThat(failed.latestVersion()).isNull();
        assertThat(failed.updateAvailable()).isFalse();
        assertThat(failed.message()).isEqualTo("Could not reach GitHub. Is this machine offline?");
        assertThat(failed.checkedAt()).isEqualTo(START);

        // Still inside the retry window: no second attempt, so an offline machine is not made to
        // wait out a connect timeout on every page load.
        clock.advance(updateCheckProperties.getFailureRetryDelay().minusSeconds(1));
        assertThat(service.status(false).message()).isNotNull();
        verify(gitHubReleaseClient, times(1)).fetchLatestRelease();

        clock.advance(Duration.ofSeconds(1));
        UpdateCheckStatus recovered = service.status(false);
        assertThat(recovered.message()).isNull();
        assertThat(recovered.updateAvailable()).isTrue();
        verify(gitHubReleaseClient, times(2)).fetchLatestRelease();
    }

    /**
     * The mocked repository hands back the same entity the service edits, so the status that
     * follows the save reads the value just written — as the real repository's next read would.
     */
    @Test
    void turningTheSwitchOffUpsertsTheSingletonRowAndMakesNoRequest() {
        UpdateCheckSettingsEntity stored = new UpdateCheckSettingsEntity();
        stored.setEnabled(true);
        when(updateCheckSettingsRepository.findById(UpdateCheckSettingsEntity.SINGLETON_ID))
                .thenReturn(Optional.of(stored));

        UpdateCheckStatus status = serviceRunning("2.7.1-SNAPSHOT").updateSettings(false);

        ArgumentCaptor<UpdateCheckSettingsEntity> saved = ArgumentCaptor.forClass(UpdateCheckSettingsEntity.class);
        verify(updateCheckSettingsRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(UpdateCheckSettingsEntity.SINGLETON_ID);
        assertThat(saved.getValue().getEnabled()).isFalse();
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(START);
        assertThat(status.enabled()).isFalse();
        verifyNoInteractions(gitHubReleaseClient);
    }

    @Test
    void turningTheSwitchOnAnswersInTheSameCall() {
        UpdateCheckSettingsEntity stored = new UpdateCheckSettingsEntity();
        stored.setEnabled(false);
        when(updateCheckSettingsRepository.findById(UpdateCheckSettingsEntity.SINGLETON_ID))
                .thenReturn(Optional.of(stored));
        when(gitHubReleaseClient.fetchLatestRelease()).thenReturn(release(2, 8, 0));

        UpdateCheckStatus status = serviceRunning("2.7.1-SNAPSHOT").updateSettings(true);

        assertThat(status.enabled()).isTrue();
        assertThat(status.updateAvailable()).isTrue();
    }

    @Test
    void savingANullSwitchClearsTheOverrideBackToTheDefault() {
        UpdateCheckSettingsEntity stored = new UpdateCheckSettingsEntity();
        stored.setEnabled(false);
        when(updateCheckSettingsRepository.findById(UpdateCheckSettingsEntity.SINGLETON_ID))
                .thenReturn(Optional.of(stored));
        when(gitHubReleaseClient.fetchLatestRelease()).thenReturn(release(2, 8, 0));

        UpdateCheckStatus status = serviceRunning("2.7.1-SNAPSHOT").updateSettings(null);

        assertThat(stored.getEnabled()).isNull();
        assertThat(status.enabled()).isEqualTo(new UpdateCheckProperties().isEnabled());
    }

    private UpdateCheckService serviceRunning(String applicationVersion) {
        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", applicationVersion);
        return new UpdateCheckService(updateCheckSettingsRepository, updateCheckProperties, gitHubReleaseClient,
                Optional.of(new BuildProperties(buildInfo)), clock);
    }

    private UpdateCheckService serviceWithNoBuildInfo() {
        return new UpdateCheckService(updateCheckSettingsRepository, updateCheckProperties, gitHubReleaseClient,
                Optional.empty(), clock);
    }

    private static LatestRelease release(int major, int minor, int patch) {
        return new LatestRelease(new ReleaseVersion(major, minor, patch), RELEASE_URL, PUBLISHED_AT);
    }

    /** A {@link Clock} the test moves by hand, so cache expiry is asserted rather than slept through. */
    private static final class AdvanceableClock extends Clock {

        private Instant now;

        AdvanceableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
