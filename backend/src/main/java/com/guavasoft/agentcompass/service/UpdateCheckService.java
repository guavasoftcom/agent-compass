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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

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
import java.util.Optional;

/**
 * Answers "is a newer release out?" for the Settings page, and owns the switch that turns the
 * question off.
 *
 * <p><b>The switch is enforced here, not in the UI.</b> With the check off, {@link #status} returns
 * before any network call is made, so a stale tab or a script that ignores the toggle still cannot
 * make this application talk to GitHub — the same rule {@code TraceAnalysisService} applies to the
 * Ollama toggle. The effective value is the stored {@code update_check_settings} override, else
 * {@code update-check.enabled}.
 *
 * <p>An answer is cached in memory: {@code update-check.cache-ttl} after a success, the shorter
 * {@code update-check.failure-retry-delay} after a failure, so a machine with no route out waits out
 * a connect timeout once per retry window and not on every page load. A restart clears it, which is
 * fine — at most one request per launch.
 *
 * <p>Deliberately not {@code @Transactional}: the lookup is a network call and must not hold a
 * pooled connection open, the same reasoning {@link OllamaSettingsService} gives.
 */
@Service
public class UpdateCheckService {

    private static final String DEVELOPMENT_VERSION = "dev";
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";
    private static final String DEVELOPMENT_BUILD_MESSAGE =
            "This is a development build, so there is no release to compare against.";

    private final UpdateCheckSettingsRepository updateCheckSettingsRepository;
    private final UpdateCheckProperties updateCheckProperties;
    private final GitHubReleaseClient gitHubReleaseClient;
    private final Optional<BuildProperties> buildProperties;
    private final Clock clock;

    private final Object lookupLock = new Object();
    private CachedLookup cachedLookup;

    @Autowired
    public UpdateCheckService(
            UpdateCheckSettingsRepository updateCheckSettingsRepository,
            UpdateCheckProperties updateCheckProperties,
            GitHubReleaseClient gitHubReleaseClient,
            Optional<BuildProperties> buildProperties) {
        this(updateCheckSettingsRepository, updateCheckProperties, gitHubReleaseClient, buildProperties,
                Clock.systemUTC());
    }

    /** Test seam: a fixed or advanceable {@link Clock} makes cache expiry assertable. */
    UpdateCheckService(
            UpdateCheckSettingsRepository updateCheckSettingsRepository,
            UpdateCheckProperties updateCheckProperties,
            GitHubReleaseClient gitHubReleaseClient,
            Optional<BuildProperties> buildProperties,
            Clock clock) {
        this.updateCheckSettingsRepository = updateCheckSettingsRepository;
        this.updateCheckProperties = updateCheckProperties;
        this.gitHubReleaseClient = gitHubReleaseClient;
        this.buildProperties = buildProperties;
        this.clock = clock;
    }

    /**
     * The current answer. {@code forceRefresh} skips the cache — for the operator pressing "Check
     * now", never for a page load.
     */
    public UpdateCheckStatus status(boolean forceRefresh) {
        boolean enabled = effectiveEnabled();
        String currentVersion = currentVersion();
        if (!enabled) {
            return new UpdateCheckStatus(false, currentVersion, null, false, null, null, null, null);
        }
        Optional<ReleaseVersion> runningVersion = ReleaseVersion.parse(currentVersion);
        if (runningVersion.isEmpty()) {
            return new UpdateCheckStatus(true, currentVersion, null, false, null, null, null, DEVELOPMENT_BUILD_MESSAGE);
        }

        CachedLookup lookup = lookup(forceRefresh);
        if (lookup.release() == null) {
            return new UpdateCheckStatus(
                    true, currentVersion, null, false, null, null, lookup.checkedAt(), lookup.failureMessage());
        }
        LatestRelease latestRelease = lookup.release();
        boolean updateAvailable = latestRelease.version().compareTo(runningVersion.get()) > 0;
        return new UpdateCheckStatus(
                true, currentVersion, latestRelease.version().toString(), updateAvailable,
                latestRelease.url(), latestRelease.publishedAt(), lookup.checkedAt(), null);
    }

    /**
     * Upserts the singleton switch row and returns the status that results, so turning the check on
     * answers in the same round trip. A null {@code enabled} clears the override back to
     * {@code update-check.enabled}; the Settings page's toggle always sends an explicit boolean.
     */
    public UpdateCheckStatus updateSettings(Boolean enabled) {
        UpdateCheckSettingsEntity entity = updateCheckSettingsRepository
                .findById(UpdateCheckSettingsEntity.SINGLETON_ID)
                .orElseGet(UpdateCheckSettingsEntity::new);
        entity.setId(UpdateCheckSettingsEntity.SINGLETON_ID);
        entity.setEnabled(enabled);
        entity.setUpdatedAt(Instant.now(clock));
        updateCheckSettingsRepository.save(entity);
        return status(false);
    }

    private boolean effectiveEnabled() {
        return updateCheckSettingsRepository.findById(UpdateCheckSettingsEntity.SINGLETON_ID)
                .map(UpdateCheckSettingsEntity::getEnabled)
                .orElseGet(updateCheckProperties::isEnabled);
    }

    /** The running version without its {@code -SNAPSHOT} suffix, or {@code dev} when unpackaged. */
    private String currentVersion() {
        return buildProperties
                .map(BuildProperties::getVersion)
                .map(version -> version.endsWith(SNAPSHOT_SUFFIX)
                        ? version.substring(0, version.length() - SNAPSHOT_SUFFIX.length())
                        : version)
                .orElse(DEVELOPMENT_VERSION);
    }

    /**
     * One lookup at a time: two page loads racing on a cold cache make one request, not two. The
     * lock is held across the network call, bounded by the client's connect and read timeouts.
     */
    private CachedLookup lookup(boolean forceRefresh) {
        synchronized (lookupLock) {
            Instant now = Instant.now(clock);
            if (!forceRefresh && cachedLookup != null && cachedLookup.isFreshAt(now, updateCheckProperties)) {
                return cachedLookup;
            }
            try {
                cachedLookup = new CachedLookup(gitHubReleaseClient.fetchLatestRelease(), null, now);
            } catch (UpdateCheckException e) {
                cachedLookup = new CachedLookup(null, e.getMessage(), now);
            }
            return cachedLookup;
        }
    }

    /** One remembered outcome: either a release or the reason there is none. */
    private record CachedLookup(LatestRelease release, String failureMessage, Instant checkedAt) {

        boolean isFreshAt(Instant now, UpdateCheckProperties properties) {
            Duration lifetime = release != null ? properties.getCacheTtl() : properties.getFailureRetryDelay();
            return now.isBefore(checkedAt.plus(lifetime));
        }
    }
}
