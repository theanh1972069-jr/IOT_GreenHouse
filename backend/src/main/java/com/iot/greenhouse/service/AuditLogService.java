package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.AuditLog;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Writes the audit trail. The table and its read endpoint existed but nothing ever
 * inserted a row, so the audit log page was permanently empty.
 *
 * <p>Runs in its own transaction so a logging failure can never roll back the
 * business change it is describing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(User actor, HttpServletRequest request, String action,
                       String resourceType, String resourceId, Map<String, Object> details) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .user(actor)
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .ipAddress(clientIp(request))
                    .details(details == null || details.isEmpty() ? null : details)
                    .build());
        } catch (Exception e) {
            log.error("Failed to write audit log for action {} on {} {}", action, resourceType, resourceId, e);
        }
    }

    /**
     * Honours the usual proxy headers so the recorded address is the real client
     * rather than the reverse proxy. Leftmost entry wins, matching RFC 7239.
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty() && first.length() <= 45) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank() && realIp.length() <= 45) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote != null && remote.length() <= 45 ? remote : null;
    }
}
