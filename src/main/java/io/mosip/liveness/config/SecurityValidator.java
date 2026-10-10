package io.mosip.liveness.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Validates production credentials on startup. The local {@code dev} profile
 * uses H2 and keeps administration fail-closed when its optional key is absent.
 */
@Component
public class SecurityValidator implements ApplicationRunner {

    private final Environment environment;

    @Autowired
    public SecurityValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean production = !environment.acceptsProfiles(Profiles.of("dev"));
        if (production) {
            validatePostgresPassword();
        }
        validateAdminApiKey(production);
    }

    private void validatePostgresPassword() {
        String password = environment.getProperty("POSTGRES_PASSWORD");
        if (password == null || password.isBlank()) {
            password = environment.getProperty("spring.datasource.password");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("POSTGRES_PASSWORD must be set");
        }
        if ("change_me".equals(password)) {
            throw new IllegalStateException("POSTGRES_PASSWORD must not be the default value 'change_me'");
        }
        if (password.length() < 8) {
            throw new IllegalStateException("POSTGRES_PASSWORD must be at least 8 characters long");
        }
    }

    private void validateAdminApiKey(boolean required) {
        String key = environment.getProperty("MOSIP_ADMIN_API_KEY");
        if (key == null || key.isBlank()) {
            key = environment.getProperty("mosip.security.admin-api-key");
        }
        if (key == null || key.isBlank()) {
            if (required) {
                throw new IllegalStateException("MOSIP_ADMIN_API_KEY must be set");
            }
            return;
        }
        if (key.length() < 32) {
            throw new IllegalStateException("MOSIP_ADMIN_API_KEY must be at least 32 characters long");
        }
    }
}
