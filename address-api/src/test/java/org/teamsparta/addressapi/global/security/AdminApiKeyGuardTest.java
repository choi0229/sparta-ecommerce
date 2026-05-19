package org.teamsparta.addressapi.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class AdminApiKeyGuardTest {

    @Test
    @DisplayName("올바른 키 → 통과")
    void validate_correctKey_passes() {
        AdminApiKeyGuard guard = new AdminApiKeyGuard("secret-key");
        assertThatCode(() -> guard.validate("secret-key")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("잘못된 키 → 403")
    void validate_wrongKey_throwsForbidden() {
        AdminApiKeyGuard guard = new AdminApiKeyGuard("secret-key");
        assertThatThrownBy(() -> guard.validate("wrong-key"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("헤더 누락(null) → 403")
    void validate_nullKey_throwsForbidden() {
        AdminApiKeyGuard guard = new AdminApiKeyGuard("secret-key");
        assertThatThrownBy(() -> guard.validate(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("설정값 비어 있으면 올바른 헤더라도 403")
    void validate_emptyConfigKey_throwsForbidden() {
        AdminApiKeyGuard guard = new AdminApiKeyGuard("");
        assertThatThrownBy(() -> guard.validate("any-key"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("설정값 공백만 있어도 403")
    void validate_blankConfigKey_throwsForbidden() {
        AdminApiKeyGuard guard = new AdminApiKeyGuard("   ");
        assertThatThrownBy(() -> guard.validate("   "))
                .isInstanceOf(ResponseStatusException.class);
    }
}
