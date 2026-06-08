package com.guavasoft.agentcompass;

import com.google.protobuf.ByteString;
import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.model.ToolCallCount;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OtlpIngestIntegrationTest {

        @Container
        @ServiceConnection
        static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

        @LocalServerPort
        int port;

        @Autowired
        LogRecordRepository logRecordRepository;

        @Autowired
        SpanRepository spanRepository;

        @Test
        void ingestedOtlpToolResultLogsShowUpInToolCallsAggregation() {
                long timestampNanos = System.currentTimeMillis() * 1_000_000L;
                ScopeLogs.Builder scopeLogs = ScopeLogs.newBuilder()
                                .setScope(InstrumentationScope.newBuilder().setName("test-scope").build());
                for (int i = 0; i < 8; i++) {
                        scopeLogs.addLogRecords(toolResultLog(timestampNanos, "Read"));
                }
                scopeLogs.addLogRecords(toolResultLog(timestampNanos, "Bash"));

                ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
                                .addResourceLogs(ResourceLogs.newBuilder()
                                                .setResource(Resource.newBuilder()
                                                                .addAttributes(stringAttr("service.name",
                                                                                "claude-code"))
                                                                .build())
                                                .addScopeLogs(scopeLogs.build())
                                                .build())
                                .build();

                post("/v1/logs", request.toByteArray());

                ResponseEntity<List<ToolCallCount>> queryResponse = new RestTemplate().exchange(
                                baseUrl() + "/api/tool-activity/calls?minutes=1440",
                                HttpMethod.GET,
                                null,
                                new ParameterizedTypeReference<List<ToolCallCount>>() {
                                });

                List<ToolCallCount> rows = queryResponse.getBody();
                assertThat(rows).isNotNull();
                assertThat(rows).extracting(ToolCallCount::getTool).contains("Read", "Bash");
                ToolCallCount read = rows.stream().filter(r -> "Read".equals(r.getTool())).findFirst().orElseThrow();
                ToolCallCount bash = rows.stream().filter(r -> "Bash".equals(r.getTool())).findFirst().orElseThrow();
                assertThat(read.getCalls()).isEqualTo(8L);
                assertThat(bash.getCalls()).isEqualTo(1L);
                assertThat(rows.get(0).getTool()).isEqualTo("Read");
        }

        private static LogRecord toolResultLog(long timestampNanos, String toolName) {
                return LogRecord.newBuilder()
                                .setTimeUnixNano(timestampNanos)
                                .addAttributes(stringAttr("event.name", "tool_result"))
                                .addAttributes(stringAttr("tool_name", toolName))
                                .build();
        }

        @Test
        void ingestedOtlpLogsArePersisted() {
                long now = System.currentTimeMillis() * 1_000_000L;
                AnyValue editFailedBody = AnyValue.newBuilder()
                        .setStringValue("Edit failed: old_string not unique")
                        .build();
                ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
                                .addResourceLogs(ResourceLogs.newBuilder()
                                                .setResource(Resource.newBuilder()
                                                                .addAttributes(stringAttr("service.name",
                                                                                "claude-code"))
                                                                .build())
                                                .addScopeLogs(ScopeLogs.newBuilder()
                                                                .setScope(InstrumentationScope.newBuilder()
                                                                                .setName("claude-code.events").build())
                                                                .addLogRecords(LogRecord.newBuilder()
                                                                                .setTimeUnixNano(now)
                                                                                .setSeverityNumber(
                                                                                                SeverityNumber.SEVERITY_NUMBER_ERROR)
                                                                                .setSeverityText("ERROR")
                                                                                .setBody(editFailedBody)
                                                                                .addAttributes(stringAttr("event.name",
                                                                                                "tool_result"))
                                                                                .addAttributes(stringAttr("tool",
                                                                                                "Edit"))
                                                                                .build())
                                                                .build())
                                                .build())
                                .build();

                long beforeCount = logRecordRepository.count();
                post("/v1/logs", request.toByteArray());

                List<LogRecordEntity> all = logRecordRepository.findAll();
                assertThat(all).hasSize((int) beforeCount + 1);
                LogRecordEntity record = all.get(all.size() - 1);
                assertThat(record.getSeverityText()).isEqualTo("ERROR");
                assertThat(record.getSeverityNumber()).isEqualTo(SeverityNumber.SEVERITY_NUMBER_ERROR.getNumber());
                assertThat(record.getBody()).contains("old_string not unique");
                assertThat(record.getAttributes()).containsEntry("tool", "Edit");
                assertThat(record.getResourceAttributes()).containsEntry("service.name", "claude-code");
        }

        @Test
        void ingestedOtlpTracesArePersisted() {
                long start = System.currentTimeMillis() * 1_000_000L;
                long end = start + 25_000_000L; // 25ms span
                Status okStatus = Status.newBuilder()
                        .setCode(Status.StatusCode.STATUS_CODE_OK)
                        .build();
                ByteString traceId = ByteString.copyFrom(new byte[] {
                                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                                0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
                });
                ByteString spanId = ByteString.copyFrom(new byte[] { 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18 });

                ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                                .addResourceSpans(ResourceSpans.newBuilder()
                                                .setResource(Resource.newBuilder()
                                                                .addAttributes(stringAttr("service.name",
                                                                                "claude-code"))
                                                                .build())
                                                .addScopeSpans(ScopeSpans.newBuilder()
                                                                .setScope(InstrumentationScope.newBuilder()
                                                                                .setName("claude-code.tools").build())
                                                                .addSpans(Span.newBuilder()
                                                                                .setTraceId(traceId)
                                                                                .setSpanId(spanId)
                                                                                .setName("Bash")
                                                                                .setKind(Span.SpanKind.SPAN_KIND_INTERNAL)
                                                                                .setStartTimeUnixNano(start)
                                                                                .setEndTimeUnixNano(end)
                                                                                .setStatus(okStatus)
                                                                                .addAttributes(stringAttr("command",
                                                                                                "ls -la"))
                                                                                .build())
                                                                .build())
                                                .build())
                                .build();

                long beforeCount = spanRepository.count();
                post("/v1/traces", request.toByteArray());

                List<SpanEntity> all = spanRepository.findAll();
                assertThat(all).hasSize((int) beforeCount + 1);
                SpanEntity span = all.get(all.size() - 1);
                assertThat(span.getName()).isEqualTo("Bash");
                assertThat(span.getKind()).isEqualTo("internal");
                assertThat(span.getStatusCode()).isEqualTo("ok");
                assertThat(span.getTraceId()).isEqualTo("0102030405060708090a0b0c0d0e0f10");
                assertThat(span.getSpanId()).isEqualTo("1112131415161718");
                assertThat(span.getDurationNanos()).isEqualTo(25_000_000L);
                assertThat(span.getAttributes()).containsEntry("command", "ls -la");
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
