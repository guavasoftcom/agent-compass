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
package com.guavasoft.agentcompass.service;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.BashCommandCoverage;
import com.guavasoft.agentcompass.model.BashCommandHotspot;
import com.guavasoft.agentcompass.model.EditFailureLoop;
import com.guavasoft.agentcompass.model.McpServerUsage;
import com.guavasoft.agentcompass.model.OversizedToolResult;
import com.guavasoft.agentcompass.model.PathNearMiss;
import com.guavasoft.agentcompass.model.RedundantFileRead;
import com.guavasoft.agentcompass.model.SlowAndLargeCall;
import com.guavasoft.agentcompass.model.ToolCallCount;
import com.guavasoft.agentcompass.model.ToolContextFootprint;
import com.guavasoft.agentcompass.model.ToolFailure;
import com.guavasoft.agentcompass.model.ToolPerformance;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReportService {

  private static final int SESSION_ID_DISPLAY_LENGTH = 8;
  private static final int ERROR_MESSAGE_DISPLAY_LENGTH = 120;
  private static final long SLOW_BASH_P95_MS = 5_000L;
  private static final long OVERSIZED_BYTES_THRESHOLD = 20_000L;
  private static final int CD_PREFIX_SUGGESTION_MIN_CALLS = 10;
  private static final String HUNTING_LOOP_PATTERN = "hunting loop";
  private static final String SPREAD_PATTERN = "spread across session";
  private static final String TIGHT_CLUSTER_PATTERN = "tight cluster";
  private static final String CTX_TOOL = "tool";
  private static final String CTX_CALLS = "calls";
  private static final String CTX_AVG_MS = "avgMs";
  private static final String CTX_P95_MS = "p95Ms";
  private static final String CTX_AVG_OUT = "avgOut";
  private static final String CTX_SESSION_ID = "sessionId";
  private static final String CTX_FILE_PATH = "filePath";
  private static final String BASH_DEDUP_KEY = "bash:";
  private static final String FILE_DEDUP_KEY = "file:";
  private static final String SCOPE_DEDUP_KEY = "scope:";
  private static final int OVERSIZED_SUGGESTION_LIMIT = 3;
  private static final int SLOW_AND_LARGE_SUGGESTION_LIMIT = 2;
  // A tool whose p95 result is this many times its own mean is not uniformly
  // chatty — a few blowout calls carry its total, which is a different fix
  // (cap the tail) from "this tool returns too much in general".
  private static final double CONTEXT_TAIL_P95_TO_MEAN_RATIO = 3.0;
  private static final long CONTEXT_TAIL_MINIMUM_CALLS = 10L;
  private static final int CONTEXT_TAIL_SUGGESTION_LIMIT = 2;
  private static final String SHARE_FORMAT = "%.1f%%";
  private static final String TRUNCATION_SUFFIX = "…";
  // An MCP server above this failure rate is a prime AGENTS.md tuning target, same order of
  // magnitude as the guidance ToolFailureRate's javadoc gives for any tool (>0.2-0.3), but set
  // lower here because a whole external server failing this often is worth flagging even before
  // it reaches "prime target" territory for a single built-in tool.
  private static final double MCP_SERVER_HIGH_FAILURE_RATE_THRESHOLD = 0.10;
  // A server whose share of MCP context bytes runs this many times ahead of its share of MCP
  // calls is spending disproportionately on context relative to how often it's actually used.
  private static final double MCP_SERVER_CONTEXT_SHARE_TO_CALL_SHARE_RATIO = 2.0;
  private static final int MCP_SERVER_SUGGESTION_LIMIT = 3;

  private final LogService logService;
  private final TuningProperties tuningProperties;
  private final Template template;
  private final Map<String, String> bashAntipatternReplacements;

  public ReportService(
      LogService logService,
      TuningProperties tuningProperties,
      Mustache.Compiler compiler,
      @Value("classpath:templates/report.mustache") Resource templateResource) throws IOException {
    this.logService = logService;
    this.tuningProperties = tuningProperties;
    this.template = compiler.compile(templateResource.getContentAsString(StandardCharsets.UTF_8));
    this.bashAntipatternReplacements = Map.copyOf(tuningProperties.getBashAntipatternReplacements());
  }

  public String renderMarkdown(int minutes) {
    Instant end = Instant.now();
    Instant start = end.minus(Duration.ofMinutes(minutes));
    return renderForWindow(start, end, minutes);
  }

  public String renderMarkdownInRange(Instant start, Instant end) {
    long minutes = Math.max(1, Duration.between(start, end).toMinutes());
    return renderForWindow(start, end, (int) Math.min(minutes, Integer.MAX_VALUE));
  }

  private String renderForWindow(Instant start, Instant end, int minutesForContext) {
    List<ToolCallCount> rows = logService.aggregateToolCallsInRange(start, end);
    long total = rows.stream().mapToLong(ToolCallCount::getCalls).sum();

    List<Map<String, Object>> rowsWithShare = rows.stream()
        .map(callCount -> {
          Map<String, Object> mixRow = new LinkedHashMap<>();
          mixRow.put(CTX_TOOL, callCount.getTool());
          mixRow.put(CTX_CALLS, callCount.getCalls());
          mixRow.put("share", formatShare(callCount.getCalls(), total));
          return mixRow;
        })
        .toList();

    List<ToolPerformance> performanceRows = logService.aggregateToolPerformanceInRange(start, end);
    List<Map<String, Object>> performanceContext = performanceRows.stream()
        .map(performance -> {
          Map<String, Object> performanceRow = new LinkedHashMap<>();
          performanceRow.put(CTX_TOOL, performance.tool());
          performanceRow.put(CTX_CALLS, performance.calls());
          performanceRow.put(CTX_AVG_MS, performance.avgDurationMs());
          performanceRow.put(CTX_P95_MS, performance.p95DurationMs());
          performanceRow.put(CTX_AVG_OUT, performance.avgResultBytes());
          return performanceRow;
        })
        .toList();

    List<ToolFailure> failureRows = logService.aggregateToolFailuresInRange(start, end);
    long totalFailures = failureRows.stream().mapToLong(ToolFailure::count).sum();
    List<Map<String, Object>> failuresContext = failureRows.stream()
        .map(failure -> {
          Map<String, Object> failureRow = new LinkedHashMap<>();
          failureRow.put(CTX_TOOL, failure.tool());
          failureRow.put("errorType", failure.errorType());
          failureRow.put("errorSignature", failure.errorSignature());
          failureRow.put("exampleScope", failure.exampleScope());
          failureRow.put("exampleMessage", truncate(failure.exampleMessage(), ERROR_MESSAGE_DISPLAY_LENGTH));
          failureRow.put("count", failure.count());
          return failureRow;
        })
        .toList();

    List<BashCommandHotspot> bashHotspots = logService.aggregateBashCommandHotspotsInRange(start, end);
    List<Map<String, Object>> bashHotspotsContext = bashHotspots.stream()
        .map(hotspot -> {
          Map<String, Object> hotspotRow = new LinkedHashMap<>();
          hotspotRow.put("commandPrefix", hotspot.commandPrefix());
          hotspotRow.put(CTX_CALLS, hotspot.calls());
          hotspotRow.put(CTX_AVG_MS, hotspot.avgDurationMs());
          hotspotRow.put(CTX_P95_MS, hotspot.p95DurationMs());
          hotspotRow.put(CTX_AVG_OUT, hotspot.avgResultBytes());
          return hotspotRow;
        })
        .toList();

    BashCommandCoverage bashCoverage = logService.bashCommandCoverageInRange(start, end);

    List<OversizedToolResult> oversized = logService.aggregateOversizedToolResultsInRange(start, end);
    List<Map<String, Object>> oversizedContext = oversized.stream()
        .map(result -> {
          Map<String, Object> oversizedRow = new LinkedHashMap<>();
          oversizedRow.put(CTX_TOOL, result.tool());
          oversizedRow.put("scope", result.scope());
          oversizedRow.put("bytes", result.bytes());
          oversizedRow.put("occurrences", result.occurrences());
          return oversizedRow;
        })
        .toList();

    List<ToolContextFootprint> contextFootprint =
        logService.aggregateTunableToolContextFootprintInRange(start, end);
    long contextFootprintTotalBytes = contextFootprint.stream()
        .mapToLong(ToolContextFootprint::totalBytes)
        .sum();
    List<Map<String, Object>> contextFootprintContext = contextFootprint.stream()
        .map(footprint -> {
          Map<String, Object> footprintRow = new LinkedHashMap<>();
          footprintRow.put(CTX_TOOL, footprint.tool());
          footprintRow.put(CTX_CALLS, footprint.calls());
          footprintRow.put("totalBytes", footprint.totalBytes());
          footprintRow.put("share", formatShare(footprint.totalBytes(), contextFootprintTotalBytes));
          footprintRow.put(CTX_AVG_OUT, averageBytes(footprint));
          footprintRow.put("p95Bytes", footprint.p95Bytes());
          return footprintRow;
        })
        .toList();

    List<McpServerUsage> mcpServerUsageRows = logService.aggregateMcpServerUsageInRange(start, end);
    List<McpServerRollup> mcpServerRollups = rollUpMcpServersByServer(mcpServerUsageRows);
    long mcpServersTotalBytes = mcpServerRollups.stream().mapToLong(McpServerRollup::totalBytes).sum();
    List<Map<String, Object>> mcpServersContext = mcpServerRollups.stream()
        .map(rollup -> {
          Map<String, Object> serverRow = new LinkedHashMap<>();
          serverRow.put("server", rollup.server());
          serverRow.put(CTX_CALLS, rollup.calls());
          serverRow.put("failureRate", formatShare(rollup.failures(), rollup.calls()));
          serverRow.put(CTX_P95_MS, rollup.p95DurationMs());
          serverRow.put("totalBytes", rollup.totalBytes());
          serverRow.put("share", formatShare(rollup.totalBytes(), mcpServersTotalBytes));
          return serverRow;
        })
        .toList();

    List<RedundantFileRead> redundantReads = logService.aggregateRedundantFileReadsInRange(start, end);
    List<Map<String, Object>> redundantReadsContext = redundantReads.stream()
        .map(redundantRead -> {
          Map<String, Object> readRow = new LinkedHashMap<>();
          readRow.put(CTX_SESSION_ID, truncate(redundantRead.sessionId(), SESSION_ID_DISPLAY_LENGTH));
          readRow.put(CTX_FILE_PATH, redundantRead.filePath());
          readRow.put("reads", redundantRead.reads());
          readRow.put("spanMinutes", redundantRead.spanMinutes());
          readRow.put("maxGapMinutes", redundantRead.maxGapMinutes());
          readRow.put("pattern", classifyReadPattern(redundantRead));
          return readRow;
        })
        .toList();

    List<EditFailureLoop> editLoops = logService.aggregateEditFailureLoopsInRange(start, end);
    List<Map<String, Object>> editLoopsContext = editLoops.stream()
        .map(loop -> {
          Map<String, Object> loopRow = new LinkedHashMap<>();
          loopRow.put(CTX_SESSION_ID, truncate(loop.sessionId(), SESSION_ID_DISPLAY_LENGTH));
          loopRow.put(CTX_FILE_PATH, loop.filePath());
          loopRow.put("failures", loop.failures());
          return loopRow;
        })
        .toList();

    List<SlowAndLargeCall> slowLarge = logService.aggregateSlowAndLargeCallsInRange(start, end);

    List<PathNearMiss> pathNearMisses = logService.findReadPathNearMissesInRange(start, end);
    List<Map<String, Object>> pathNearMissesContext = pathNearMisses.stream()
        .map(nearMiss -> {
          Map<String, Object> nearMissRow = new LinkedHashMap<>();
          nearMissRow.put(CTX_SESSION_ID, truncate(nearMiss.sessionId(), SESSION_ID_DISPLAY_LENGTH));
          nearMissRow.put("failedPath", nearMiss.failedPath());
          nearMissRow.put("nearestSuccessfulPath", nearMiss.nearestSuccessfulPath());
          nearMissRow.put("editDistance", nearMiss.editDistance());
          nearMissRow.put("failures", nearMiss.failures());
          return nearMissRow;
        })
        .toList();

    List<String> suggestions = buildSuggestions(
        bashHotspots, bashCoverage, oversized, contextFootprint, redundantReads, editLoops, slowLarge,
        mcpServerRollups);

    Map<String, Object> context = new HashMap<>();
    context.put("minutes", minutesForContext);
    context.put("since", start);
    context.put("eventName", tuningProperties.getToolEventName());
    context.put("hasRows", !rows.isEmpty());
    context.put("rows", rowsWithShare);
    context.put("total", total);
    context.put("hasSuggestions", !suggestions.isEmpty());
    context.put("suggestions", suggestions);
    context.put("hasPerformance", !performanceContext.isEmpty());
    context.put("performance", performanceContext);
    context.put("hasFailures", !failuresContext.isEmpty());
    context.put("failures", failuresContext);
    context.put("totalFailures", totalFailures);
    context.put("hasBashHotspots", !bashHotspotsContext.isEmpty());
    context.put("bashHotspots", bashHotspotsContext);
    context.put("bashCoverageWithCommand", bashCoverage.withCommand());
    context.put("bashCoverageTotal", bashCoverage.total());
    context.put("bashCoverageCdPrefixed", bashCoverage.cdPrefixed());
    context.put("hasPathNearMisses", !pathNearMissesContext.isEmpty());
    context.put("pathNearMisses", pathNearMissesContext);
    context.put("hasOversized", !oversizedContext.isEmpty());
    context.put("oversized", oversizedContext);
    context.put("hasContextFootprint", !contextFootprintContext.isEmpty());
    context.put("contextFootprint", contextFootprintContext);
    context.put("contextFootprintTotalBytes", contextFootprintTotalBytes);
    context.put("contextFootprintEstimatedTokens", contextFootprint.stream()
        .mapToLong(ToolContextFootprint::estimatedTokens)
        .sum());
    context.put("hasMcpServers", !mcpServersContext.isEmpty());
    context.put("mcpServers", mcpServersContext);
    context.put("mcpServersTotalBytes", mcpServersTotalBytes);
    context.put("mcpServersEstimatedTokens", mcpServerUsageRows.stream()
        .mapToLong(McpServerUsage::estimatedTokens)
        .sum());
    context.put("hasRedundantReads", !redundantReadsContext.isEmpty());
    context.put("redundantReads", redundantReadsContext);
    context.put("hasEditLoops", !editLoopsContext.isEmpty());
    context.put("editLoops", editLoopsContext);
    return template.execute(context);
  }

  /**
   * One MCP server, rolled up across every tool row {@link com.guavasoft.agentcompass.model.McpServerUsage}
   * reports for it. p95DurationMs is the MAX of the per-tool p95s rather than a recomputed
   * per-server percentile — averaging or re-deriving a percentile from already-aggregated per-tool
   * percentiles is not statistically valid, whereas MAX gives a defensible worst-case bound for the
   * "is this server worth its latency cost" question the report section answers. Order matches the
   * repository query: servers arrive together, sorted by total calls descending.
   */
  private record McpServerRollup(String server, long calls, long failures, long totalBytes, long p95DurationMs) {}

  private static List<McpServerRollup> rollUpMcpServersByServer(List<McpServerUsage> mcpServerUsageRows) {
    Map<String, long[]> totalsByServer = new LinkedHashMap<>();
    for (McpServerUsage usage : mcpServerUsageRows) {
      long[] totals = totalsByServer.computeIfAbsent(usage.server(), key -> new long[4]);
      totals[0] += usage.calls();
      totals[1] += usage.failures();
      totals[2] += usage.totalBytes();
      totals[3] = Math.max(totals[3], usage.p95DurationMs());
    }
    List<McpServerRollup> rollups = new ArrayList<>(totalsByServer.size());
    for (Map.Entry<String, long[]> entry : totalsByServer.entrySet()) {
      long[] totals = entry.getValue();
      rollups.add(new McpServerRollup(entry.getKey(), totals[0], totals[1], totals[2], totals[3]));
    }
    return rollups;
  }

  // Derive concrete tuning suggestions from the just-fetched data. Each rule is a
  // single
  // sentence that names a specific file path, command, or session — generic "Top
  // tool is X"
  // lines are intentionally omitted because they don't carry an actionable fix.
  //
  // The list must respect the same skip rules the report's "How to use" block
  // states: only
  // hunting-loop redundant reads (spread-across-session re-reads are normal
  // context refresh
  // in long sessions), no externally-determined tools (filtered upstream in the
  // queries),
  // and antipattern bullets name the concrete replacement tool instead of
  // pointing at a
  // rewrite table that doesn't exist.
  //
  // De-duplication: when multiple signals point at the same command prefix or
  // file path
  // we append context to the existing bullet rather than emitting a fresh one, so
  // the
  // ./mvnw slow-tail bullet absorbs its worst-single-call fact instead of
  // duplicating it.
  private List<String> buildSuggestions(
      List<BashCommandHotspot> bashHotspots,
      BashCommandCoverage bashCoverage,
      List<OversizedToolResult> oversized,
      List<ToolContextFootprint> contextFootprint,
      List<RedundantFileRead> redundantReads,
      List<EditFailureLoop> editLoops,
      List<SlowAndLargeCall> slowLarge,
      List<McpServerRollup> mcpServerRollups) {
    List<String> bullets = new ArrayList<>();
    Map<String, Integer> indexByKey = new HashMap<>();

    if (bashCoverage.cdPrefixed() >= CD_PREFIX_SUGGESTION_MIN_CALLS) {
      bullets.add(String.format(
          "%d of %d Bash commands lead with `cd <dir> && …` — prefer path-scoped invocations"
              + " (e.g. `<tool> -f <dir>/…` or `--cwd`), and fix any AGENTS.md run/build examples that"
              + " teach the `cd` form; the prefix hides the real command from latency attribution and"
              + " can trigger extra permission prompts.",
          bashCoverage.cdPrefixed(), bashCoverage.total()));
    }

    for (BashCommandHotspot hotspot : bashHotspots) {
      String replacementTool = bashAntipatternReplacements.get(hotspot.commandPrefix());
      boolean isSlow = hotspot.p95DurationMs() >= SLOW_BASH_P95_MS;
      if (replacementTool == null && !isSlow) {
        continue;
      }
      String bullet;
      if (replacementTool != null && isSlow) {
        bullet = String.format(
            "Bash `%s` (%d calls, p95 %d ms) — replace with `%s`; add the rule to AGENTS.md if it keeps recurring.",
            hotspot.commandPrefix(), hotspot.calls(), hotspot.p95DurationMs(), replacementTool);
      } else if (replacementTool != null) {
        bullet = String.format(
            "Bash `%s` (%d calls) — replace with `%s`; add the rule to AGENTS.md if it keeps recurring.",
            hotspot.commandPrefix(), hotspot.calls(), replacementTool);
      } else {
        bullet = String.format(
            "Bash `%s` is in the slow tail (p95 %d ms over %d calls) — narrow its scope or run it in"
                + " the background (builds and test suites are slow by nature — don't ban them).",
            hotspot.commandPrefix(), hotspot.p95DurationMs(), hotspot.calls());
      }
      indexByKey.put(BASH_DEDUP_KEY + hotspot.commandPrefix(), bullets.size());
      bullets.add(bullet);
    }

    int oversizedBulletCount = 0;
    for (OversizedToolResult result : oversized) {
      if (oversizedBulletCount >= OVERSIZED_SUGGESTION_LIMIT) {
        break;
      }
      if (result.bytes() < OVERSIZED_BYTES_THRESHOLD || result.scope().isEmpty()) {
        continue;
      }
      String key = SCOPE_DEDUP_KEY + result.scope();
      if (indexByKey.containsKey(key)) {
        continue;
      }
      indexByKey.put(key, bullets.size());
      String occurrenceSuffix = result.occurrences() > 1
          ? String.format(" × %d calls", result.occurrences())
          : "";
      bullets.add(String.format(
          "`%s` returned %d bytes%s via `%s` — page the read or replace dump-style output.",
          result.tool(), result.bytes(), occurrenceSuffix, result.scope()));
      oversizedBulletCount++;
    }

    // Tail-shape rule. The oversized list above names individual payloads; this one
    // names a tool whose *distribution* is the problem — a p95 several times its own
    // mean means the total is carried by a handful of blowouts, so the fix is a cap
    // on the worst calls rather than using the tool less. Tools that return a lot on
    // every call don't match (their p95 sits near their mean) and are already covered
    // by the oversized rows.
    contextFootprint.stream()
        .filter(footprint -> footprint.calls() >= CONTEXT_TAIL_MINIMUM_CALLS)
        .filter(footprint -> footprint.p95Bytes() >= OVERSIZED_BYTES_THRESHOLD)
        .filter(footprint -> footprint.p95Bytes()
            >= averageBytes(footprint) * CONTEXT_TAIL_P95_TO_MEAN_RATIO)
        .limit(CONTEXT_TAIL_SUGGESTION_LIMIT)
        .forEach(footprint -> bullets.add(String.format(
            "`%s` results average %d bytes but p95 is %d (%.1fx the mean over %d calls) — its context"
                + " share is driven by a few blowout calls, not the typical one, so cap the tail"
                + " (page the read, scope the glob, pipe dump-style output through `head`) rather"
                + " than discouraging the tool.",
            footprint.tool(),
            averageBytes(footprint),
            footprint.p95Bytes(),
            averageBytes(footprint) == 0 ? 0.0 : (double) footprint.p95Bytes() / averageBytes(footprint),
            footprint.calls())));

    // MCP server rule, beside the tail-shape rule above: flags a server whose failure rate or
    // context share is disproportionate to how often it's actually called — the same "is this
    // worth its cost" question the tail-shape rule asks of a single tool, one level up at the
    // server. A server can trip either condition without tripping the other (a chatty-but-reliable
    // server vs. a rarely-called one that eats context whenever it is).
    long totalMcpCalls = mcpServerRollups.stream().mapToLong(McpServerRollup::calls).sum();
    long totalMcpBytes = mcpServerRollups.stream().mapToLong(McpServerRollup::totalBytes).sum();
    mcpServerRollups.stream()
        .filter(rollup -> rollup.calls() > 0)
        .filter(rollup -> {
          double failureRate = (double) rollup.failures() / rollup.calls();
          double callShare = totalMcpCalls == 0 ? 0.0 : (double) rollup.calls() / totalMcpCalls;
          double byteShare = totalMcpBytes == 0 ? 0.0 : (double) rollup.totalBytes() / totalMcpBytes;
          boolean highFailureRate = failureRate >= MCP_SERVER_HIGH_FAILURE_RATE_THRESHOLD;
          boolean disproportionateContextShare = rollup.totalBytes() >= OVERSIZED_BYTES_THRESHOLD
              && byteShare >= callShare * MCP_SERVER_CONTEXT_SHARE_TO_CALL_SHARE_RATIO;
          return highFailureRate || disproportionateContextShare;
        })
        .limit(MCP_SERVER_SUGGESTION_LIMIT)
        .forEach(rollup -> bullets.add(String.format(
            "MCP server `%s` (%d calls, %.1f%% failure rate, %d bytes of context) — check whether it "
                + "earns its cost: narrow which of its tools the agent is allowed to call, or drop the "
                + "server if a built-in tool already covers the same need.",
            rollup.server(),
            rollup.calls(),
            100.0 * rollup.failures() / rollup.calls(),
            rollup.totalBytes())));

    redundantReads.stream()
        .filter(redundantRead -> HUNTING_LOOP_PATTERN.equals(classifyReadPattern(redundantRead)))
        .limit(3)
        .forEach(redundantRead -> {
          String key = FILE_DEDUP_KEY + redundantRead.filePath();
          indexByKey.put(key, bullets.size());
          bullets.add(String.format(
              "`%s` was re-read %d times inside %d min in session `%s` (hunting loop) — add a rule for"
                  + " the workflow that touches this file: read it once up front (paged if large) and"
                  + " trust Edit/Write results instead of re-reading to verify.",
              redundantRead.filePath(),
              redundantRead.reads(),
              redundantRead.spanMinutes(),
              truncate(redundantRead.sessionId(), SESSION_ID_DISPLAY_LENGTH)));
        });

    editLoops.stream()
        .limit(3)
        .forEach(loop -> {
          String key = FILE_DEDUP_KEY + loop.filePath();
          Integer existing = indexByKey.get(key);
          if (existing != null) {
            bullets.set(existing, bullets.get(existing) + String.format(
                " Edit also failed %d times against this path — use larger `old_string` or `replace_all`.",
                loop.failures()));
          } else {
            indexByKey.put(key, bullets.size());
            bullets.add(String.format(
                "Edit failed %d times against `%s` in session `%s` — use a larger `old_string` or `replace_all`.",
                loop.failures(),
                loop.filePath(),
                truncate(loop.sessionId(), SESSION_ID_DISPLAY_LENGTH)));
          }
        });

    Set<Integer> bulletsWithWorstCallFact = new HashSet<>();
    slowLarge.stream()
        .limit(SLOW_AND_LARGE_SUGGESTION_LIMIT)
        .forEach(call -> {
          String prefix = firstToken(call.scope());
          Integer existing = prefix == null ? null : indexByKey.get(BASH_DEDUP_KEY + prefix);
          if (existing != null) {
            String worstCallFact = bulletsWithWorstCallFact.add(existing)
                ? " Worst single call: %d ms, %d bytes (`%s`)."
                : " Also %d ms, %d bytes (`%s`).";
            bullets.set(existing, bullets.get(existing) + String.format(
                worstCallFact,
                call.durationMs(),
                call.bytes(),
                call.scope()));
          } else {
            bullets.add(String.format(
                "Single `%s` call took %d ms AND returned %d bytes (scope `%s`) — the worst offender on both axes.",
                call.tool(), call.durationMs(), call.bytes(),
                call.scope()));
          }
        });

    return bullets;
  }

  /**
   * Mean result size for one footprint row. The aggregation counts only calls that reported a
   * size, so this divides by the same population the p95 was taken over — the two are comparable.
   */
  private static long averageBytes(ToolContextFootprint footprint) {
    if (footprint.calls() == 0) {
      return 0L;
    }
    return footprint.totalBytes() / footprint.calls();
  }

  private static String firstToken(String scope) {
    if (scope == null || scope.isEmpty()) {
      return null;
    }
    int spaceIndex = scope.indexOf(' ');
    return spaceIndex < 0 ? scope : scope.substring(0, spaceIndex);
  }

  private static String classifyReadPattern(RedundantFileRead redundantRead) {
    if (redundantRead.reads() >= 3 && redundantRead.maxGapMinutes() <= 5) {
      return HUNTING_LOOP_PATTERN;
    }
    if (redundantRead.maxGapMinutes() >= 30) {
      return SPREAD_PATTERN;
    }
    return TIGHT_CLUSTER_PATTERN;
  }

  private static String formatShare(long calls, long total) {
    if (total == 0) {
      return "0.0%";
    }
    return String.format(SHARE_FORMAT, 100.0 * calls / total);
  }

  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return StringUtils.EMPTY;
    }
    if (value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength) + TRUNCATION_SUFFIX;
  }
}
