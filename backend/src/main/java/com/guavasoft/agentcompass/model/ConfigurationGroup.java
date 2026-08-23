package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A themed set of tuning.* properties, so the Settings page can present 47 "
        + "properties as something other than one flat list")
public record ConfigurationGroup(
        @Schema(description = "Group name", example = "LLM requests & cost") String name,

        @Schema(description = "What the properties in this group collectively drive",
                example = "Correlating api_request logs to spans, and the cost and effort read off them")
        String description,

        @Schema(description = "Properties in this group, in declaration order")
        List<ConfigurationEntry> entries) {
}
