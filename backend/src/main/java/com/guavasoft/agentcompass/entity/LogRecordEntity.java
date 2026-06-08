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
@Table(name = "log_records", indexes = {
        @Index(name = "idx_log_records_ts", columnList = "timestamp"),
        @Index(name = "idx_log_records_severity", columnList = "severity_number,timestamp"),
        @Index(name = "idx_log_records_trace", columnList = "trace_id")
})
public class LogRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "observed_timestamp")
    private Instant observedTimestamp;

    @Column(name = "severity_number")
    private Integer severityNumber;

    @Column(name = "severity_text")
    private String severityText;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "scope_name")
    private String scopeName;

    @Column(name = "trace_id", length = 32)
    private String traceId;

    @Column(name = "span_id", length = 16)
    private String spanId;

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
