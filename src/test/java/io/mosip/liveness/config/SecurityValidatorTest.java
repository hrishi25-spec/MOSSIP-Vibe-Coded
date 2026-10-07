package io.mosip.liveness.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityValidatorTest {

    @Test
    void devProfileDoesNotRequireProductionSecrets() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertDoesNotThrow(() -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));
    }

    @Test
    void devProfileStillRejectsAWeakAdminKeyWhenConfigured() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("MOSIP_ADMIN_API_KEY", "short-key");

        assertThrows(IllegalStateException.class,
                () -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));
    }

    @Test
    void defaultProfileRequiresDatabasePasswordAndAdminKey() {
        MockEnvironment environment = new MockEnvironment();
        SecurityValidator validator = new SecurityValidator(environment);

        assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments()));

        environment.setProperty("POSTGRES_PASSWORD", "a-strong-database-password");
        assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments()));

        environment.setProperty("MOSIP_ADMIN_API_KEY", "a-strong-admin-key-with-at-least-32-chars");
        assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments()));
    }
}
