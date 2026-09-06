package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javaoperatorsdk.operator.api.config.LeaderElectionConfiguration;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LeaderElectionPolicyTest {

  @Test
  void disabledByDefaultWhenUnset() {
    LeaderElectionPolicy policy = LeaderElectionPolicy.fromEnvironment(Map.of());
    assertFalse(policy.enabled());
    assertTrue(policy.toConfiguration().isEmpty());
  }

  @Test
  void parsesCustomEnvironmentWhenEnabled() {
    Map<String, String> env =
        Map.of(
            LeaderElectionPolicy.ENABLED_ENV, "true",
            LeaderElectionPolicy.LEASE_NAME_ENV, "custom-lock",
            LeaderElectionPolicy.LEASE_NAMESPACE_ENV, "custom-ns",
            LeaderElectionPolicy.IDENTITY_ENV, "pod-xyz",
            LeaderElectionPolicy.LEASE_DURATION_ENV, "20s",
            LeaderElectionPolicy.RENEW_DEADLINE_ENV, "15s",
            LeaderElectionPolicy.RETRY_PERIOD_ENV, "3s");

    LeaderElectionPolicy policy = LeaderElectionPolicy.fromEnvironment(env);
    assertTrue(policy.enabled());
    assertEquals("custom-lock", policy.leaseName());
    assertEquals("custom-ns", policy.leaseNamespace());
    assertEquals("pod-xyz", policy.identity());
    assertEquals(Duration.ofSeconds(20), policy.leaseDuration());
    assertEquals(Duration.ofSeconds(15), policy.renewDeadline());
    assertEquals(Duration.ofSeconds(3), policy.retryPeriod());

    assertTrue(policy.toConfiguration().isPresent());
    LeaderElectionConfiguration config = policy.toConfiguration().get();
    assertEquals("custom-lock", config.getLeaseName());
    assertEquals("custom-ns", config.getLeaseNamespace().orElse(null));
    assertEquals("pod-xyz", config.getIdentity().orElse(null));
    assertEquals(Duration.ofSeconds(20), config.getLeaseDuration());
    assertEquals(Duration.ofSeconds(15), config.getRenewDeadline());
    assertEquals(Duration.ofSeconds(3), config.getRetryPeriod());
  }

  @Test
  void fallsBackToPodNamespaceAndPodName() {
    Map<String, String> env =
        Map.of(
            LeaderElectionPolicy.ENABLED_ENV, "true",
            LeaderElectionPolicy.POD_NAMESPACE_ENV, "injected-ns",
            LeaderElectionPolicy.POD_NAME_ENV, "injected-pod");

    LeaderElectionPolicy policy = LeaderElectionPolicy.fromEnvironment(env);
    assertTrue(policy.enabled());
    assertEquals("releaseguard-operator-lock", policy.leaseName());
    assertEquals("injected-ns", policy.leaseNamespace());
    assertEquals("injected-pod", policy.identity());
    assertEquals(LeaderElectionPolicy.DEFAULT_LEASE_DURATION, policy.leaseDuration());
    assertEquals(LeaderElectionPolicy.DEFAULT_RENEW_DEADLINE, policy.renewDeadline());
    assertEquals(LeaderElectionPolicy.DEFAULT_RETRY_PERIOD, policy.retryPeriod());
  }

  @Test
  void rejectsInvalidDurationsWhenEnabled() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LeaderElectionPolicy.fromEnvironment(
                Map.of(
                    LeaderElectionPolicy.ENABLED_ENV, "true",
                    LeaderElectionPolicy.LEASE_DURATION_ENV, "10s",
                    LeaderElectionPolicy.RENEW_DEADLINE_ENV, "15s")));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            LeaderElectionPolicy.fromEnvironment(
                Map.of(
                    LeaderElectionPolicy.ENABLED_ENV, "true",
                    LeaderElectionPolicy.RENEW_DEADLINE_ENV, "5s",
                    LeaderElectionPolicy.RETRY_PERIOD_ENV, "5s")));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            LeaderElectionPolicy.fromEnvironment(
                Map.of(
                    LeaderElectionPolicy.ENABLED_ENV, "true",
                    LeaderElectionPolicy.LEASE_DURATION_ENV, "invalid")));
  }

  @Test
  void rejectsBlankLeaseNameOrNamespaceWhenEnabled() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LeaderElectionPolicy(
                true,
                "  ",
                "ns",
                "id",
                Duration.ofSeconds(15),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2)));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LeaderElectionPolicy(
                true,
                "lock",
                "  ",
                "id",
                Duration.ofSeconds(15),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2)));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LeaderElectionPolicy(
                true,
                "lock",
                "ns",
                "  ",
                Duration.ofSeconds(15),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2)));
  }
}
