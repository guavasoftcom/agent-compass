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
package com.guavasoft.agentcompass.ollama;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.model.OllamaConnectionTestResult;
import com.guavasoft.agentcompass.model.OllamaModelListResult;
import com.guavasoft.agentcompass.model.OllamaModelListResult.OllamaModelSummary;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaClientTest {

    private static final String BASE_URL = "http://localhost:11434";
    private static final String MODEL = "llama3.1";

    @Test
    void generateReturnsTheModelResponseTextOnSuccess() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/generate"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"model\":\"llama3.1\",\"response\":\"This trace looks fine.\",\"done\":true}",
                        MediaType.APPLICATION_JSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        assertThat(client.generate("analyze this trace", BASE_URL, MODEL)).isEqualTo("This trace looks fine.");
        mockServer.verify();
    }

    /**
     * Without an explicit {@code options.num_ctx}, Ollama applies its own default context window
     * and SILENTLY left-truncates any longer prompt — dropping the instructions at the top of the
     * trace-analysis prompt with no error anywhere. This asserts the field is actually on the wire.
     *
     * <p>A short prompt gets a window sized to itself rather than the configured maximum:
     * {@code num_ctx} is what the KV cache is allocated from, so sending the ceiling on every call
     * made small ones — the review's second, "Apply this" call above all — reserve memory they
     * cannot use. 300 characters is 101 estimated tokens on top of the 4096-token answer headroom.
     */
    @Test
    void generateSendsAnExplicitContextWindowSoOllamaCannotSilentlyTruncateThePrompt() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/generate"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.options.num_ctx").value(4096 + 101))
                .andExpect(jsonPath("$.stream").value(true))
                .andRespond(withSuccess(
                        "{\"model\":\"llama3.1\",\"response\":\"ok\",\"done\":true}", MediaType.APPLICATION_JSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        client.generate("x".repeat(300), BASE_URL, MODEL);
        mockServer.verify();
    }

    /**
     * The other half of the sizing, and the one that must never be got wrong: a prompt big enough to
     * need more than the floor gets a window that actually holds it. The estimate deliberately
     * over-counts tokens (3 chars each, not 4), so this asserts a window comfortably above the
     * prompt rather than an exact figure — undersizing is the failure Ollama does not report.
     */
    @Test
    void aLongPromptGetsAContextWindowLargeEnoughToHoldItUpToTheConfiguredCeiling() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        String longPrompt = "x".repeat(30_000);

        mockServer.expect(requestTo(BASE_URL + "/api/generate"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.options.num_ctx").value(30_000 / 3 + 1 + 4096))
                .andRespond(withSuccess(
                        "{\"model\":\"llama3.1\",\"response\":\"ok\",\"done\":true}", MediaType.APPLICATION_JSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        client.generate(longPrompt, BASE_URL, MODEL);
        mockServer.verify();
    }

    /** And it never exceeds what the operator configured, whatever the prompt length implies. */
    @Test
    void theSizedContextWindowIsClampedToTheConfiguredCeiling() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/generate"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.options.num_ctx").value(new OllamaProperties().getContextTokens()))
                .andRespond(withSuccess(
                        "{\"model\":\"llama3.1\",\"response\":\"ok\",\"done\":true}", MediaType.APPLICATION_JSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        client.generate("x".repeat(200_000), BASE_URL, MODEL);
        mockServer.verify();
    }

    /**
     * The whole point of streaming: the trace-analysis dialog shows the review being written rather
     * than holding a spinner for the length of a local inference run. Asserts both halves — every
     * fragment reaches the listener in order, and the caller still gets the complete answer back, so
     * the listener is a tee rather than a replacement for the return value.
     */
    @Test
    void generateHandsEachAnswerFragmentToTheListenerAndStillReturnsTheWholeAnswer() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/generate"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"model":"llama3.1","response":"This trace ","done":false}
                        {"model":"llama3.1","response":"reads one file ","done":false}
                        {"model":"llama3.1","response":"twice.","done":true}
                        """, MediaType.APPLICATION_NDJSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);
        List<String> fragments = new ArrayList<>();

        String answer = client.generate("analyze this trace", BASE_URL, MODEL, null, fragments::add);

        assertThat(fragments).containsExactly("This trace ", "reads one file ", "twice.");
        assertThat(answer).isEqualTo("This trace reads one file twice.");
        mockServer.verify();
    }

    /**
     * Ollama reports a run that dies partway — a model unloaded, an out-of-memory abort — as an
     * {@code error} field on a chunk of an otherwise-200 response. A parser that only watches the
     * HTTP status would return the fragments written so far as if they were the whole review, and
     * {@code TraceAnalysisService} would store that truncated text as a finished analysis.
     */
    @Test
    void generateThrowsOllamaUnavailableWhenAChunkCarriesAnInBandError() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/generate"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"model":"llama3.1","response":"This trace ","done":false}
                        {"error":"model runner has unexpectedly stopped"}
                        """, MediaType.APPLICATION_NDJSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        assertThatThrownBy(() -> client.generate("analyze this trace", BASE_URL, MODEL))
                .isInstanceOf(OllamaUnavailableException.class)
                .hasMessageContaining("model runner has unexpectedly stopped");
    }

    @Test
    void generateThrowsOllamaUnavailableWhenOllamaReturnsAnErrorStatus() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/generate"))
                .andExpect(method(POST))
                .andRespond(withServerError().body("model \"llama3.1\" not found"));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        assertThatThrownBy(() -> client.generate("analyze this trace", BASE_URL, MODEL))
                .isInstanceOf(OllamaUnavailableException.class)
                .hasMessageContaining("Ollama returned an error");
    }

    @Test
    void generateThrowsOllamaUnavailableWhenOllamaIsUnreachable() throws IOException {
        int freePort = findAFreePort();
        String unreachableBaseUrl = "http://localhost:" + freePort;
        OllamaClient client = new OllamaClient(RestClient.builder(), new OllamaProperties(), JsonMapper.builder().build());

        assertThatThrownBy(() -> client.generate("analyze this trace", unreachableBaseUrl, MODEL))
                .isInstanceOf(OllamaUnavailableException.class)
                .hasMessageContaining("Could not reach Ollama");
    }

    @Test
    void testConnectionReturnsSuccessWhenOllamaAnswers() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/tags"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"models\":[]}", MediaType.APPLICATION_JSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        OllamaConnectionTestResult result = client.testConnection(BASE_URL);

        assertThat(result.success()).isTrue();
        mockServer.verify();
    }

    @Test
    void testConnectionReturnsFailureRatherThanThrowingWhenOllamaIsUnreachable() throws IOException {
        int freePort = findAFreePort();
        String unreachableBaseUrl = "http://localhost:" + freePort;
        OllamaClient client = new OllamaClient(RestClient.builder(), new OllamaProperties(), JsonMapper.builder().build());

        OllamaConnectionTestResult result = client.testConnection(unreachableBaseUrl);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Could not reach Ollama");
    }

    @Test
    void listModelsReturnsTheInstalledModelNamesAndParameterSizesOnSuccess() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/tags"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"models":[
                            {"name":"llama3.1:latest","details":{"parameter_size":"8.0B"}},
                            {"name":"qwen2.5:14b","details":{"parameter_size":"13B"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        OllamaModelListResult result = client.listModels(BASE_URL);

        assertThat(result.success()).isTrue();
        assertThat(result.models()).containsExactly(
                new OllamaModelSummary("llama3.1:latest", "8.0B", 8.0),
                new OllamaModelSummary("qwen2.5:14b", "13B", 13.0));
        mockServer.verify();
    }

    /**
     * A mixture-of-experts model's parameter size is reported as {@code "<experts>x<perExpert>B"};
     * the parsed count is their product — an approximation of total parameter count adequate for
     * "how big/slow is this" purposes.
     */
    @Test
    void listModelsMultipliesExpertCountByPerExpertSizeForAMixtureOfExpertsModel() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/tags"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"models":[{"name":"mixtral:8x7b","details":{"parameter_size":"8x7B"}}]}
                        """, MediaType.APPLICATION_JSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        OllamaModelListResult result = client.listModels(BASE_URL);

        assertThat(result.models()).containsExactly(new OllamaModelSummary("mixtral:8x7b", "8x7B", 56.0));
        mockServer.verify();
    }

    /** Some Ollama versions/model types report no {@code details} at all — this must not throw. */
    @Test
    void listModelsReturnsNullParameterSizeAndCountWhenDetailsAreAbsent() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/tags"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"models":[{"name":"custom:latest"}]}
                        """, MediaType.APPLICATION_JSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        OllamaModelListResult result = client.listModels(BASE_URL);

        assertThat(result.models()).containsExactly(new OllamaModelSummary("custom:latest", null, null));
        mockServer.verify();
    }

    /** A parameter-size string in neither recognized shape parses to null rather than throwing. */
    @Test
    void listModelsReturnsNullParameterCountForAnUnrecognizedParameterSizeShape() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/tags"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"models":[{"name":"mystery:latest","details":{"parameter_size":"large"}}]}
                        """, MediaType.APPLICATION_JSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        OllamaModelListResult result = client.listModels(BASE_URL);

        assertThat(result.models()).containsExactly(new OllamaModelSummary("mystery:latest", "large", null));
        mockServer.verify();
    }

    /** Neither a missing response body nor a missing/empty {@code models} field is ever a null list. */
    @Test
    void listModelsReturnsAnEmptyListRatherThanNullWhenTheModelsFieldIsAbsent() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/tags"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        OllamaModelListResult result = client.listModels(BASE_URL);

        assertThat(result.success()).isTrue();
        assertThat(result.models()).isEmpty();
        mockServer.verify();
    }

    @Test
    void listModelsReturnsFailureRatherThanThrowingWhenOllamaIsUnreachable() throws IOException {
        int freePort = findAFreePort();
        String unreachableBaseUrl = "http://localhost:" + freePort;
        OllamaClient client = new OllamaClient(RestClient.builder(), new OllamaProperties(), JsonMapper.builder().build());

        OllamaModelListResult result = client.listModels(unreachableBaseUrl);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Could not reach Ollama");
        assertThat(result.models()).isEmpty();
    }

    @Test
    void listModelsReturnsFailureRatherThanThrowingWhenOllamaReturnsAnErrorStatus() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockServer.expect(requestTo(BASE_URL + "/api/tags"))
                .andExpect(method(GET))
                .andRespond(withServerError().body("internal error"));

        RestClient fixedClient = restClientBuilder.baseUrl(BASE_URL).build();
        OllamaClient client = new OllamaClient(baseUrl -> fixedClient, baseUrl -> fixedClient);

        OllamaModelListResult result = client.listModels(BASE_URL);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Ollama returned an error");
        assertThat(result.models()).isEmpty();
        mockServer.verify();
    }

    /** Binds and immediately releases a port so the caller has a real, guaranteed-free one. */
    private static int findAFreePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
}
