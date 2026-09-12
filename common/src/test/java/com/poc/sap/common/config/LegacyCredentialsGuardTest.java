package com.poc.sap.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec sdd/common/autenticacion-sap.md R-5 / AC-6. */
class LegacyCredentialsGuardTest {

    /** Boot deja el placeholder literal si la variable no existe: eso es "sin credenciales". */
    @Test
    void unresolvedPlaceholderFailsAtStartupNamingTheVariables() {
        assertThatThrownBy(new LegacyCredentialsGuard("${POSTGRES_USER}", "${POSTGRES_PASSWORD}")::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder sin resolver")
                .hasMessageContaining("POSTGRES_USER/POSTGRES_PASSWORD")
                .hasMessageContaining("source scripts/env/local.env");
    }

    @Test
    void blankCredentialsFailAtStartup() {
        assertThatThrownBy(new LegacyCredentialsGuard("", "")::validate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(new LegacyCredentialsGuard("sa", "")::validate).isInstanceOf(IllegalStateException.class);
        assertThat(LegacyCredentialsGuard.missing(null)).isTrue();
    }

    @Test
    void realCredentialsPass() {
        assertThatCode(new LegacyCredentialsGuard("sa", "S3cret!")::validate).doesNotThrowAnyException();
    }
}
