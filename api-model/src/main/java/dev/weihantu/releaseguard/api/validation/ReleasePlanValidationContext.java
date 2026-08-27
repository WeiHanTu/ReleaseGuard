package dev.weihantu.releaseguard.api.validation;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import java.util.Map;

public record ReleasePlanValidationContext(
    String namespace,
    boolean allowMutableManagedImage,
    boolean requireAllImagesByDigest,
    int minimumReplicas,
    int maximumReplicas,
    Map<String, MetricPolicySpec> acceptedPolicies) {

  public ReleasePlanValidationContext {
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("namespace must not be blank");
    }
    if (minimumReplicas < 1 || maximumReplicas < minimumReplicas) {
      throw new IllegalArgumentException("invalid replica bounds");
    }
    acceptedPolicies = Map.copyOf(acceptedPolicies);
  }

  public static ReleasePlanValidationContext releaseEnvironment(
      String namespace, Map<String, MetricPolicySpec> acceptedPolicies) {
    return new ReleasePlanValidationContext(namespace, false, true, 2, 100, acceptedPolicies);
  }

  public static ReleasePlanValidationContext localDevelopment(
      String namespace, Map<String, MetricPolicySpec> acceptedPolicies) {
    return new ReleasePlanValidationContext(namespace, true, false, 2, 100, acceptedPolicies);
  }
}
