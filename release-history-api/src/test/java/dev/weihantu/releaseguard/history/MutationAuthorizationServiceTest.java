package dev.weihantu.releaseguard.history;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.weihantu.releaseguard.history.api.MutationForbiddenException;
import dev.weihantu.releaseguard.history.config.HistoryProperties;
import dev.weihantu.releaseguard.history.security.MutationAuthorizationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class MutationAuthorizationServiceTest {

  private MutationAuthorizationService authorizationService;

  @BeforeEach
  void setUp() {
    authorizationService = new MutationAuthorizationService(new HistoryProperties());
  }

  @Test
  void administratorCanMutateWithoutApplicationScope() {
    Jwt jwt = jwt(Map.of("roles", List.of("platform-administrator")));

    assertDoesNotThrow(
        () -> authorizationService.requireActionAccess(jwt, "production", "checkout"));
  }

  @Test
  void operatorCanUseNamespaceWildcardScope() {
    Jwt jwt =
        jwt(
            Map.of(
                "roles", List.of("developer-operator"),
                "releaseguard_actions", List.of("production/*")));

    assertDoesNotThrow(
        () -> authorizationService.requireActionAccess(jwt, "production", "checkout"));
  }

  @Test
  void operatorWithoutMatchingScopeIsRejected() {
    Jwt jwt =
        jwt(
            Map.of(
                "roles", List.of("developer-operator"),
                "releaseguard_actions", List.of("staging/*")));

    assertThrows(
        MutationForbiddenException.class,
        () -> authorizationService.requireActionAccess(jwt, "production", "checkout"));
  }

  @Test
  void tokenWithoutSubjectIsRejected() {
    Instant now = Instant.now();
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .issuedAt(now.minusSeconds(30))
            .expiresAt(now.plusSeconds(300))
            .claim("roles", List.of("platform-administrator"))
            .build();

    assertThrows(
        MutationForbiddenException.class,
        () -> authorizationService.requireActionAccess(jwt, "production", "checkout"));
  }

  private static Jwt jwt(Map<String, Object> claims) {
    Instant now = Instant.now();
    return Jwt.withTokenValue("test-token")
        .header("alg", "none")
        .subject("engineer@example.com")
        .issuedAt(now.minusSeconds(30))
        .expiresAt(now.plusSeconds(300))
        .claims(values -> values.putAll(claims))
        .build();
  }
}
