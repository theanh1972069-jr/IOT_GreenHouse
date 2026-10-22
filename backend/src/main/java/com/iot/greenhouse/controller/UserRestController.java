package com.iot.greenhouse.controller;

import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.config.RequireRole;
import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.RoleRepository;
import com.iot.greenhouse.repository.UserRepository;
import com.iot.greenhouse.repository.UserSessionRepository;
import com.iot.greenhouse.service.AuditLogService;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserRestController {

    private static final Map<String, String> ROLE_DESCRIPTIONS = Map.of(
            "ADMIN", "System administrator",
            "OPERATOR", "Greenhouse monitoring and operations",
            "VIEWER", "Read-only greenhouse access",
            "USER", "Legacy standard user");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @GetMapping
    @RequireRole("ADMIN")
    @Transactional(readOnly = true)
    public PageResponse<UserView> getUsers(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String search,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        ZonedDateTime now = ZonedDateTime.now();
        return PageResponse.from(
                userRepository.findAll(buildSpecification(status, search),
                        PageParams.of(page, size, Sort.by(Sort.Direction.ASC, "fullName"))),
                user -> toUserView(user, isOnline(user.getId(), now)));
    }

    private static Specification<User> buildSpecification(String status, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
                predicates.add(cb.equal(cb.upper(root.get("status")), status.trim().toUpperCase(Locale.ROOT)));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), like),
                        cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(root.get("email")), like)));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    @PostMapping
    @RequireRole("ADMIN")
    @Transactional
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest request,
                                        @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User creator,
                                        HttpServletRequest httpRequest) {
        boolean isAdmin = creator.getRoles() != null && creator.getRoles().stream()
                .anyMatch(role -> "ADMIN".equalsIgnoreCase(role.getName()));
        if (!isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("Chỉ quản trị viên mới được tạo tài khoản."));
        }

        String username = request.username() == null ? "" : request.username().trim();
        String email = request.email() == null ? "" : request.email().trim();
        String fullName = request.fullName() == null ? "" : request.fullName().trim();
        String password = request.password() == null ? "" : request.password();
        String roleName = request.role() == null ? "USER" : request.role().trim().toUpperCase(Locale.ROOT);

        if (!username.matches("[A-Za-z0-9._-]{3,50}")) {
            return ResponseEntity.badRequest().body(new ApiError("Tên đăng nhập cần từ 3 đến 50 ký tự, chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang."));
        }
        if (email.length() > 150 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            return ResponseEntity.badRequest().body(new ApiError("Địa chỉ email không hợp lệ."));
        }
        if (password.length() < 8 || password.length() > 72) {
            return ResponseEntity.badRequest().body(new ApiError("Mật khẩu cần từ 8 đến 72 ký tự."));
        }
        if (fullName.length() > 150) {
            return ResponseEntity.badRequest().body(new ApiError("Họ tên không được vượt quá 150 ký tự."));
        }
        if (!ROLE_DESCRIPTIONS.containsKey(roleName)) {
            return ResponseEntity.badRequest().body(new ApiError("Vai trò tài khoản không hợp lệ."));
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("Tên đăng nhập đã được sử dụng."));
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("Email đã được sử dụng."));
        }

        Role userRole = roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.save(Role.builder()
                .name(roleName)
                .description(ROLE_DESCRIPTIONS.get(roleName))
                        .build()));
        User user = userRepository.save(User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .fullName(fullName.isBlank() ? username : fullName)
                .status("ACTIVE")
                .roles(Set.of(userRole))
                .build());

        auditLogService.record(creator, httpRequest, "USER_CREATE", "User",
            String.valueOf(user.getId()), Map.of("username", username, "email", email, "role", roleName));

        return ResponseEntity.status(HttpStatus.CREATED).body(toUserView(user, false));
    }

    private static UserView toUserView(User user, boolean online) {
        return new UserView(user.getId(), user.getUsername(), user.getEmail(), user.getFullName(), user.getStatus(),
                user.getRoles() == null ? List.of()
                        : user.getRoles().stream().map(Role::getName).sorted().toList(),
                user.getCreatedAt(), online);
    }

    private boolean isOnline(Long userId, ZonedDateTime now) {
        return userId != null && sessionRepository.existsByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, now);
    }

    /**
     * Changes a member's role. Administrator accounts are immutable through this
     * endpoint so the system always keeps at least its existing admins.
     */
    @PatchMapping("/{id}/role")
    @RequireRole("ADMIN")
    @Transactional
    public ResponseEntity<?> updateRole(@PathVariable Long id,
                                        @RequestBody RoleRequest request,
                                        @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
                                        HttpServletRequest httpRequest) {
        try {
            return doUpdateRole(id, request, actor, httpRequest);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(UserRestController.class)
                    .error("Failed to change role for user {}", id, e);
            String detail = e.getMessage() == null ? e.toString() : e.getClass().getSimpleName() + ": " + e.getMessage();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiError("Không thể đổi vai trò (" + detail + ")."));
        }
    }

    private ResponseEntity<?> doUpdateRole(Long id, RoleRequest request, User actor, HttpServletRequest httpRequest) {
        String roleName = request == null || request.role() == null
                ? "" : request.role().trim().toUpperCase(Locale.ROOT);
        if (!ROLE_DESCRIPTIONS.containsKey(roleName)) {
            return ResponseEntity.badRequest().body(new ApiError("Vai trò tài khoản không hợp lệ."));
        }
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = userOpt.get();
        boolean isAdmin = user.getRoles() != null && user.getRoles().stream()
                .anyMatch(role -> "ADMIN".equalsIgnoreCase(role.getName()));
        if (isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiError("Không thể thay đổi vai trò của quản trị viên."));
        }
        Role role = roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .name(roleName)
                        .description(ROLE_DESCRIPTIONS.get(roleName))
                        .build()));
        user.setRoles(new HashSet<>(Set.of(role)));
        User saved = userRepository.save(user);
        auditLogService.record(actor, httpRequest, "USER_ROLE_CHANGE", "User",
                String.valueOf(saved.getId()), Map.of("username", saved.getUsername(), "role", roleName));
        return ResponseEntity.ok(toUserView(saved, isOnline(saved.getId(), ZonedDateTime.now())));
    }

    public record UserView(Long id, String username, String email, String fullName, String status,
                           List<String> roles, ZonedDateTime createdAt, boolean online) {}

    public record CreateUserRequest(String username, String email, String password, String fullName, String role) {}

    public record RoleRequest(String role) {}

    public record ApiError(String message) {}
}
