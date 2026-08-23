package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Every tuning.* property the running instance resolved, with the SQL-mirroring "
        + "warning attached. Defaults match Claude Code's emission shape; an override that is flagged "
        + "MIRRORED but has no accompanying migration is the failure mode this endpoint exists to "
        + "make visible, because it produces empty pages rather than errors.")
public record EffectiveConfiguration(
        @Schema(description = "Properties grouped by what they drive") List<ConfigurationGroup> groups,

        @Schema(description = "Total properties across every group. A completeness test asserts this "
                + "equals the field count on TuningProperties, so a newly added property fails the "
                + "build until it is classified.", example = "47") int propertyCount,

        @Schema(description = "How many properties differ from their compiled-in default", example = "2")
        int overriddenCount) {
}
