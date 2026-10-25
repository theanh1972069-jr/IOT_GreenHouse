package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.repository.UserRepository;
import com.iot.greenhouse.service.AuditLogService;
import com.iot.greenhouse.service.AuthTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final AuditLogService auditLogService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String identifier = request.identifier() == null ? "" : request.identifier().trim();
        String password = request.password() == null ? "" : request.password();

        if (identifier.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(new ApiError("Tên đăng nhập và mật khẩu là bắt buộc."));
        }

        Optional<User> userOpt = userRepository.findByUsernameOrEmail(identifier);
        if (userOpt.isEmpty()) {
            auditLogService.record(null, httpRequest, "LOGIN_FAILED", "User", identifier,
                    Map.of("reason", "unknown_user"));
            return ResponseEntity.status(401).body(new ApiError("Tên đăng nhập hoặc mật khẩu không đúng."));
        }

        User user = userOpt.get();
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            auditLogService.record(user, httpRequest, "LOGIN_FAILED", "User",
                    String.valueOf(user.getId()), Map.of("reason", "inactive"));
            return ResponseEntity.status(401).body(new ApiError("Tài khoản hiện không hoạt động."));
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            auditLogService.record(user, httpRequest, "LOGIN_FAILED", "User",
                    String.valueOf(user.getId()), Map.of("reason", "bad_password"));
            return ResponseEntity.status(401).body(new ApiError("Tên đăng nhập hoặc mật khẩu không đúng."));
        }

        List<String> roles = user.getRoles() == null
                ? List.of()
                : user.getRoles().stream().map(Role::getName).sorted().toList();

        LoginResponse response = new LoginResponse(
                authTokenService.issue(user, AuditLogService.clientIp(httpRequest)),
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getStatus(),
                roles
        );

        auditLogService.record(user, httpRequest, "LOGIN", "User", String.valueOf(user.getId()), null);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public UserProfile me(@RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User user) {
        return toUserProfile(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                       @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User user) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            authTokenService.revoke(token);
            auditLogService.record(user, request, "LOGOUT", "User", String.valueOf(user.getId()), null);
        }
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/profile")
    @Transactional
    public ResponseEntity<?> updateProfile(
            @RequestBody ProfileUpdateRequest request,
            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User user,
            HttpServletRequest httpRequest) {
        String fullName = request == null || request.fullName() == null ? "" : request.fullName().trim();
        String email = request == null || request.email() == null ? "" : request.email().trim();
        if (fullName.isBlank() || fullName.length() > 150
                || email.length() > 150 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            return ResponseEntity.badRequest().body(new ApiError("Họ tên hoặc email không hợp lệ."));
        }
        if (!email.equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmailIgnoreCase(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("Email đã được sử dụng."));
        }
        user.setFullName(fullName);
        user.setEmail(email);
        User saved = userRepository.save(user);
        auditLogService.record(user, httpRequest, "PROFILE_UPDATE", "User", String.valueOf(user.getId()),
                Map.of("fullName", fullName, "email", email));
        return ResponseEntity.ok(toUserProfile(saved));
    }

    @PostMapping("/password")
    @Transactional
    public ResponseEntity<?> changePassword(
            @RequestBody PasswordChangeRequest request,
            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User user,
            HttpServletRequest httpRequest) {
        if (request == null || request.currentPassword() == null || request.newPassword() == null
                || request.newPassword().length() < 12 || request.newPassword().length() > 72
                || request.newPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.badRequest().body(new ApiError("Mật khẩu mới cần từ 12 ký tự và tối đa 72 byte UTF-8."));
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            auditLogService.record(user, httpRequest, "PASSWORD_CHANGE_FAILED", "User",
                    String.valueOf(user.getId()), Map.of("reason", "current_password_mismatch"));
            return ResponseEntity.badRequest().body(new ApiError("Mật khẩu hiện tại không đúng."));
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        authTokenService.revokeAllForUser(user.getId());
        auditLogService.record(user, httpRequest, "PASSWORD_CHANGE", "User",
                String.valueOf(user.getId()), null);
        return ResponseEntity.noContent().build();
    }

    private UserProfile toUserProfile(User user) {
        List<String> roles = user.getRoles() == null
                ? List.of()
                : user.getRoles().stream().map(Role::getName).sorted().toList();
        return new UserProfile(user.getId(), user.getUsername(), user.getEmail(), user.getFullName(), user.getStatus(), roles);
    }

    public record LoginRequest(String identifier, String password) {}
    public record ProfileUpdateRequest(String fullName, String email) {}
    public record PasswordChangeRequest(String currentPassword, String newPassword) {}
    public record LoginResponse(String token, Long userId, String username, String email, String fullName, String status, List<String> roles) {}
    public record UserProfile(Long userId, String username, String email, String fullName, String status, List<String> roles) {}
    public record ApiError(String message) {}
}
