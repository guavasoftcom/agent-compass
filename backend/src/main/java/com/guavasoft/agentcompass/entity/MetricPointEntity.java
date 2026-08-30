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
package com.guavasoft.agentcompass.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Getter
@Setter
@Table(name = "metric_points", indexes = {
        @Index(name = "idx_metric_points_name_ts", columnList = "metric_name,timestamp"),
        @Index(name = "idx_metric_points_ts", columnList = "timestamp")
})
public class MetricPointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "unit")
    private String unit;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "scope_name")
    private String scopeName;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "start_timestamp")
    private Instant startTimestamp;

    @Column(name = "value_double")
    private Double valueDouble;

    @Column(name = "value_long")
    private Long valueLong;

    @Column(name = "value_kind")
    private String valueKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb")
    private Map<String, Object> attributes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource_attributes", columnDefinition = "jsonb")
    private Map<String, Object> resourceAttributes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_attributes", columnDefinition = "jsonb")
    private Map<String, Object> scopeAttributes;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    // Stored generated column (V11): md5(metric_name || '|' || attributes::text),
    // the full stream identity. Computed by Postgres on insert — never written by
    // Hibernate.
    @Column(name = "stream_id", insertable = false, updatable = false)
    private String streamId;

    // Reset-aware per-row counter increment (V11), populated by
    // MetricPointRepository#recomputeValueDeltas after insert. Never written
    // through the entity — Hibernate must not include it in INSERT/UPDATE
    // statements, since ingest sets it via a dedicated native UPDATE once the
    // row's true predecessor (by stream_id, timestamp, id) is visible.
    @Column(name = "value_delta", insertable = false, updatable = false)
    private Double valueDelta;
}
