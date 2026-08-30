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
