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
package com.guavasoft.agentcompass.otlp.controller;

import com.google.protobuf.InvalidProtocolBufferException;
import com.guavasoft.agentcompass.otlp.service.OtlpMetricService;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsPartialSuccess;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
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
@RequestMapping("/v1/metrics")
@Tag(name = "OTLP Ingest", description = "OpenTelemetry Protocol HTTP receiver for metric data")
public class OtlpMetricController {

    private final OtlpMetricService otlpMetricService;

    @PostMapping(
            consumes = MediaType.APPLICATION_PROTOBUF_VALUE,
            produces = MediaType.APPLICATION_PROTOBUF_VALUE)
    @Operation(
            summary = "Receive an OTLP ExportMetricsServiceRequest and persist its data points",
            description = "Standard OTLP/HTTP endpoint. Body must be a binary-encoded "
                    + "opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest. "
                    + "Point agents at this URL with OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf and "
                    + "OTEL_EXPORTER_OTLP_ENDPOINT pointed at this host.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "OTLP ExportMetricsServiceRequest protobuf payload (binary)",
                    required = true,
                    content = @Content(
                            mediaType = "application/x-protobuf",
                            schema = @Schema(type = "string", format = "binary"))))
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Ingestion succeeded. Response body is an empty ExportMetricsServiceResponse.",
                    content = @Content(
                            mediaType = "application/x-protobuf",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Malformed protobuf payload. Response body is an ExportMetricsServiceResponse "
                            + "with partial_success.error_message populated.",
                    content = @Content(
                            mediaType = "application/x-protobuf",
                            schema = @Schema(type = "string", format = "binary")))
    })
    public ResponseEntity<byte[]> receiveProtobuf(@RequestBody byte[] body) {
        try {
            otlpMetricService.ingestProtobuf(body);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PROTOBUF)
                    .body(ExportMetricsServiceResponse.newBuilder().build().toByteArray());
        } catch (InvalidProtocolBufferException e) {
            log.warn("Rejected malformed OTLP metrics protobuf payload", e);
            ExportMetricsServiceResponse response = ExportMetricsServiceResponse.newBuilder()
                    .setPartialSuccess(ExportMetricsPartialSuccess.newBuilder()
                            .setErrorMessage("malformed protobuf: " + e.getMessage())
                            .build())
                    .build();
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROTOBUF)
                    .body(response.toByteArray());
        }
    }
}
