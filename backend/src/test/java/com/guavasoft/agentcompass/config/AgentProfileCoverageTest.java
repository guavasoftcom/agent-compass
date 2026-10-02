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
package com.guavasoft.agentcompass.config;

import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.repository.query.Param;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Guards the agent-profile partition against the one failure mode it has: a query that quietly
 * forgets it.
 *
 * <p>Agent profiles are a <b>hard partition</b>, not a filter. {@code repositoryUrl} reads null as
 * "every repository", which is correct because two repositories' telemetry shares one vocabulary and
 * one measurement pipeline. Two different agents do not: Copilot emits no cost metric at all and
 * reports tokens as an OTLP histogram where Claude Code reports a cumulative counter read through
 * {@code value_delta}. A query that omits the profile predicate therefore does not return "mixed"
 * data, it returns a number that is wrong — and wrong quietly, which is the worst kind.
 *
 * <p>There is no chokepoint to enforce this at. The repository filter is 126 hand-copied inline
 * predicates across the three repositories, with no shared SQL fragment or Specification, and the
 * profile predicate will be threaded the same way. So this test derives the <em>expectation</em> by
 * reflection, exactly as {@link TuningPropertyCatalogTest} does for the property catalog: a window
 * scoped query must either carry the parameter or be named in the debt list, and a newly added one
 * that does neither fails the build.
 *
 * <p><b>Why a debt list exists at all.</b> The read-path threading is deliberately NOT part of the
 * groundwork change that introduced the column, so at the time this test was written every
 * window-scoped query lacked the parameter. An unseeded assertion would have failed on all of them
 * at once and been commented out within a day. Seeding it makes the guard introducible now and lets
 * the read-path change drain it incrementally.
 *
 * <p><b>It is a ratchet, in both directions.</b> {@code newlyAddedWindowScopedQueriesCarryTheProfile}
 * fails when an unguarded query appears outside the list; {@code theDebtListHasNoStaleEntries} fails
 * when a listed query has since been guarded, forcing its line to be deleted rather than left behind.
 * Together the list can only shrink. Treat it like the coverage thresholds in
 * {@code frontend/vite.config.js} — a bar to close, never one to lower.
 */
class AgentProfileCoverageTest {

  /** Resource holding the shrinking list of window-scoped queries that predate the partition. */
  private static final String DEBT_LIST_RESOURCE = "/agent-profile-coverage-debt.txt";

  private static final String AGENT_PROFILE_PARAMETER = "agentProfile";
  private static final String COMMENT_PREFIX = "#";
  private static final String KEY_FORMAT = "%s#%s";

  private static final List<Class<?>> SIGNAL_REPOSITORIES =
      List.of(LogRecordRepository.class, SpanRepository.class, MetricPointRepository.class);

  @Test
  void newlyAddedWindowScopedQueriesCarryTheProfile() {
    Set<String> unguarded = unguardedWindowScopedQueries();
    Set<String> debtList = readDebtList();

    Set<String> escaped = new TreeSet<>(unguarded);
    escaped.removeAll(debtList);

    if (!escaped.isEmpty()) {
      fail("""
          %d window-scoped repository query/queries take no %s parameter and are not in the debt \
          list, so they would read across every agent profile:

          %s

          Agent profiles are a hard partition, not a filter -- a query without the predicate \
          returns a wrong number rather than a mixed one. Add the parameter and its \
          `AND agent_profile = :agentProfile` predicate. If this query genuinely must span \
          profiles, say why in %s and add it there.""".formatted(
          escaped.size(),
          AGENT_PROFILE_PARAMETER,
          escaped.stream().collect(Collectors.joining("\n")),
          DEBT_LIST_RESOURCE));
    }
  }

  @Test
  void theDebtListHasNoStaleEntries() {
    Set<String> unguarded = unguardedWindowScopedQueries();
    Set<String> debtList = readDebtList();

    Set<String> stale = new TreeSet<>(debtList);
    stale.removeAll(unguarded);

    assertThat(stale)
        .as("these queries now carry the %s parameter (or no longer exist), so their lines in %s "
            + "must be deleted -- the debt list is a ratchet and may only shrink",
            AGENT_PROFILE_PARAMETER, DEBT_LIST_RESOURCE)
        .isEmpty();
  }

  @Test
  void theGuardActuallySeesTheQueriesItClaimsToCover() {
    // A reflection guard that silently matches nothing passes forever and protects nothing. This
    // pins the detector itself: the three repositories really do expose window-scoped queries, and
    // every one of them is accounted for either as guarded or as declared debt.
    Set<String> windowScoped = windowScopedQueries();
    Set<String> unguarded = unguardedWindowScopedQueries();

    assertThat(windowScoped)
        .as("the window-scoped detector matched nothing -- it is broken, not the code")
        .isNotEmpty();
    assertThat(windowScoped).containsAll(unguarded);
  }

  /** Every repository method taking an {@link Instant} bound, i.e. scoped to a time window. */
  private static Set<String> windowScopedQueries() {
    return SIGNAL_REPOSITORIES.stream()
        .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods())
            .filter(AgentProfileCoverageTest::isWindowScoped)
            .map(method -> KEY_FORMAT.formatted(repository.getSimpleName(), method.getName())))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> unguardedWindowScopedQueries() {
    return SIGNAL_REPOSITORIES.stream()
        .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods())
            .filter(AgentProfileCoverageTest::isWindowScoped)
            .filter(method -> !carriesAgentProfile(method))
            .map(method -> KEY_FORMAT.formatted(repository.getSimpleName(), method.getName())))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static boolean isWindowScoped(Method method) {
    return Arrays.stream(method.getParameterTypes()).anyMatch(Instant.class::equals);
  }

  /**
   * Reads {@code @Param} rather than the parameter's own name. Parameter names survive reflection
   * only when the compiler is given {@code -parameters}, which this build does not guarantee, while
   * {@code @Param} is what Spring Data binds native-query placeholders by and is therefore both
   * always present on these methods and the thing that actually has to match the SQL.
   */
  private static boolean carriesAgentProfile(Method method) {
    for (Parameter parameter : method.getParameters()) {
      Param param = parameter.getAnnotation(Param.class);
      if (param != null && AGENT_PROFILE_PARAMETER.equals(param.value())) {
        return true;
      }
      if (param == null && AGENT_PROFILE_PARAMETER.equals(parameter.getName())) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> readDebtList() {
    try (InputStream stream = AgentProfileCoverageTest.class.getResourceAsStream(DEBT_LIST_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException(DEBT_LIST_RESOURCE + " is missing from the test classpath");
      }
      String contents = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      return contents.lines()
          .map(String::trim)
          .filter(line -> !line.isEmpty() && !line.startsWith(COMMENT_PREFIX))
          .collect(Collectors.toCollection(LinkedHashSet::new));
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + DEBT_LIST_RESOURCE, e);
    }
  }
}
