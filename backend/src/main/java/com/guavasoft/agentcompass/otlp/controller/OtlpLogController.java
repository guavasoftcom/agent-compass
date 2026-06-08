package com.guavasoft.agentcompass.otlp.controller;

import com.google.protobuf.InvalidProtocolBufferException;
import com.guavasoft.agentcompass.otlp.service.OtlpLogService;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsPartialSuccess;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/logs")
@Tag(name = "OTLP Ingest", description = "OpenTelemetry Protocol HTTP receiver for log data")
public class OtlpLogController {

    private final OtlpLogService otlpLogService;

    @PostMapping(
            consumes = MediaType.APPLICATION_PROTOBUF_VALUE,
            produces = MediaType.APPLICATION_PROTOBUF_VALUE)
    @Operation(
            summary = "Receive an OTLP ExportLogsServiceRequest and persist its log records",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "OTLP ExportLogsServiceRequest protobuf payload (binary)",
                    required = true,
                    content = @Content(
                            mediaType = "application/x-protobuf",
                            schema = @Schema(type = "string", format = "binary"))))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ingestion succeeded"),
            @ApiResponse(responseCode = "400", description = "Malformed protobuf payload")
    })
    public ResponseEntity<byte[]> receiveProtobuf(@RequestBody byte[] body) {
        try {
            otlpLogService.ingestProtobuf(body);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PROTOBUF)
                    .body(ExportLogsServiceResponse.newBuilder().build().toByteArray());
        } catch (InvalidProtocolBufferException e) {
            log.warn("Rejected malformed OTLP logs protobuf payload", e);
            ExportLogsServiceResponse response = ExportLogsServiceResponse.newBuilder()
                    .setPartialSuccess(ExportLogsPartialSuccess.newBuilder()
                            .setErrorMessage("malformed protobuf: " + e.getMessage())
                            .build())
                    .build();
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROTOBUF)
                    .body(response.toByteArray());
        }
    }
}
