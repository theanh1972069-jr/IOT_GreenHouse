package com.iot.greenhouse.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.ZonedDateTime;

@Entity
@Table(name = "firmware_versions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FirmwareVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String version;

    @Column(name = "file_url", nullable = false, columnDefinition = "TEXT")
    private String fileUrl;

    @Column(nullable = false, length = 128)
    private String checksum;

    @Column(name = "release_notes", columnDefinition = "TEXT")
    private String releaseNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;
}
