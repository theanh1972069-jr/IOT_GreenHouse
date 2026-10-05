package com.iot.greenhouse.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a handler (or every handler of a controller) to the listed roles.
 * Enforced by {@link AuthInterceptor} after the bearer token has been resolved,
 * so a missing token still yields 401 and an insufficient role yields 403.
 *
 * <p>When the annotation is absent the endpoint only requires a valid session.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** Role names that grant access. Matched case-insensitively. */
    String[] value();
}
