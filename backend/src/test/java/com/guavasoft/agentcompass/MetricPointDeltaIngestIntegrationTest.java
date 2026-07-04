package com.guavasoft.agentcompass;

import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.repository.MetricPointRepository;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the ingest-time reset-aware delta computation (V11: stream_id +
 * value_delta) end-to-end through the real OTLP/HTTP path — POST /v1/metrics ->
 * {@link com.guavasoft.agentcompass.otlp.service.OtlpMetricService#ingestProtobuf} ->
 * {@link MetricPointRepository#recomputeValueDeltas} — rather than seeding rows
 * directly, so the actual ingest wiring (id collection after saveAll, the
 * correlated previous-row subquery) is what's under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MetricPointDeltaIngestIntegrationTest {

  private static final String TOKEN_METRIC = "claude_code.token.usage";

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @LocalServerPort
  int port;

  @Autowired
  MetricPointRepository metricPointRepository;

  private long baseTimeUnixNanos;

  @BeforeEach
  void clearMetricPoints() {
    metricPointRepository.deleteAll();
    baseTimeUnixNanos = Instant.now().minusSeconds(3_600).toEpochMilli() * 1_000_000L;
  }

  @Test
  void simpleIncreasingStreamProducesPerRowIncrements() {
    String sessionId = "delta-simple";
    postTokenBatch(List.of(
        dataPoint(sessionId, "main", 100, baseTimeUnixNanos),
        dataPoint(sessionId, "main", 250, baseTimeUnixNanos + secondsToNanos(60)),
        dataPoint(sessionId, "main", 400, baseTimeUnixNanos + secondsToNanos(120))));

    List<Double> deltas = orderedDeltasForSession(sessionId);
    assertThat(deltas).containsExactly(100.0, 150.0, 150.0);
  }

  @Test
  void counterResetIsCountedAsNewValueNeverNegative() {
    String sessionId = "delta-reset";
    postTokenBatch(List.of(
        dataPoint(sessionId, "main", 100, baseTimeUnixNanos),
        dataPoint(sessionId, "main", 250, baseTimeUnixNanos + secondsToNanos(60)),
        // Re-spawned run starts low again -> counted in full, never negative.
        dataPoint(sessionId, "main", 50, baseTimeUnixNanos + secondsToNanos(120))));

    List<Double> deltas = orderedDeltasForSession(sessionId);
    assertThat(deltas).containsExactly(100.0, 150.0, 50.0);
    assertThat(deltas).allMatch(delta -> delta >= 0.0);
  }

  @Test
  void concurrentStreamsDifferingOnlyByQuerySourceAreNotMerged() {
    String sessionId = "delta-concurrent";
    postTokenBatch(List.of(
        dataPoint(sessionId, "main", 1_000, baseTimeUnixNanos),
        dataPoint(sessionId, "subagent", 5_000, baseTimeUnixNanos),
        dataPoint(sessionId, "main", 1_200, baseTimeUnixNanos + secondsToNanos(60)),
        dataPoint(sessionId, "subagent", 5_300, baseTimeUnixNanos + secondsToNanos(60))));

    List<MetricPointEntity> rows = metricPointRepository.findAll().stream()
        .filter(row -> sessionId.equals(row.getAttributes().get("session.id")))
        .sorted(Comparator.comparing(MetricPointEntity::getTimestamp)
            .thenComparing(row -> (String) row.getAttributes().get("query_source")))
        .toList();

    // Distinct stream_id per query_source -> each stream's own reset-aware delta,
    // never merged into one combined value.
    List<String> streamIds = rows.stream().map(MetricPointEntity::getStreamId).distinct().toList();
    assertThat(streamIds).hasSize(2);

    double mainTotal = rows.stream()
        .filter(row -> "main".equals(row.getAttributes().get("query_source")))
        .mapToDouble(MetricPointEntity::getValueDelta)
        .sum();
    double subagentTotal = rows.stream()
        .filter(row -> "subagent".equals(row.getAttributes().get("query_source")))
        .mapToDouble(MetricPointEntity::getValueDelta)
        .sum();
    assertThat(mainTotal).isEqualTo(1_200.0);
    assertThat(subagentTotal).isEqualTo(5_300.0);
  }

  @Test
  void multiplePointsForOneStreamWithinASingleIngestBatchAreChained() {
    String sessionId = "delta-one-batch";
    // All three rows arrive in ONE OTLP export batch — the correlated
    // previous-row subquery must still chain them by (timestamp, id) rather than
    // seeing every row as the "first" one because they were inserted together.
    postTokenBatch(List.of(
        dataPoint(sessionId, "main", 300, baseTimeUnixNanos),
        dataPoint(sessionId, "main", 700, baseTimeUnixNanos + secondsToNanos(60)),
        dataPoint(sessionId, "main", 1_000, baseTimeUnixNanos + secondsToNanos(120))));

    List<Double> deltas = orderedDeltasForSession(sessionId);
    assertThat(deltas).containsExactly(300.0, 400.0, 300.0);
  }

  @Test
  void streamContinuingAcrossTwoIngestBatchesChainsAgainstThePriorBatch() {
    String sessionId = "delta-two-batches";
    postTokenBatch(List.of(dataPoint(sessionId, "main", 1_000, baseTimeUnixNanos)));
    postTokenBatch(List.of(dataPoint(sessionId, "main", 1_200, baseTimeUnixNanos + secondsToNanos(60))));

    List<Double> deltas = orderedDeltasForSession(sessionId);
    assertThat(deltas).containsExactly(1_000.0, 200.0);

    // A window query starting strictly after the first batch's row must NOT
    // count the second batch's row at its full cumulative value: value_delta was
    // computed once at ingest against the TRUE previous row (which sits outside
    // this window), so the window total is the real increment (200), not an
    // overcounted 1200.
    Instant midStreamWindowStart = Instant.ofEpochMilli(baseTimeUnixNanos / 1_000_000L).plusSeconds(30);
    Instant windowEnd = Instant.now().plusSeconds(3_600);
    Number windowTotal = firstScalar(
        metricPointRepository.aggregateTotalTokens(TOKEN_METRIC, midStreamWindowStart, windowEnd));
    assertThat(windowTotal).isNotNull();
    assertThat(windowTotal.longValue()).isEqualTo(200L);
  }

  private List<Double> orderedDeltasForSession(String sessionId) {
    return metricPointRepository.findAll().stream()
        .filter(row -> sessionId.equals(row.getAttributes().get("session.id")))
        .sorted(Comparator.comparing(MetricPointEntity::getTimestamp).thenComparing(MetricPointEntity::getId))
        .map(MetricPointEntity::getValueDelta)
        .toList();
  }

  private static Number firstScalar(List<Object[]> rows) {
    if (rows.isEmpty() || rows.get(0) == null || rows.get(0)[0] == null) {
      return null;
    }
    return (Number) rows.get(0)[0];
  }

  private static long secondsToNanos(long seconds) {
    return seconds * 1_000_000_000L;
  }

  private static NumberDataPoint dataPoint(String sessionId, String querySource, long tokenValue, long timeUnixNanos) {
    return NumberDataPoint.newBuilder()
        .setTimeUnixNano(timeUnixNanos)
        .setAsInt(tokenValue)
        .addAttributes(stringAttr("session.id", sessionId))
        .addAttributes(stringAttr("model", "opus"))
        .addAttributes(stringAttr("type", "input"))
        .addAttributes(stringAttr("query_source", querySource))
        .build();
  }

  private void postTokenBatch(List<NumberDataPoint> dataPoints) {
    Sum.Builder sum = Sum.newBuilder();
    dataPoints.forEach(sum::addDataPoints);
    Metric metric = Metric.newBuilder()
        .setName(TOKEN_METRIC)
        .setUnit("tokens")
        .setSum(sum.build())
        .build();

    ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
        .addResourceMetrics(ResourceMetrics.newBuilder()
            .setResource(Resource.newBuilder()
                .addAttributes(stringAttr("service.name", "claude-code"))
                .build())
            .addScopeMetrics(ScopeMetrics.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName("claude-code.metrics").build())
                .addMetrics(metric)
                .build())
            .build())
        .build();

    post("/v1/metrics", request.toByteArray());
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  private void post(String path, byte[] body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.valueOf("application/x-protobuf"));
    ResponseEntity<byte[]> response = new RestTemplate().exchange(
        baseUrl() + path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        byte[].class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
  }

  private static KeyValue stringAttr(String key, String value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value).build())
        .build();
  }
}
