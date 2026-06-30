package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.FacetValue;
import com.guavasoft.agentcompass.model.HistogramBucket;
import com.guavasoft.agentcompass.model.LogCursor;
import com.guavasoft.agentcompass.model.LogCursorPage;
import com.guavasoft.agentcompass.model.LogFacets;
import com.guavasoft.agentcompass.model.LogHistogram;
import com.guavasoft.agentcompass.model.LogPage;
import com.guavasoft.agentcompass.model.LogQueryCriteria;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.service.LogService;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LogsController.class)
class LogsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LogService logService;

    // -------------------------------------------------------------------------
    // Existing tests — unchanged
    // -------------------------------------------------------------------------

    @Test
    void logsReturnsAllLogRecordsWhenNoFilterApplied() throws Exception {
        when(logService.cursorPage(any(LogQueryCriteria.class), isNull(), isNull(), anyInt()))
                .thenReturn(new LogCursorPage(
                        List.of(LogRecord.builder()
                                .id(7L)
                                .timestamp(Instant.parse("2026-05-21T12:00:00Z"))
                                .severityNumber(17)
                                .severityText("ERROR")
                                .body("Edit failed: old_string not unique")
                                .scopeName("claude-code.events")
                                .build()),
                        null, false, 1L));

        mockMvc.perform(get("/api/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].severityText").value("ERROR"))
                .andExpect(jsonPath("$.items[0].body").value("Edit failed: old_string not unique"))
                .andExpect(jsonPath("$.totalCount").value(1));

        verify(logService).cursorPage(any(LogQueryCriteria.class), isNull(), isNull(), anyInt());
    }

    @Test
    void logsNarrowsBySuppliedFiltersAndReturnsFilteredCount() throws Exception {
        when(logService.cursorPage(any(LogQueryCriteria.class), isNull(), isNull(), anyInt()))
                .thenReturn(new LogCursorPage(
                        List.of(LogRecord.builder()
                                .id(11L)
                                .timestamp(Instant.parse("2026-05-21T12:00:00Z"))
                                .body("tool_result")
                                .scopeName("claude-code.events")
                                .build()),
                        null, false, 1L));

        mockMvc.perform(get("/api/logs")
                .param("filter", "event.name=tool_result", "tool_name=Read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].body").value("tool_result"));

        verify(logService).cursorPage(any(LogQueryCriteria.class), isNull(), isNull(), anyInt());
    }

    @Test
    void logsNarrowsByTimeWindow() throws Exception {
        when(logService.cursorPage(any(LogQueryCriteria.class), isNull(), isNull(), anyInt()))
                .thenReturn(new LogCursorPage(
                        List.of(LogRecord.builder()
                                .id(21L)
                                .timestamp(Instant.parse("2026-05-21T12:02:30Z"))
                                .body("tool_result")
                                .scopeName("claude-code.events")
                                .build()),
                        null, false, 1L));

        mockMvc.perform(get("/api/logs")
                .param("startTimestamp", "2026-05-21T12:00:00Z")
                .param("endTimestamp", "2026-05-21T12:05:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));

        verify(logService).cursorPage(any(LogQueryCriteria.class), isNull(), isNull(), anyInt());
    }

    @Test
    void logAttributesReturnsDistinctKeyValuePairsWhenNoFilterApplied() throws Exception {
        when(logService.availableAttributePairs(List.of(), null, null)).thenReturn(List.of(
                "event.name=tool_result",
                "tool_name=Bash",
                "tool_name=Read"));

        mockMvc.perform(get("/api/logs/attributes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0]").value("event.name=tool_result"));

        verify(logService).availableAttributePairs(List.of(), null, null);
    }

    @Test
    void logAttributesNarrowsBySuppliedFilters() throws Exception {
        when(logService.availableAttributePairs(
                List.of("event.name=tool_result"), null, null))
                .thenReturn(List.of("event.name=tool_result", "tool_name=Read"));

        mockMvc.perform(get("/api/logs/attributes")
                .param("filter", "event.name=tool_result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        verify(logService).availableAttributePairs(
                List.of("event.name=tool_result"), null, null);
    }

    @Test
    void logAttributesNarrowsByTimeWindow() throws Exception {
        Instant windowStart = Instant.parse("2026-05-21T12:00:00Z");
        Instant windowEnd = Instant.parse("2026-05-21T12:05:00Z");
        when(logService.availableAttributePairs(List.of(), windowStart, windowEnd))
                .thenReturn(List.of("event.name=tool_result", "tool_name=Read"));

        mockMvc.perform(get("/api/logs/attributes")
                .param("startTimestamp", windowStart.toString())
                .param("endTimestamp", windowEnd.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        verify(logService).availableAttributePairs(List.of(), windowStart, windowEnd);
    }

    // -------------------------------------------------------------------------
    // New endpoint tests
    // -------------------------------------------------------------------------

    @Test
    void histogramDispatchesToServiceWithDefaultBuckets() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        HistogramBucket bucket = new HistogramBucket(start, end, 2L, 5L, 41L, 18L);
        when(logService.histogram(any(LogQueryCriteria.class), anyInt()))
                .thenReturn(new LogHistogram(1800000L, List.of(bucket)));

        mockMvc.perform(get("/api/logs/histogram")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketMs").value(1800000))
                .andExpect(jsonPath("$.buckets", hasSize(1)))
                .andExpect(jsonPath("$.buckets[0].ERROR").value(2))
                .andExpect(jsonPath("$.buckets[0].WARN").value(5))
                .andExpect(jsonPath("$.buckets[0].INFO").value(41))
                .andExpect(jsonPath("$.buckets[0].DEBUG").value(18));

        verify(logService).histogram(any(LogQueryCriteria.class), anyInt());
    }

    @Test
    void histogramPassesBucketsParamToService() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        when(logService.histogram(any(LogQueryCriteria.class), anyInt()))
                .thenReturn(new LogHistogram(3600000L, List.of()));

        mockMvc.perform(get("/api/logs/histogram")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString())
                .param("buckets", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketMs").value(3600000));

        verify(logService).histogram(any(LogQueryCriteria.class), anyInt());
    }

    @Test
    void facetsDispatchesToServiceAndReturnsThreeDimensions() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        LogFacets stubFacets = new LogFacets(
                List.of(
                        new FacetValue("ERROR", 61L),
                        new FacetValue("WARN", 48L),
                        new FacetValue("INFO", 612L),
                        new FacetValue("DEBUG", 120L)),
                List.of(new FacetValue("tool_result", 360L)),
                List.of(new FacetValue("Bash", 92L)));

        when(logService.facets(any(LogQueryCriteria.class))).thenReturn(stubFacets);

        mockMvc.perform(get("/api/logs/facets")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity", hasSize(4)))
                .andExpect(jsonPath("$.severity[0].value").value("ERROR"))
                .andExpect(jsonPath("$.severity[0].count").value(61))
                .andExpect(jsonPath("$.scope").doesNotExist())
                .andExpect(jsonPath("$.event[0].value").value("tool_result"))
                .andExpect(jsonPath("$.tool[0].value").value("Bash"));

        verify(logService).facets(any(LogQueryCriteria.class));
    }

    @Test
    void facetsPassesSeverityFilterToCriteria() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        when(logService.facets(any(LogQueryCriteria.class)))
                .thenReturn(new LogFacets(
                        List.of(
                                new FacetValue("ERROR", 0L),
                                new FacetValue("WARN", 0L),
                                new FacetValue("INFO", 0L),
                                new FacetValue("DEBUG", 0L)),
                        List.of(), List.of()));

        mockMvc.perform(get("/api/logs/facets")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString())
                .param("severity", "ERROR", "WARN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity", hasSize(4)));

        verify(logService).facets(any(LogQueryCriteria.class));
    }

    @Test
    void logsWithPageParamDispatchesToOffsetMode() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        when(logService.offsetPage(any(LogQueryCriteria.class), anyInt(), anyInt()))
                .thenReturn(new LogPage(
                        List.of(LogRecord.builder().id(5L).body("a log").build()),
                        99L));

        mockMvc.perform(get("/api/logs")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString())
                .param("page", "0")
                .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalCount").value(99))
                .andExpect(jsonPath("$.items[0].body").value("a log"));

        verify(logService).offsetPage(any(LogQueryCriteria.class), anyInt(), anyInt());
    }

    @Test
    void logsWithBeforeParamDispatchesToCursorBeforeMode() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        when(logService.cursorPage(
                any(LogQueryCriteria.class), eq("2026-06-01T12:00:00Z,42"), isNull(), anyInt()))
                .thenReturn(new LogCursorPage(List.of(), null, false, 0L));

        mockMvc.perform(get("/api/logs")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString())
                .param("before", "2026-06-01T12:00:00Z,42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(logService).cursorPage(
                any(LogQueryCriteria.class), eq("2026-06-01T12:00:00Z,42"), isNull(), anyInt());
    }

    @Test
    void logsWithAfterParamDispatchesToCursorAfterMode() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        LogRecord freshRow = LogRecord.builder().id(100L).body("new log").build();
        when(logService.cursorPage(
                any(LogQueryCriteria.class), isNull(), eq("2026-06-01T18:00:00Z,99"), anyInt()))
                .thenReturn(new LogCursorPage(List.of(freshRow), null, false, 1L));

        mockMvc.perform(get("/api/logs")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString())
                .param("after", "2026-06-01T18:00:00Z,99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].body").value("new log"));

        verify(logService).cursorPage(
                any(LogQueryCriteria.class), isNull(), eq("2026-06-01T18:00:00Z,99"), anyInt());
    }

    @Test
    void logsWithNoCursorParamDispatchesToCursorFirstMode() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        LogRecord row = LogRecord.builder().id(7L).body("first page").build();
        LogCursor cursor = new LogCursor(Instant.parse("2026-06-01T10:00:00Z"), 7L);
        when(logService.cursorPage(any(LogQueryCriteria.class), isNull(), isNull(), anyInt()))
                .thenReturn(new LogCursorPage(List.of(row), cursor, false, 1L));

        mockMvc.perform(get("/api/logs")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.nextCursor.id").value(7))
                .andExpect(jsonPath("$.totalCount").value(1));

        verify(logService).cursorPage(any(LogQueryCriteria.class), isNull(), isNull(), anyInt());
    }

    // -------------------------------------------------------------------------
    // LogFilterParams multi-value List binding verification
    // -------------------------------------------------------------------------

    @Test
    void histogramBindsMultipleFilterEventAndToolValuesToLogFilterParams() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        when(logService.histogram(any(LogQueryCriteria.class), anyInt()))
                .thenReturn(new LogHistogram(1800000L, List.of()));

        ArgumentCaptor<LogQueryCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(LogQueryCriteria.class);

        mockMvc.perform(get("/api/logs/histogram")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString())
                .param("filter", "event.name=tool_result", "tool_name=Read")
                .param("event", "tool_result", "tool_use")
                .param("tool", "Bash", "Read"))
                .andExpect(status().isOk());

        verify(logService).histogram(criteriaCaptor.capture(), anyInt());
        LogQueryCriteria capturedCriteria = criteriaCaptor.getValue();
        assertThat(Arrays.asList(capturedCriteria.filters()))
                .containsExactlyInAnyOrder("event.name=tool_result", "tool_name=Read");
        assertThat(Arrays.asList(capturedCriteria.events()))
                .containsExactlyInAnyOrder("tool_result", "tool_use");
        assertThat(Arrays.asList(capturedCriteria.tools()))
                .containsExactlyInAnyOrder("Bash", "Read");
        // severity must be empty — histogram always excludes it
        assertThat(capturedCriteria.severities()).isEmpty();
    }

    @Test
    void facetsBindsMultipleFilterEventToolAndSeverityValues() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        when(logService.facets(any(LogQueryCriteria.class)))
                .thenReturn(new LogFacets(
                        List.of(
                                new FacetValue("ERROR", 0L),
                                new FacetValue("WARN", 0L),
                                new FacetValue("INFO", 0L),
                                new FacetValue("DEBUG", 0L)),
                        List.of(), List.of()));

        ArgumentCaptor<LogQueryCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(LogQueryCriteria.class);

        mockMvc.perform(get("/api/logs/facets")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString())
                .param("filter", "event.name=tool_result", "tool_name=Read")
                .param("event", "tool_result", "tool_use")
                .param("tool", "Bash", "Read")
                .param("severity", "ERROR", "WARN"))
                .andExpect(status().isOk());

        verify(logService).facets(criteriaCaptor.capture());
        LogQueryCriteria capturedCriteria = criteriaCaptor.getValue();
        assertThat(Arrays.asList(capturedCriteria.filters()))
                .containsExactlyInAnyOrder("event.name=tool_result", "tool_name=Read");
        assertThat(Arrays.asList(capturedCriteria.events()))
                .containsExactlyInAnyOrder("tool_result", "tool_use");
        assertThat(Arrays.asList(capturedCriteria.tools()))
                .containsExactlyInAnyOrder("Bash", "Read");
        assertThat(Arrays.asList(capturedCriteria.severities()))
                .containsExactlyInAnyOrder("ERROR", "WARN");
    }

    @Test
    void logsBindsMultipleFilterEventToolAndSeverityValues() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        when(logService.cursorPage(any(LogQueryCriteria.class), isNull(), isNull(), anyInt()))
                .thenReturn(new LogCursorPage(List.of(), null, false, 0L));

        ArgumentCaptor<LogQueryCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(LogQueryCriteria.class);

        mockMvc.perform(get("/api/logs")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString())
                .param("filter", "event.name=tool_result", "tool_name=Read")
                .param("event", "tool_result", "tool_use")
                .param("tool", "Bash", "Read")
                .param("severity", "INFO", "DEBUG"))
                .andExpect(status().isOk());

        verify(logService).cursorPage(criteriaCaptor.capture(), isNull(), isNull(), anyInt());
        LogQueryCriteria capturedCriteria = criteriaCaptor.getValue();
        assertThat(Arrays.asList(capturedCriteria.filters()))
                .containsExactlyInAnyOrder("event.name=tool_result", "tool_name=Read");
        assertThat(Arrays.asList(capturedCriteria.events()))
                .containsExactlyInAnyOrder("tool_result", "tool_use");
        assertThat(Arrays.asList(capturedCriteria.tools()))
                .containsExactlyInAnyOrder("Bash", "Read");
        assertThat(Arrays.asList(capturedCriteria.severities()))
                .containsExactlyInAnyOrder("INFO", "DEBUG");
    }

    @Test
    void histogramTreatsAbsentFilterParamsAsEmpty() throws Exception {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-02T00:00:00Z");
        when(logService.histogram(any(LogQueryCriteria.class), anyInt()))
                .thenReturn(new LogHistogram(1800000L, List.of()));

        ArgumentCaptor<LogQueryCriteria> criteriaCaptor =
                ArgumentCaptor.forClass(LogQueryCriteria.class);

        mockMvc.perform(get("/api/logs/histogram")
                .param("startTimestamp", start.toString())
                .param("endTimestamp", end.toString()))
                .andExpect(status().isOk());

        verify(logService).histogram(criteriaCaptor.capture(), anyInt());
        LogQueryCriteria capturedCriteria = criteriaCaptor.getValue();
        // absent params must arrive as empty arrays, not NPE
        assertThat(capturedCriteria.filters()).isEmpty();
        assertThat(capturedCriteria.events()).isEmpty();
        assertThat(capturedCriteria.tools()).isEmpty();
        assertThat(capturedCriteria.severities()).isEmpty();
    }
}
