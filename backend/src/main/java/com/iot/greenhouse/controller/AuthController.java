package com.iot.greenhouse.controller;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.repository.UserRepository;
import com.iot.greenhouse.service.AuditLogService;
import com.iot.greenhouse.service.AuthTokenService;
import com.iot.greenhouse.service.PasswordResetService;
import com.iot.greenhouse.service.PasswordResetTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Pattern RESET_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final AuditLogService auditLogService;
    private final PasswordResetService passwordResetService;
    private final PasswordResetTokenService passwordResetTokenService;

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

    @PostMapping("/password-reset/request")
    public ResponseEntity<PasswordResetRequestResponse> requestPasswordReset(
            @RequestBody PasswordResetRequest request,
            HttpServletRequest httpRequest) {
        passwordResetService.requestReset(
                request == null ? null : request.email(),
                httpRequest.getRemoteAddr());
        return ResponseEntity.accepted().body(new PasswordResetRequestResponse(
                "Nếu email thuộc tài khoản đang hoạt động, hướng dẫn đặt lại mật khẩu sẽ được gửi."));
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<?> confirmPasswordReset(
            @RequestBody PasswordResetConfirmation request,
            HttpServletRequest httpRequest) {
        String token = request == null || request.token() == null ? "" : request.token();
        String password = request == null || request.newPassword() == null ? "" : request.newPassword();
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (!RESET_TOKEN_PATTERN.matcher(token).matches()
                || password.length() < 8
                || passwordBytes > 72) {
            return ResponseEntity.badRequest().body(new ApiError(
                    "Liên kết đặt lại không hợp lệ hoặc đã hết hạn, hoặc mật khẩu mới không đáp ứng yêu cầu."));
        }
        if (!passwordResetService.allowConfirmation(httpRequest.getRemoteAddr())) {
            return ResponseEntity.status(429).body(new ApiError(
                    "Bạn đã thử đặt lại mật khẩu quá nhiều lần. Vui lòng thử lại sau 15 phút."));
        }

        Optional<User> resetUser = passwordResetTokenService.resetPassword(token, password);
        if (resetUser.isEmpty()) {
            return ResponseEntity.badRequest().body(new ApiError(
                    "Liên kết đặt lại không hợp lệ hoặc đã hết hạn."));
        }

        User user = resetUser.get();
        auditLogService.record(user, httpRequest, "PASSWORD_RESET", "User",
                String.valueOf(user.getId()), null);
        return ResponseEntity.ok(new PasswordResetConfirmationResponse(
                "Mật khẩu đã được cập nhật. Tất cả phiên đăng nhập cũ đã bị đăng xuất."));
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

    private UserProfile toUserProfile(User user) {
        List<String> roles = user.getRoles() == null
                ? List.of()
                : user.getRoles().stream().map(Role::getName).sorted().toList();
        return new UserProfile(user.getId(), user.getUsername(), user.getEmail(), user.getFullName(), user.getStatus(), roles);
    }

    public record LoginRequest(String identifier, String password) {}
    public record PasswordResetRequest(String email) {}
    public record PasswordResetConfirmation(String token, String newPassword) {}
    public record PasswordResetRequestResponse(String message) {}
    public record PasswordResetConfirmationResponse(String message) {}
    public record LoginResponse(String token, Long userId, String username, String email, String fullName, String status, List<String> roles) {}
    public record UserProfile(Long userId, String username, String email, String fullName, String status, List<String> roles) {}
    public record ApiError(String message) {}
}
