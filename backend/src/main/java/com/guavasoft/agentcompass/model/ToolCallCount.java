package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "ToolCallCount", description = "Aggregated tool call count over the requested window")
public class ToolCallCount {

  @Schema(description = "Tool name as reported by the agent (e.g. \"Read\", \"Bash\")", example = "Read")
  private String tool;

  @Schema(description = "Total number of calls observed in the window", example = "142")
  private long calls;
}
