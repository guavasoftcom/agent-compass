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
import java.util.List;
import java.util.Map;

@Entity
@Getter
@Setter
@Table(name = "spans", indexes = {
        @Index(name = "idx_spans_trace", columnList = "trace_id"),
        @Index(name = "idx_spans_start_ts", columnList = "start_timestamp"),
        @Index(name = "idx_spans_name_start", columnList = "name,start_timestamp")
})
public class SpanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", length = 32, nullable = false)
    private String traceId;

    @Column(name = "span_id", length = 16, nullable = false)
    private String spanId;

    @Column(name = "parent_span_id", length = 16)
    private String parentSpanId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "kind")
    private String kind;

    @Column(name = "start_timestamp", nullable = false)
    private Instant startTimestamp;

    @Column(name = "end_timestamp", nullable = false)
    private Instant endTimestamp;

    @Column(name = "duration_nanos")
    private Long durationNanos;

    @Column(name = "status_code")
    private String statusCode;

    @Column(name = "status_message")
    private String statusMessage;

    @Column(name = "scope_name")
    private String scopeName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb")
    private Map<String, Object> attributes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "events", columnDefinition = "jsonb")
    private List<Map<String, Object>> events;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource_attributes", columnDefinition = "jsonb")
    private Map<String, Object> resourceAttributes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_attributes", columnDefinition = "jsonb")
    private Map<String, Object> scopeAttributes;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
