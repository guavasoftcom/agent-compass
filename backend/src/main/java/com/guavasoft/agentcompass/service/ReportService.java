package com.guavasoft.agentcompass.service;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.BashCommandCoverage;
import com.guavasoft.agentcompass.model.BashCommandHotspot;
import com.guavasoft.agentcompass.model.EditFailureLoop;
import com.guavasoft.agentcompass.model.OversizedToolResult;
import com.guavasoft.agentcompass.model.PathNearMiss;
import com.guavasoft.agentcompass.model.RedundantFileRead;
import com.guavasoft.agentcompass.model.SlowAndLargeCall;
import com.guavasoft.agentcompass.model.ToolCallCount;
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
  private static final String SHARE_FORMAT = "%.1f%%";
  private static final String TRUNCATION_SUFFIX = "…";

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
        bashHotspots, bashCoverage, oversized, redundantReads, editLoops, slowLarge);

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
    context.put("hasRedundantReads", !redundantReadsContext.isEmpty());
    context.put("redundantReads", redundantReadsContext);
    context.put("hasEditLoops", !editLoopsContext.isEmpty());
    context.put("editLoops", editLoopsContext);
    return template.execute(context);
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
      List<RedundantFileRead> redundantReads,
      List<EditFailureLoop> editLoops,
      List<SlowAndLargeCall> slowLarge) {
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
