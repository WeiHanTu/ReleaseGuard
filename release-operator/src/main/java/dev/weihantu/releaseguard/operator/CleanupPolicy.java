package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.KubernetesDuration;
import java.time.Duration;
import java.util.Map;

record CleanupPolicy(Duration deadline, Duration requeueAfter) {
  static final String DEADLINE_ENV = "RELEASEGUARD_CLEANUP_DEADLINE";
  static final String REQUEUE_ENV = "RELEASEGUARD_CLEANUP_REQUEUE_INTERVAL";
  static final CleanupPolicy DEFAULT =
      new CleanupPolicy(Duration.ofSeconds(60), Duration.ofSeconds(1));

  CleanupPolicy {
    if (deadline == null || deadline.isNegative() || deadline.isZero()) {
      throw new IllegalArgumentException("cleanup deadline must be positive");
    }
    if (requeueAfter == null || requeueAfter.isNegative() || requeueAfter.isZero()) {
      throw new IllegalArgumentException("cleanup requeue interval must be positive");
    }
  }

  static CleanupPolicy fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  static CleanupPolicy fromEnvironment(Map<String, String> environment) {
    return new CleanupPolicy(
        parse(environment, DEADLINE_ENV, DEFAULT.deadline),
        parse(environment, REQUEUE_ENV, DEFAULT.requeueAfter));
  }

  private static Duration parse(
      Map<String, String> environment, String variable, Duration defaultValue) {
    String configured = environment.get(variable);
    if (configured == null || configured.isBlank()) {
      return defaultValue;
    }
    return KubernetesDuration.parse(configured)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    variable
                        + " must be a positive Kubernetes duration such as 500ms, 30s, or 2m"));
  }
}
