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
}
