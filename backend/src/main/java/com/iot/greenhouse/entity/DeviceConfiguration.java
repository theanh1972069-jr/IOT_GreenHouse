package com.iot.greenhouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Entity
@Table(name = "device_configurations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceConfiguration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "device_id", nullable = false, unique = true)
    private Device device;

    @Column(name = "temperature_high", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal temperatureHigh = new BigDecimal("35.00");

    @Column(name = "soil_moisture_low", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal soilMoistureLow = new BigDecimal("30.00");

    @Column(name = "heartbeat_interval", nullable = false)
    @Builder.Default
    private Integer heartbeatInterval = 10;

    @Column(name = "auto_watering", nullable = false)
    @Builder.Default
    private Boolean autoWatering = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;
}
