package com.iot.greenhouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Lịch tự động bật/tắt đèn quang hợp theo giờ trong ngày.
 * Mỗi lịch gắn với một thiết bị (device) và chạy hàng ngày vào giờ cài đặt.
 */
@Entity
@Table(name = "light_schedules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LightSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    /** Nhãn mô tả lịch, ví dụ "Sáng sớm" */
    @Column(nullable = false, length = 100)
    @Builder.Default
    private String label = "Lịch chiếu sáng";

    /** Giờ bật đèn (HH:mm) */
    @Column(name = "turn_on_time", nullable = false)
    private LocalTime turnOnTime;

    /** Giờ tắt đèn (HH:mm) */
    @Column(name = "turn_off_time", nullable = false)
    private LocalTime turnOffTime;

    /** Lịch có đang được kích hoạt hay không */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;
}

