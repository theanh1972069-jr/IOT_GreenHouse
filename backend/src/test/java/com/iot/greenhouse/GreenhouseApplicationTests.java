package com.iot.greenhouse;

import com.iot.greenhouse.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Context load smoke test. Extends {@link TestDatabase} so it runs against the
 * disposable PostgreSQL container instead of requiring a local database.
 */
@SpringBootTest
@EnabledIf("dockerAvailable")
class GreenhouseApplicationTests extends TestDatabase {

    static boolean dockerAvailable() {
        return TestDatabase.isRunning();
    }

    @Test
    void contextLoads() {
    }
}
