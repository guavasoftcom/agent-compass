package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One effective tuning.* property. Only TuningProperties is rendered here — never "
        + "the Spring Environment, which would expose the datasource password on an unauthenticated "
        + "endpoint.")
public record ConfigurationEntry(
        @Schema(description = "Fully-qualified property name in kebab-case, exactly as it would be "
                + "written in application.yml", example = "tuning.api-request-event-name")
        String propertyName,

        @Schema(description = "Effective value. Lists render comma-separated, maps as key=value pairs.",
                example = "api_request") String value,

        @Schema(description = "Whether the effective value differs from the compiled-in default",
                example = "false") boolean overridden,

        @Schema(description = "Whether this value is also duplicated as a literal in migration SQL")
        SqlMirroring sqlMirroring,

        @Schema(description = "Migration versions carrying that literal, empty when not mirrored",
                example = "[\"V14\", \"V15\", \"V19\"]") List<String> mirroredIn,

        @Schema(description = "One-line summary of what the property drives",
                example = "event.name of the log carrying per-request cost, effort, and request id")
        String description) {
}
