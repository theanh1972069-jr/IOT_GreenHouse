package com.iot.greenhouse.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Entity
@Table(name = "telemetry")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Telemetry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Column(nullable = false)
    private ZonedDateTime timestamp;

    @Column(precision = 6, scale = 2)
    private BigDecimal temperature;

    @Column(precision = 6, scale = 2)
    private BigDecimal humidity;

    @Column(name = "soil_moisture", precision = 6, scale = 2)
    private BigDecimal soilMoisture;

    @Column(precision = 10, scale = 2)
    private BigDecimal light;
}
