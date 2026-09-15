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

/**
 * Normalizes the frontend's "Unattributed" sentinel to {@code null} at every point a raw
 * {@code repositoryUrl} request param is bound into a query-criteria object.
 *
 * <p>Every native repository query filters with {@code (:repositoryUrl IS NULL OR repository_url
 * = :repositoryUrl)}. The frontend's RepositorySelector sends the literal string {@link
 * #UNATTRIBUTED_SENTINEL} when the user picks "Unattributed" (see {@code
 * frontend/src/api/types.ts}'s {@code UNATTRIBUTED_REPOSITORY}), which can never equal a real
 * {@code repository_url} value — binding it verbatim silently matches zero rows, not even the
 * unattributed ones. Mapping it to {@code null} here restores the existing predicate's own
 * documented meaning ("omitted or null shows every repository, including unaffiliated
 * telemetry") instead of matching nothing. Rather than rewrite every one of those predicates,
 * every binding point (the {@link TimeWindowParams} / {@link RequiredTimeWindowParams} compact
 * constructors, {@link TraceFilterParams#setRepositoryUrl}, and the {@link TraceQueryCriteria} /
 * {@link LogQueryCriteria} compact constructors) routes the raw value through this one
 * normalizer, so downstream SQL keeps its existing "null means all repositories" contract
 * unchanged.
 */
final class RepositoryUrlFilter {

    /** Sentinel the frontend sends for "Unattributed" — mirrors {@code UNATTRIBUTED_REPOSITORY}. */
    static final String UNATTRIBUTED_SENTINEL = "__unattributed__";

    private RepositoryUrlFilter() {
    }

    /** Maps the "Unattributed" sentinel to {@code null}; passes every other value through unchanged. */
    static String normalize(String repositoryUrl) {
        return UNATTRIBUTED_SENTINEL.equals(repositoryUrl) ? null : repositoryUrl;
    }
}
