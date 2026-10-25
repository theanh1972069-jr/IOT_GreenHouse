package com.iot.greenhouse.config;

import com.iot.greenhouse.entity.Role;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.UserRepository;
import com.iot.greenhouse.service.AuditLogService;
import com.iot.greenhouse.service.AuthTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTHENTICATED_USER_ATTRIBUTE = "authenticatedUser";

    private final AuthTokenService authTokenService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS preflights never carry credentials by design, so they must reach the
        // CORS processor instead of being rejected as an unauthenticated request.
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
        }

        String token = authorization.substring(7).trim();
        User user = authTokenService.findUserId(token)
                .flatMap(userRepository::findByIdWithRoles)
                .filter(candidate -> "ACTIVE".equalsIgnoreCase(candidate.getStatus()))
                .orElse(null);
        if (user == null) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
        }

        request.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, user);

        if (!hasRequiredRole(handler, user)) {
            auditLogService.record(user, request, "ACCESS_DENIED", "Endpoint", request.getRequestURI(),
                    Map.of("method", request.getMethod(), "reason", "role_required"));
            return reject(response, HttpServletResponse.SC_FORBIDDEN,
                    "Bạn không có quyền thực hiện thao tác này.");
        }
        return true;
    }

    private boolean hasRequiredRole(Object handler, User user) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole requirement = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requirement == null) {
            requirement = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requirement == null) {
            return true;
        }
        final String[] requiredRoles = requirement.value();
        if (user.getRoles() == null) {
            return false;
        }
        return user.getRoles().stream()
                .map(Role::getName)
                .filter(name -> name != null)
                .map(name -> normalizeRole(name))
                .anyMatch(granted -> Arrays.stream(requiredRoles)
                        .anyMatch(required -> required.equalsIgnoreCase(granted)));
    }

    private static String normalizeRole(String role) {
        String normalized = role.toUpperCase(Locale.ROOT);
        return normalized.equals("USER") ? "VIEWER" : normalized;
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
        return false;
    }
}