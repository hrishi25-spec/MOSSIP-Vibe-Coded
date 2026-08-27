package io.mosip.liveness.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> healthCheck() {
        return Map.of(
                "status", "ok",
                "service", "MOSIP Face Liveness & PAD Service"
        );
    }
}
