package com.iot.greenhouse.controller;

import com.iot.greenhouse.config.RequireRole;
import com.iot.greenhouse.entity.AuditLog;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.AuditLogRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/audit-logs")
@RequireRole("ADMIN")
@RequiredArgsConstructor
public class AuditLogRestController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<AuditLogView> getAuditLogs(@RequestParam(required = false) String action,
                                                   @RequestParam(required = false) String search,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return PageResponse.from(
                auditLogRepository.findAll(buildSpecification(action, search),
                        PageParams.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))),
                AuditLogRestController::toView);
    }

    private static Specification<AuditLog> buildSpecification(String action, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (action != null && !action.isBlank() && !action.equalsIgnoreCase("ALL")) {
                predicates.add(cb.equal(cb.upper(root.get("action")), action.trim().toUpperCase(Locale.ROOT)));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                Join<AuditLog, User> user = root.join("user", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.like(cb.lower(user.get("username")), like),
                        cb.like(cb.lower(root.get("action")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("resourceType"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("resourceId"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("ipAddress"), "")), like)));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static AuditLogView toView(AuditLog log) {
        return new AuditLogView(log.getId(),
                log.getUser() == null ? null : log.getUser().getUsername(),
                log.getAction(), log.getResourceType(), log.getResourceId(), log.getIpAddress(),
                log.getDetails(), log.getCreatedAt());
    }

    public record AuditLogView(Long id, String username, String action, String resourceType,
                               String resourceId, String ipAddress, Map<String, Object> details,
                               ZonedDateTime createdAt) {}
}
