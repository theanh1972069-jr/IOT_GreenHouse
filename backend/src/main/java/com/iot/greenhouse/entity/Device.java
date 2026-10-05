package com.iot.greenhouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.Map;

@Entity
@Table(name = "devices")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true, length = 100)
    private String deviceId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 50)
    private String zone;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "REGISTERED";

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    @Column(name = "mac_address", length = 17)
    private String macAddress;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * Mapped as JSON rather than a plain String: without {@code @JdbcTypeCode} Hibernate
     * binds it as VARCHAR and PostgreSQL rejects the insert with
     * "column state is of type jsonb but expression is of type character varying".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> state = Map.of();

    @Column(name = "last_seen")
    private ZonedDateTime lastSeen;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;
}
