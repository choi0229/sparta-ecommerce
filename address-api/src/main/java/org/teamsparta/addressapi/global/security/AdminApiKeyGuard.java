package org.teamsparta.addressapi.global.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AdminApiKeyGuard {

    private final String adminApiKey;

    public AdminApiKeyGuard(@Value("${address.admin.api-key:}") String adminApiKey) {
        this.adminApiKey = adminApiKey;
    }

    // 설정값이 비어 있거나 헤더와 일치하지 않으면 403
    public void validate(String providedKey) {
        if (adminApiKey.isBlank() || !adminApiKey.equals(providedKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
