package ru.transaction.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    private static final String ISSUER = "http://keycloak:8080/realms/my-realm";
    private final OAuth2TokenValidator<Jwt> validator = SecurityConfig.tokenValidator(ISSUER, "my-client-id");

    @Test
    void tokenIssuedForGatewayClientIsAccepted() {
        assertThat(validator.validate(token(ISSUER, "my-client-id")).hasErrors()).isFalse();
    }

    @Test
    void tokenIssuedForAnotherClientIsRejected() {
        assertThat(validator.validate(token(ISSUER, "other-client")).hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, null)).hasErrors()).isTrue();
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        assertThat(validator.validate(token("http://evil/realms/my-realm", "my-client-id")).hasErrors()).isTrue();
    }

    @Test
    void realmRolesBecomeAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user")
                .claim("realm_access", Map.of("roles", List.of("admin", "user"))).build();

        AbstractAuthenticationToken authentication = SecurityConfig.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_admin", "ROLE_user");
    }

    private static Jwt token(String issuer, String authorizedParty) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("user")
                .issuer(issuer).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (authorizedParty != null) {
            builder.claim("azp", authorizedParty);
        }
        return builder.build();
    }
}
