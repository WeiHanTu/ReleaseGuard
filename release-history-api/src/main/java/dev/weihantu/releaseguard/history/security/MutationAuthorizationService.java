package dev.weihantu.releaseguard.history.security;

import dev.weihantu.releaseguard.history.api.MutationForbiddenException;
import dev.weihantu.releaseguard.history.config.HistoryProperties;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class MutationAuthorizationService {

  private final HistoryProperties.Auth auth;

  public MutationAuthorizationService(HistoryProperties properties) {
    this.auth = Objects.requireNonNull(properties.getAuth(), "auth");
  }

  /** Enforces the role and namespace/application scope boundary defined by RG-SEC-002/004. */
  public void requireActionAccess(Jwt jwt, String namespace, String application) {
    Objects.requireNonNull(jwt, "jwt");
    if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
      throw new MutationForbiddenException("Authenticated token does not contain a subject");
    }
    String normalizedNamespace = normalized(namespace, "namespace");
    String target = normalizedNamespace + "/" + normalized(application, "application");
    Set<String> roles = claimValues(jwt, auth.getRolesClaim());

    if (roles.contains(auth.getAdministratorRole())) {
      return;
    }

    Set<String> scopes = claimValues(jwt, auth.getActionScopesClaim());
    String namespaceScope = normalizedNamespace + "/*";
    if (roles.contains(auth.getOperatorRole())
        && (scopes.contains(target) || scopes.contains(namespaceScope))) {
      return;
    }

    throw new MutationForbiddenException(
        "Authenticated identity is not authorized to mutate release " + target);
  }

  private static Set<String> claimValues(Jwt jwt, String claimName) {
    Object raw = jwt.getClaim(normalized(claimName, "claim name"));
    if (raw == null) {
      return Set.of();
    }

    Set<String> values = new LinkedHashSet<>();
    if (raw instanceof Collection<?> collection) {
      collection.forEach(value -> addValue(values, value));
    } else if (raw instanceof String text) {
      for (String value : text.split("[\\s,]+")) {
        addValue(values, value);
      }
    } else {
      addValue(values, raw);
    }
    return Set.copyOf(values);
  }

  private static void addValue(Set<String> values, Object raw) {
    if (raw != null && !raw.toString().isBlank()) {
      values.add(raw.toString().trim());
    }
  }

  private static String normalized(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value.trim();
  }
}
