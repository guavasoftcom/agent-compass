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
package com.guavasoft.agentcompass.update;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.guavasoft.agentcompass.config.UpdateCheckProperties;

/**
 * Asks GitHub which release is newest. One unauthenticated {@code GET}, carrying no identifier of
 * this installation — no version, no host, no telemetry — beyond what any HTTP request reveals.
 *
 * <p>Every failure surfaces as an {@link UpdateCheckException} whose message is fit to show the
 * operator as written. The caller treats one as an ordinary result rather than an error, since
 * "this machine cannot reach GitHub" is the expected state of many deployments.
 */
@Component
public class GitHubReleaseClient {

    private static final String GITHUB_MEDIA_TYPE = "application/vnd.github+json";
    private static final String GITHUB_API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String GITHUB_API_VERSION = "2022-11-28";

    /** GitHub rejects a request that carries no {@code User-Agent}. */
    private static final String USER_AGENT = "agent-compass-update-check";

    private static final String LATEST_RELEASE_PATH_FORMAT = "/repos/%s/releases/latest";

    private final RestClient restClient;
    private final String latestReleasePath;

    @Autowired
    public GitHubReleaseClient(RestClient.Builder restClientBuilder, UpdateCheckProperties updateCheckProperties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(updateCheckProperties.getConnectTimeout());
        requestFactory.setReadTimeout(updateCheckProperties.getReadTimeout());
        this.restClient = restClientBuilder
                .baseUrl(updateCheckProperties.getApiBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, GITHUB_MEDIA_TYPE)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(GITHUB_API_VERSION_HEADER, GITHUB_API_VERSION)
                .build();
        this.latestReleasePath = LATEST_RELEASE_PATH_FORMAT.formatted(updateCheckProperties.getRepository());
    }

    /**
     * Test seam: accepts a ready-made {@link RestClient} (one a test bound a
     * {@code MockRestServiceServer} to), because the public constructor's {@code requestFactory(...)}
     * call would silently replace a mock server's request factory.
     */
    GitHubReleaseClient(RestClient restClient, String repository) {
        this.restClient = restClient;
        this.latestReleasePath = LATEST_RELEASE_PATH_FORMAT.formatted(repository);
    }

    public LatestRelease fetchLatestRelease() {
        GitHubReleaseResponse response;
        try {
            response = restClient.get().uri(latestReleasePath).retrieve().body(GitHubReleaseResponse.class);
        } catch (RestClientResponseException e) {
            throw new UpdateCheckException(messageForStatus(e.getStatusCode().value()), e);
        } catch (ResourceAccessException e) {
            throw new UpdateCheckException("Could not reach GitHub. Is this machine offline?", e);
        } catch (RestClientException e) {
            throw new UpdateCheckException("GitHub sent a response this version could not read.", e);
        }
        if (response == null || response.tagName() == null) {
            throw new UpdateCheckException("GitHub did not report a latest release.");
        }
        ReleaseVersion version = ReleaseVersion.parse(response.tagName())
                .orElseThrow(() -> new UpdateCheckException(
                        "The latest release is tagged '%s', which is not a version number.".formatted(response.tagName())));
        return new LatestRelease(version, response.htmlUrl(), response.publishedAt());
    }

    private static String messageForStatus(int statusCode) {
        if (statusCode == HttpStatus.NOT_FOUND.value()) {
            return "No release has been published yet, or the repository is private.";
        }
        if (statusCode == HttpStatus.FORBIDDEN.value() || statusCode == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return "GitHub is rate-limiting requests from this network. Try again later.";
        }
        return "GitHub answered with HTTP %d.".formatted(statusCode);
    }
}
