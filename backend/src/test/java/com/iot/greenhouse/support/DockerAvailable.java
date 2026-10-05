package com.iot.greenhouse.support;

import org.testcontainers.DockerClientFactory;

/**
 * Detects a usable Docker daemon without throwing.
 *
 * <p>{@code DockerClientFactory.instance().isDockerAvailable()} logs an alarming
 * stack trace on machines without Docker, so the check is wrapped and the result
 * cached - it is consulted once per JVM from a static initialiser.
 */
public final class DockerAvailable {

    private static final boolean AVAILABLE = probe();

    private DockerAvailable() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    private static boolean probe() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
