package com.procureflow.app.identity;

import com.procureflow.app.config.SecuritySettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthConfigurationController {
    private final SecuritySettings settings;
    public AuthConfigurationController(SecuritySettings settings) { this.settings = settings; }
    public record PublicOidcConfiguration(String issuer, String clientId) {}

    @GetMapping("/api/auth/config")
    PublicOidcConfiguration configuration() {
        return new PublicOidcConfiguration(settings.issuer(), "procureflow-web");
    }
}
