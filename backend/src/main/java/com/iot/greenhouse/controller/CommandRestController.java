package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.Command;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.CommandRepository;
import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.config.RequireRole;
import com.iot.greenhouse.service.ZoneAccessService;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/commands")
@RequiredArgsConstructor
public class CommandRestController {

    private final CommandRepository commandRepository;
    private final ZoneAccessService zoneAccessService;

    @GetMapping
    @RequireRole({"ADMIN", "OPERATOR", "VIEWER"})
    @Transactional(readOnly = true)
    public PageResponse<CommandView> getCommands(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String deviceId,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor) {
        boolean unrestricted = zoneAccessService.isAdministrator(actor);
        PageResponse<CommandView> result = PageResponse.from(
                commandRepository.findAll(buildSpecification(status, deviceId, unrestricted,
                                zoneAccessService.accessibleZones(actor)),
                        PageParams.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))),
                CommandRestController::toView);
        return result;
    }

    private static Specification<Command> buildSpecification(String status, String deviceId,
                                                              boolean unrestricted, List<String> zones) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!unrestricted) {
                if (zones.isEmpty()) {
                    predicates.add(cb.disjunction());
                } else {
                    Join<Command, Device> device = root.join("device", JoinType.LEFT);
                    predicates.add(device.get("zone").in(zones));
                }
            }
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
                predicates.add(cb.equal(cb.upper(root.get("status")), status.trim().toUpperCase(Locale.ROOT)));
            }
            if (deviceId != null && !deviceId.isBlank() && !deviceId.equalsIgnoreCase("ALL")) {
                Join<Command, Device> device = root.join("device", JoinType.LEFT);
                predicates.add(cb.equal(device.get("deviceId"), deviceId.trim()));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static CommandView toView(Command command) {
        return new CommandView(command.getId(), command.getCommandId(),
                command.getDevice().getDeviceId(), command.getDevice().getName(),
                command.getCommandType(), command.getPayload(), command.getStatus(),
                command.getCreatedAt(), command.getSentAt(), command.getExecutedAt());
    }

    public record CommandView(Long id, String commandId, String deviceId, String deviceName,
                              String commandType, Map<String, Object> payload, String status,
                              ZonedDateTime createdAt, ZonedDateTime sentAt, ZonedDateTime executedAt) {}
}
