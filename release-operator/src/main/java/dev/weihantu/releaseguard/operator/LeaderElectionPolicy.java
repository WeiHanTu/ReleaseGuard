package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.KubernetesDuration;
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfiguration;
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfigurationBuilder;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

record LeaderElectionPolicy(
    boolean enabled,
    String leaseName,
    String leaseNamespace,
    String identity,
    Duration leaseDuration,
    Duration renewDeadline,
    Duration retryPeriod) {

  static final LeaderElectionPolicy DISABLED =
      new LeaderElectionPolicy(false, null, null, null, null, null, null);

  static final String ENABLED_ENV = "RELEASEGUARD_LEADER_ELECTION_ENABLED";
  static final String LEASE_NAME_ENV = "RELEASEGUARD_LEASE_NAME";
  static final String LEASE_NAMESPACE_ENV = "RELEASEGUARD_LEASE_NAMESPACE";
  static final String POD_NAMESPACE_ENV = "POD_NAMESPACE";
  static final String IDENTITY_ENV = "RELEASEGUARD_LEADER_IDENTITY";
  static final String POD_NAME_ENV = "POD_NAME";
  static final String HOSTNAME_ENV = "HOSTNAME";
  static final String LEASE_DURATION_ENV = "RELEASEGUARD_LEASE_DURATION";
  static final String RENEW_DEADLINE_ENV = "RELEASEGUARD_LEASE_RENEW_DEADLINE";
  static final String RETRY_PERIOD_ENV = "RELEASEGUARD_LEASE_RETRY_PERIOD";

  static final String DEFAULT_LEASE_NAME = "releaseguard-operator-lock";
  static final String DEFAULT_LEASE_NAMESPACE = "releaseguard";
  static final Duration DEFAULT_LEASE_DURATION =
      LeaderElectionConfiguration.LEASE_DURATION_DEFAULT_VALUE;
  static final Duration DEFAULT_RENEW_DEADLINE =
      LeaderElectionConfiguration.RENEW_DEADLINE_DEFAULT_VALUE;
  static final Duration DEFAULT_RETRY_PERIOD =
      LeaderElectionConfiguration.RETRY_PERIOD_DEFAULT_VALUE;

  LeaderElectionPolicy {
    if (enabled) {
      Objects.requireNonNull(leaseName, "leaseName");
      Objects.requireNonNull(leaseNamespace, "leaseNamespace");
      Objects.requireNonNull(identity, "identity");
      if (leaseName.isBlank()) {
        throw new IllegalArgumentException(
            "leaseName cannot be blank when leader election is enabled");
      }
      if (leaseNamespace.isBlank()) {
        throw new IllegalArgumentException(
            "leaseNamespace cannot be blank when leader election is enabled");
      }
      if (identity.isBlank()) {
        throw new IllegalArgumentException(
            "identity cannot be blank when leader election is enabled");
      }
      if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
        throw new IllegalArgumentException("leaseDuration must be positive");
      }
      if (renewDeadline == null || renewDeadline.isNegative() || renewDeadline.isZero()) {
        throw new IllegalArgumentException("renewDeadline must be positive");
      }
      if (retryPeriod == null || retryPeriod.isNegative() || retryPeriod.isZero()) {
        throw new IllegalArgumentException("retryPeriod must be positive");
      }
      if (renewDeadline.compareTo(leaseDuration) >= 0) {
        throw new IllegalArgumentException("renewDeadline must be less than leaseDuration");
      }
      if (retryPeriod.compareTo(renewDeadline) >= 0) {
        throw new IllegalArgumentException("retryPeriod must be less than renewDeadline");
      }
    }
  }

  static LeaderElectionPolicy fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  static LeaderElectionPolicy fromEnvironment(Map<String, String> environment) {
    boolean enabled = parseBoolean(environment.get(ENABLED_ENV), false);
    String leaseName = getOrDefault(environment.get(LEASE_NAME_ENV), DEFAULT_LEASE_NAME);

    String leaseNamespace = environment.get(LEASE_NAMESPACE_ENV);
    if (leaseNamespace == null || leaseNamespace.isBlank()) {
      leaseNamespace = environment.get(POD_NAMESPACE_ENV);
    }
    if (leaseNamespace == null || leaseNamespace.isBlank()) {
      leaseNamespace = DEFAULT_LEASE_NAMESPACE;
    }

    String identity = environment.get(IDENTITY_ENV);
    if (identity == null || identity.isBlank()) {
      identity = environment.get(POD_NAME_ENV);
    }
    if (identity == null || identity.isBlank()) {
      identity = environment.get(HOSTNAME_ENV);
    }
    if (identity == null || identity.isBlank()) {
      identity = "releaseguard-operator-" + UUID.randomUUID();
    }

    Duration leaseDuration = parseDuration(environment, LEASE_DURATION_ENV, DEFAULT_LEASE_DURATION);
    Duration renewDeadline = parseDuration(environment, RENEW_DEADLINE_ENV, DEFAULT_RENEW_DEADLINE);
    Duration retryPeriod = parseDuration(environment, RETRY_PERIOD_ENV, DEFAULT_RETRY_PERIOD);

    return new LeaderElectionPolicy(
        enabled, leaseName, leaseNamespace, identity, leaseDuration, renewDeadline, retryPeriod);
  }

  Optional<LeaderElectionConfiguration> toConfiguration() {
    if (!enabled) {
      return Optional.empty();
    }
    return Optional.of(
        LeaderElectionConfigurationBuilder.aLeaderElectionConfiguration(leaseName)
            .withLeaseNamespace(leaseNamespace)
            .withIdentity(identity)
            .withLeaseDuration(leaseDuration)
            .withRenewDeadline(renewDeadline)
            .withRetryPeriod(retryPeriod)
            .build());
  }

  private static boolean parseBoolean(String value, boolean defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Boolean.parseBoolean(value.trim());
  }

  private static String getOrDefault(String value, String defaultValue) {
    return (value == null || value.isBlank()) ? defaultValue : value.trim();
  }

  private static Duration parseDuration(
      Map<String, String> environment, String variable, Duration defaultValue) {
    String configured = environment.get(variable);
    if (configured == null || configured.isBlank()) {
      return defaultValue;
    }
    return KubernetesDuration.parse(configured.trim())
        .filter(duration -> !duration.isNegative() && !duration.isZero())
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    variable + " must be a positive Kubernetes duration such as 2s, 10s, or 15s"));
  }
}
