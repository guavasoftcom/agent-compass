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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.model.OllamaConnectionTestResult;
import com.guavasoft.agentcompass.model.OllamaModelListResult;
import com.guavasoft.agentcompass.model.OllamaModelListResult.OllamaModelSummary;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin HTTP client for a local Ollama server. No provider abstraction — this project targets Ollama
 * only.
 *
 * <p>{@code baseUrl} and {@code model} are runtime-editable from the Settings page (see
 * {@code OllamaSettingsService}), so neither is baked into a single {@link RestClient} built once at
 * startup: {@link #generate} and {@link #testConnection} both take the effective values as
 * parameters and build a request client per call from a {@code baseUrl -> RestClient} factory. The
 * factory itself is created once, in the constructor, purely to fix the connect/read timeouts (and,
 * for the ping path, a separate short timeout — a stuck {@code /api/tags} check should fail fast, not
 * wait out the 120s inference timeout).
 *
 * <p><b>Every generation call streams ({@code stream: true}), and there is deliberately no buffered
 * variant.</b> An earlier revision sent {@code stream: false} to avoid parsing Ollama's
 * newline-delimited JSON, since {@code RestClient.body(Class)} cannot deserialize it. The parser is
 * ~15 lines and buys two things worth more than that: the trace-analysis dialog can show the review
 * being written instead of holding a spinner for the length of a local inference run (the whole
 * point of {@code POST /api/traces/{traceId}/analysis/stream}), and the {@code ollama.read-timeout}
 * budget stops being a deadline for the entire answer. A socket read timeout is measured per read,
 * so with streaming it means "120s without a single token" — a genuinely stuck server — whereas
 * buffered it killed any answer that took longer than 120s to write in full, however healthy the
 * run. A caller that wants the old behaviour simply passes no listener: a one-chunk non-streamed
 * response is valid NDJSON and needs no separate code path.
 */
@Component
public class OllamaClient {

    private static final String GENERATE_PATH = "/api/generate";
    private static final String TAGS_PATH = "/api/tags";
    private static final boolean STREAM_ENABLED = true;

    /** Matches a plain parameter-size string like {@code "8.0B"} or {@code "70B"} (case-insensitive). */
    private static final Pattern PLAIN_PARAMETER_SIZE_PATTERN =
            Pattern.compile("^(\\d+(?:\\.\\d+)?)B$", Pattern.CASE_INSENSITIVE);

    /**
     * Matches a mixture-of-experts parameter-size string like {@code "8x7B"} — {@code expertCount}
     * experts of {@code parametersPerExpertBillions} billion parameters each. The two are multiplied
     * to approximate the total parameter count; this is a "how big/slow is this" estimate, not an
     * exact figure.
     */
    private static final Pattern MIXTURE_OF_EXPERTS_PARAMETER_SIZE_PATTERN =
            Pattern.compile("^(\\d+(?:\\.\\d+)?)[xX](\\d+(?:\\.\\d+)?)B$", Pattern.CASE_INSENSITIVE);

    /** Discards every fragment — what {@link #generate(String, String, String, Object)} passes. */
    private static final Consumer<String> IGNORE_ANSWER_FRAGMENTS = answerFragment -> {
    };

    /**
     * Read timeout for the "Test connection" ping only. Listing installed models is near-instant on
     * a running Ollama server, so this stays short regardless of {@code ollama.read-timeout} — a
     * user clicking "Test connection" should get an answer in seconds, not wait out the same budget
     * a real inference call gets.
     */
    private static final Duration PING_READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Characters per token used to size {@code num_ctx} for a given prompt — see
     * {@link #contextTokensFor}. <b>Deliberately 3 and not the usual 4.</b> The estimate must never
     * come out LOW: undersizing the context window is the one failure Ollama does not report, it
     * silently drops the front of the prompt (which is where the review's instructions live). Real
     * prompts here are dense with paths, JSON fragments and command lines, which tokenize closer to
     * 3 than to prose's 4, so this over-estimates on purpose.
     */
    private static final int PROMPT_CHARS_PER_TOKEN = 3;

    /**
     * Room reserved above the prompt for the model's own answer. A review runs to ~3k characters and
     * the second, "Apply this" call to a few hundred, so 4096 tokens is several times the largest
     * answer either call has produced.
     *
     * <p>It doubles as the floor on a sized window — every call gets at least this much whatever its
     * prompt length — which is why there is no separate minimum constant.
     */
    private static final int ANSWER_HEADROOM_TOKENS = 4_096;

    private final Function<String, RestClient> generateClientFactory;
    private final Function<String, RestClient> pingClientFactory;
    private final ObjectMapper objectMapper;
    private final int contextTokens;

    @Autowired
    public OllamaClient(RestClient.Builder restClientBuilder, OllamaProperties ollamaProperties, ObjectMapper objectMapper) {
        this.contextTokens = ollamaProperties.getContextTokens();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory generateRequestFactory = new SimpleClientHttpRequestFactory();
        generateRequestFactory.setConnectTimeout(ollamaProperties.getConnectTimeout());
        generateRequestFactory.setReadTimeout(ollamaProperties.getReadTimeout());

        SimpleClientHttpRequestFactory pingRequestFactory = new SimpleClientHttpRequestFactory();
        pingRequestFactory.setConnectTimeout(ollamaProperties.getConnectTimeout());
        pingRequestFactory.setReadTimeout(PING_READ_TIMEOUT);

        this.generateClientFactory = baseUrl -> restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(generateRequestFactory)
                .build();
        this.pingClientFactory = baseUrl -> restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(pingRequestFactory)
                .build();
    }

    /**
     * Test seam: accepts the two {@code baseUrl -> RestClient} factories directly (e.g. one closing
     * over a fixed {@link RestClient} a test bound a {@code MockRestServiceServer} to) rather than
     * building them from a {@link RestClient.Builder}. The public constructor's timeout-setting
     * {@code requestFactory(...)} call would otherwise silently discard a mock server's request
     * factory if applied after the fact, since the last {@code requestFactory(...)} call on a
     * builder always wins.
     */
    OllamaClient(Function<String, RestClient> generateClientFactory, Function<String, RestClient> pingClientFactory) {
        this.generateClientFactory = generateClientFactory;
        this.pingClientFactory = pingClientFactory;
        this.objectMapper = JsonMapper.builder().build();
        this.contextTokens = new OllamaProperties().getContextTokens();
    }

    /**
     * The context window to ask Ollama for on this particular call: enough for the prompt plus room
     * to answer, never more than {@code ollama.context-tokens} and never less than
     * {@link #MINIMUM_CONTEXT_TOKENS}.
     *
     * <p><b>Sized per call rather than sent as a constant, because {@code num_ctx} is what the KV
     * cache is allocated from</b> — it is the dominant memory cost of a local inference run, and it
     * is paid whether or not the prompt fills it. The configured value has to cover the largest
     * prompt this application builds (a capped findings prompt, ~8k tokens), so sending it
     * unconditionally made every small call reserve that much too. The review's second call, which
     * carries no timeline and runs to ~3k tokens, was asking for a 16k window to use a fifth of it.
     *
     * <p>The clamp to the configured maximum is what keeps the documented invariant intact: this can
     * only ever ask for LESS than the operator configured, so a value they sized against their own
     * hardware stays the ceiling. Undersizing is guarded from the other side by
     * {@link #PROMPT_CHARS_PER_TOKEN} being a deliberate over-estimate and by
     * {@link #ANSWER_HEADROOM_TOKENS} sitting on top of it — the failure mode for a too-small window
     * is Ollama silently truncating the front of the prompt, which is unrecoverable and invisible,
     * so every rounding here goes the safe way.
     */
    private int contextTokensFor(String prompt) {
        int estimatedPromptTokens = prompt.length() / PROMPT_CHARS_PER_TOKEN + 1;
        return Math.min(contextTokens, estimatedPromptTokens + ANSWER_HEADROOM_TOKENS);
    }

    /**
     * Runs one generation call against {@code baseUrl} for {@code model} and returns the whole
     * answer, discarding the fragments it arrived in.
     *
     * @throws OllamaUnavailableException when Ollama cannot be reached or returns an error status
     */
    public String generate(String prompt, String baseUrl, String model) {
        return generate(prompt, baseUrl, model, null);
    }

    /**
     * As {@link #generate(String, String, String)}, but constrains the answer to {@code jsonSchema}
     * via Ollama's {@code format} field — the returned string is then the JSON document itself, not
     * prose. A null schema is the plain prose call.
     *
     * @param jsonSchema a JSON Schema as nested {@code Map}/{@code List} values, serialized into the
     *     request body as-is
     */
    public String generate(String prompt, String baseUrl, String model, Object jsonSchema) {
        return generate(prompt, baseUrl, model, jsonSchema, IGNORE_ANSWER_FRAGMENTS);
    }

    /**
     * As {@link #generate(String, String, String, Object)}, but hands each fragment of the answer to
     * {@code answerListener} as it arrives, so a caller can report progress while the model is still
     * writing. The return value is the same complete answer either way — the listener is a tee, not
     * an alternative to it, so a caller that only wants progress still gets the whole text back and
     * a caller that wants neither passes {@link #IGNORE_ANSWER_FRAGMENTS} by using the shorter
     * overload.
     *
     * <p>The listener runs on the calling thread, inline with reading the socket, so a slow listener
     * slows the read. Callers that push somewhere blocking (an SSE connection, say) should coalesce
     * rather than forward every fragment.
     */
    public String generate(String prompt, String baseUrl, String model, Object jsonSchema, Consumer<String> answerListener) {
        OllamaGenerateRequest request = new OllamaGenerateRequest(
                model,
                prompt,
                STREAM_ENABLED,
                new OllamaGenerateRequest.OllamaGenerateOptions(contextTokensFor(prompt)),
                jsonSchema);
        try {
            return generateClientFactory.apply(baseUrl)
                    .post()
                    .uri(GENERATE_PATH)
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> readAnswer(clientResponse, answerListener));
        } catch (ResourceAccessException exception) {
            throw new OllamaUnavailableException(
                    "Could not reach Ollama at " + baseUrl + " — is it running?", exception);
        } catch (RestClientResponseException exception) {
            throw new OllamaUnavailableException(
                    "Ollama returned an error: " + exception.getResponseBodyAsString(), exception);
        } catch (RestClientException exception) {
            throw new OllamaUnavailableException(
                    "Ollama's answer could not be read: " + exception.getMessage(), exception);
        }
    }

    /**
     * Reads Ollama's newline-delimited JSON answer, appending each chunk's {@code response} fragment
     * to the accumulated text and handing it to {@code answerListener} on the way past.
     *
     * <p>Status is checked here rather than through {@code retrieve()}'s default error handling
     * because {@code exchange} is what gives access to the body as a stream rather than as an
     * already-buffered array. Ollama's in-band {@code error} field is checked too, and for the same
     * reason the class javadoc gives: it arrives on a 200 chunk, so a run that dies partway would
     * otherwise be stored as a complete — but silently truncated — review.
     */
    private String readAnswer(ClientHttpResponse clientResponse, Consumer<String> answerListener) throws IOException {
        if (clientResponse.getStatusCode().isError()) {
            throw new OllamaUnavailableException("Ollama returned an error: "
                    + new String(clientResponse.getBody().readAllBytes(), StandardCharsets.UTF_8));
        }
        StringBuilder answerBuilder = new StringBuilder();
        try (BufferedReader answerReader = new BufferedReader(
                new InputStreamReader(clientResponse.getBody(), StandardCharsets.UTF_8))) {
            String line = answerReader.readLine();
            while (line != null) {
                if (!line.isBlank() && !consumeChunk(line, answerBuilder, answerListener)) {
                    break;
                }
                line = answerReader.readLine();
            }
        }
        return answerBuilder.toString();
    }

    /**
     * Parses one NDJSON line and folds its fragment into the answer.
     *
     * @return false once the final chunk has been read, so the caller stops reading
     */
    private boolean consumeChunk(String line, StringBuilder answerBuilder, Consumer<String> answerListener) {
        OllamaGenerateResponse chunk;
        try {
            chunk = objectMapper.readValue(line, OllamaGenerateResponse.class);
        } catch (JacksonException exception) {
            // Not Ollama's NDJSON — most likely something in front of it (a proxy error page). Said
            // plainly rather than surfacing a Jackson stack trace as the dialog's error message.
            throw new OllamaUnavailableException(
                    "Ollama's answer was not in the expected format — is something other than Ollama "
                            + "answering at this address?", exception);
        }
        if (chunk.error() != null) {
            throw new OllamaUnavailableException("Ollama returned an error: " + chunk.error());
        }
        String fragment = chunk.response();
        if (fragment != null && !fragment.isEmpty()) {
            answerBuilder.append(fragment);
            answerListener.accept(fragment);
        }
        return !Boolean.TRUE.equals(chunk.done());
    }

    /**
     * Pings {@code GET /api/tags} — cheap and fast (lists installed models, runs no inference),
     * unlike {@link #generate}. Never throws: a failed connection is exactly what this check exists
     * to report, so the outcome is always a normal {@link OllamaConnectionTestResult}, not an
     * exception the caller has to translate.
     */
    public OllamaConnectionTestResult testConnection(String baseUrl) {
        try {
            pingClientFactory.apply(baseUrl).get().uri(TAGS_PATH).retrieve().toBodilessEntity();
            return new OllamaConnectionTestResult(true, "Connected to Ollama at " + baseUrl + ".");
        } catch (ResourceAccessException exception) {
            return new OllamaConnectionTestResult(
                    false, "Could not reach Ollama at " + baseUrl + " — is it running?");
        } catch (RestClientResponseException exception) {
            return new OllamaConnectionTestResult(
                    false, "Ollama returned an error: " + exception.getResponseBodyAsString());
        } catch (RestClientException exception) {
            return new OllamaConnectionTestResult(false, "Could not reach Ollama at " + baseUrl + ".");
        }
    }

    /**
     * Lists the models installed on the Ollama server at {@code baseUrl}, via the same
     * {@code GET /api/tags} call {@link #testConnection} pings — cheap and fast, no inference. Never
     * throws: a failed connection is a normal outcome for this check, so the outcome is always a
     * normal {@link OllamaModelListResult}, not an exception the caller has to translate.
     */
    public OllamaModelListResult listModels(String baseUrl) {
        try {
            OllamaTagsResponse tagsResponse =
                    pingClientFactory.apply(baseUrl).get().uri(TAGS_PATH).retrieve().body(OllamaTagsResponse.class);
            return new OllamaModelListResult(
                    true, "Connected to Ollama at " + baseUrl + ".", modelSummariesOf(tagsResponse));
        } catch (ResourceAccessException exception) {
            return new OllamaModelListResult(
                    false, "Could not reach Ollama at " + baseUrl + " — is it running?", List.of());
        } catch (RestClientResponseException exception) {
            return new OllamaModelListResult(
                    false, "Ollama returned an error: " + exception.getResponseBodyAsString(), List.of());
        } catch (RestClientException exception) {
            return new OllamaModelListResult(false, "Could not reach Ollama at " + baseUrl + ".", List.of());
        }
    }

    /** Empty (never null) when the response or its {@code models} field is absent. */
    private List<OllamaModelSummary> modelSummariesOf(OllamaTagsResponse tagsResponse) {
        if (tagsResponse == null || tagsResponse.models() == null) {
            return Collections.emptyList();
        }
        return tagsResponse.models().stream().map(this::toModelSummary).toList();
    }

    private OllamaModelSummary toModelSummary(OllamaTagsResponse.OllamaModelEntry modelEntry) {
        String parameterSize = modelEntry.details() == null ? null : modelEntry.details().parameterSize();
        return new OllamaModelSummary(modelEntry.name(), parameterSize, parseParameterCountBillions(parameterSize));
    }

    /**
     * Parses Ollama's free-form {@code details.parameter_size} string into a total parameter count
     * in billions, or {@code null} when the string is absent or matches neither recognized shape —
     * this never throws or guesses.
     *
     * <p>Recognized shapes: a plain {@code "<number>B"} (e.g. {@code "8.0B"} → 8.0), and a
     * mixture-of-experts {@code "<number>x<number>B"} (e.g. {@code "8x7B"} → 56.0, the product of
     * expert count and per-expert size — an approximation of total parameter count, adequate for
     * "how big/slow is this" purposes.
     */
    private static Double parseParameterCountBillions(String parameterSize) {
        if (parameterSize == null) {
            return null;
        }
        String trimmedParameterSize = parameterSize.trim();
        Matcher mixtureOfExpertsMatcher = MIXTURE_OF_EXPERTS_PARAMETER_SIZE_PATTERN.matcher(trimmedParameterSize);
        if (mixtureOfExpertsMatcher.matches()) {
            double expertCount = Double.parseDouble(mixtureOfExpertsMatcher.group(1));
            double parametersPerExpertBillions = Double.parseDouble(mixtureOfExpertsMatcher.group(2));
            return expertCount * parametersPerExpertBillions;
        }
        Matcher plainMatcher = PLAIN_PARAMETER_SIZE_PATTERN.matcher(trimmedParameterSize);
        if (plainMatcher.matches()) {
            return Double.parseDouble(plainMatcher.group(1));
        }
        return null;
    }

    /** Deserialization target for {@code GET /api/tags} — not part of this application's public API. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OllamaTagsResponse(List<OllamaModelEntry> models) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record OllamaModelEntry(String name, OllamaModelDetails details) {
        }

        /** Only the field this application currently needs off {@code details} — the rest is ignored. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record OllamaModelDetails(@JsonProperty("parameter_size") String parameterSize) {
        }
    }
}
