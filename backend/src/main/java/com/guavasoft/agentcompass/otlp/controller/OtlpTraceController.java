package com.guavasoft.agentcompass.otlp.controller;

import com.google.protobuf.InvalidProtocolBufferException;
import com.guavasoft.agentcompass.otlp.service.OtlpTraceService;

import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
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
@RequestMapping("/v1/traces")
@Tag(name = "OTLP Ingest", description = "OpenTelemetry Protocol HTTP receiver for trace data")
public class OtlpTraceController {

    private final OtlpTraceService otlpTraceService;

    @PostMapping(
            consumes = MediaType.APPLICATION_PROTOBUF_VALUE,
            produces = MediaType.APPLICATION_PROTOBUF_VALUE)
    @Operation(
            summary = "Receive an OTLP ExportTraceServiceRequest and persist its spans",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "OTLP ExportTraceServiceRequest protobuf payload (binary)",
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
            otlpTraceService.ingestProtobuf(body);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PROTOBUF)
                    .body(ExportTraceServiceResponse.newBuilder().build().toByteArray());
        } catch (InvalidProtocolBufferException e) {
            log.warn("Rejected malformed OTLP traces protobuf payload", e);
            ExportTraceServiceResponse response = ExportTraceServiceResponse.newBuilder()
                    .setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                            .setErrorMessage("malformed protobuf: " + e.getMessage())
                            .build())
                    .build();
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROTOBUF)
                    .body(response.toByteArray());
        }
    }
}
