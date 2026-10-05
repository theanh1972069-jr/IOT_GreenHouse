package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.FirmwareVersion;
import com.iot.greenhouse.repository.FirmwareVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;

/**
 * Read-only view of the published firmware catalogue. Upload and OTA rollout are not
 * implemented; this only exposes rows that were inserted out of band.
 */
@RestController
@RequestMapping("/api/firmware-versions")
@RequiredArgsConstructor
public class FirmwareVersionRestController {

    private final FirmwareVersionRepository firmwareVersionRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<FirmwareVersionView> getFirmwareVersions(@RequestParam(required = false) Integer page,
                                                                  @RequestParam(required = false) Integer size) {
        Page<FirmwareVersion> result = firmwareVersionRepository.findAll(
                PageParams.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.from(result, FirmwareVersionRestController::toView);
    }

    @GetMapping("/{version}")
    @Transactional(readOnly = true)
    public ResponseEntity<FirmwareVersionView> getFirmwareVersion(@PathVariable String version) {
        return firmwareVersionRepository.findByVersion(version)
                .map(FirmwareVersionRestController::toView)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private static FirmwareVersionView toView(FirmwareVersion firmware) {
        return new FirmwareVersionView(firmware.getId(), firmware.getVersion(), firmware.getFileUrl(),
                firmware.getChecksum(), firmware.getReleaseNotes(), firmware.getCreatedAt());
    }

    public record FirmwareVersionView(Long id, String version, String fileUrl, String checksum,
                                      String releaseNotes, ZonedDateTime createdAt) {}
}
