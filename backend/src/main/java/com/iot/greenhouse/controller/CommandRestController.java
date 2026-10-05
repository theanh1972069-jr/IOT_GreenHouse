package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.Command;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.repository.CommandRepository;
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
@RequestMapping("/api/commands")
@RequiredArgsConstructor
public class CommandRestController {

    private final CommandRepository commandRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<CommandView> getCommands(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String deviceId,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        PageResponse<CommandView> result = PageResponse.from(
                commandRepository.findAll(buildSpecification(status, deviceId),
                        PageParams.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))),
                CommandRestController::toView);
        return result;
    }

    private static Specification<Command> buildSpecification(String status, String deviceId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
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
