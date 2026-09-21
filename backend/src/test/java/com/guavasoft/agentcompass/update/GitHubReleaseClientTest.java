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

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubReleaseClientTest {

    private static final String API_BASE_URL = "https://api.github.com";
    private static final String REPOSITORY = "guavasoftcom/agent-compass";
    private static final String LATEST_RELEASE_URL = API_BASE_URL + "/repos/" + REPOSITORY + "/releases/latest";

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private final MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
    private final GitHubReleaseClient client =
            new GitHubReleaseClient(restClientBuilder.baseUrl(API_BASE_URL).build(), REPOSITORY);

    @Test
    void readsTheVersionUrlAndPublishDateOfTheLatestRelease() {
        mockServer.expect(requestTo(LATEST_RELEASE_URL))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"tag_name":"v2.8.0",
                         "html_url":"https://github.com/guavasoftcom/agent-compass/releases/tag/v2.8.0",
                         "published_at":"2026-09-20T18:31:04Z",
                         "id":392620456,"draft":false,"prerelease":false,
                         "author":{"login":"github-actions[bot]","id":41898282,"type":"Bot"},
                         "assets":[],"assets_url":"https://api.github.com/repos/x/y/releases/1/assets{?name,label}",
                         "body":"## What's Changed\\n* chore: upgrade backend to Java 25 by @someone in #61"}""",
                        MediaType.APPLICATION_JSON));

        LatestRelease release = client.fetchLatestRelease();

        assertThat(release.version()).isEqualTo(new ReleaseVersion(2, 8, 0));
        assertThat(release.url()).isEqualTo("https://github.com/guavasoftcom/agent-compass/releases/tag/v2.8.0");
        assertThat(release.publishedAt()).isEqualTo(Instant.parse("2026-09-20T18:31:04Z"));
        mockServer.verify();
    }

    @Test
    void aMissingReleaseIsReportedAsNoReleaseYetOrPrivate() {
        mockServer.expect(requestTo(LATEST_RELEASE_URL))
                .andRespond(withRawStatus(404));

        assertThatThrownBy(client::fetchLatestRelease)
                .isInstanceOf(UpdateCheckException.class)
                .hasMessageContaining("No release has been published yet");
    }

    @Test
    void aRateLimitedRequestIsReportedAsSuchWhetherGitHubSays403Or429() {
        mockServer.expect(requestTo(LATEST_RELEASE_URL))
                .andRespond(withRawStatus(403));
        assertThatThrownBy(client::fetchLatestRelease)
                .isInstanceOf(UpdateCheckException.class)
                .hasMessageContaining("rate-limiting");

        mockServer.reset();
        mockServer.expect(requestTo(LATEST_RELEASE_URL))
                .andRespond(withRawStatus(429));
        assertThatThrownBy(client::fetchLatestRelease)
                .isInstanceOf(UpdateCheckException.class)
                .hasMessageContaining("rate-limiting");
    }

    @Test
    void anyOtherErrorStatusNamesTheStatus() {
        mockServer.expect(requestTo(LATEST_RELEASE_URL)).andRespond(withServerError());

        assertThatThrownBy(client::fetchLatestRelease)
                .isInstanceOf(UpdateCheckException.class)
                .hasMessage("GitHub answered with HTTP 500.");
    }

    @Test
    void anUnreachableNetworkIsReportedAsOffline() {
        mockServer.expect(requestTo(LATEST_RELEASE_URL))
                .andRespond(withException(new IOException("connect timed out")));

        assertThatThrownBy(client::fetchLatestRelease)
                .isInstanceOf(UpdateCheckException.class)
                .hasMessageContaining("Could not reach GitHub");
    }

    @Test
    void aTagThatIsNotAVersionNumberIsRejectedRatherThanComparedAsText() {
        mockServer.expect(requestTo(LATEST_RELEASE_URL))
                .andRespond(withSuccess(
                        "{\"tag_name\":\"nightly\",\"html_url\":\"https://example.test\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::fetchLatestRelease)
                .isInstanceOf(UpdateCheckException.class)
                .hasMessageContaining("'nightly'");
    }

    @Test
    void aResponseWithNoTagIsReportedAsNoLatestRelease() {
        mockServer.expect(requestTo(LATEST_RELEASE_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::fetchLatestRelease)
                .isInstanceOf(UpdateCheckException.class)
                .hasMessageContaining("did not report a latest release");
    }
}
