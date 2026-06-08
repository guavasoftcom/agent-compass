package com.guavasoft.agentcompass.model;

import java.util.List;

/**
 * One backend-sorted, backend-paginated page of {@link SessionSummary} rows
 * together with the
 * total number of sessions in the window. {@code totalCount} drives the
 * DataGrid's server-side
 * pagination footer and is carried to the client via the {@code X-Total-Count}
 * response header,
 * so it reflects the whole window rather than the size of the returned page.
 */
public record SessionSummaryPage(List<SessionSummary> items, long totalCount) {
}
