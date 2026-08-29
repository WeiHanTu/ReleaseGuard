package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CleanupPolicyTest {
  @Test
  void usesDocumentedDefaultsWhenEnvironmentIsEmpty() {
    assertEquals(CleanupPolicy.DEFAULT, CleanupPolicy.fromEnvironment(Map.of()));
  }

  @Test
  void readsKubernetesDurationsFromEnvironment() {
    CleanupPolicy policy =
        CleanupPolicy.fromEnvironment(
            Map.of(CleanupPolicy.DEADLINE_ENV, "2m", CleanupPolicy.REQUEUE_ENV, "500ms"));

    assertEquals(Duration.ofMinutes(2), policy.deadline());
    assertEquals(Duration.ofMillis(500), policy.requeueAfter());
  }

  @Test
  void rejectsInvalidEnvironmentConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CleanupPolicy.fromEnvironment(Map.of(CleanupPolicy.DEADLINE_ENV, "soon")));
  }
}
