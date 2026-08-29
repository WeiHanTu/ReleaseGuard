package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import java.util.List;
import java.util.Map;

final class OperatorTestFixtures {
  static final String UID = "8cc875b3-6135-4f1b-a50e-7f12b826e902";
  static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  static final String IMAGE = "ghcr.io/weihantu/checkout@sha256:" + DIGEST;

  private OperatorTestFixtures() {}

  static ReleasePlan plan(long generation) {
    return plan(generation, "demo", ReleasePlanSpec.DeletionPolicy.Delete);
  }

  static ReleasePlan plan(
      long generation, String namespace, ReleasePlanSpec.DeletionPolicy deletionPolicy) {
    ReleasePlan plan = new ReleasePlan();
    plan.setMetadata(
        new ObjectMetaBuilder()
            .withName("checkout")
            .withNamespace(namespace)
            .withUid(UID)
            .withGeneration(generation)
            .build());
    plan.setSpec(spec(deletionPolicy));
    return plan;
  }

  static ReleasePlanSpec spec() {
    return spec(ReleasePlanSpec.DeletionPolicy.Delete);
  }

  static ReleasePlanSpec spec(ReleasePlanSpec.DeletionPolicy deletionPolicy) {
    return new ReleasePlanSpec(
        new ReleasePlanSpec.Workload(10, "app", podTemplate()),
        new ReleasePlanSpec.Network(
            80,
            new IntOrString("http"),
            new ReleasePlanSpec.Ingress("nginx", "checkout.releaseguard.local", "/")),
        new ReleasePlanSpec.Rollout(
            List.of(
                new ReleasePlanSpec.Step(5, "2m"),
                new ReleasePlanSpec.Step(25, "5m"),
                new ReleasePlanSpec.Step(100, null)),
            List.of(new ReleasePlanSpec.Gate("http-errors", 0.01, 0.005, 3, 2))),
        new ReleasePlanSpec.FailurePolicy(
            ReleasePlanSpec.MetricUnavailable.Pause,
            ReleasePlanSpec.GateFailed.Rollback,
            "60s",
            ReleasePlanSpec.AuditBackpressure.PausePromotion),
        deletionPolicy);
  }

  static Map<String, MetricPolicySpec> policies() {
    return Map.of("http-errors", policy());
  }

  static MetricPolicySpec policy() {
    return new MetricPolicySpec(
        MetricPolicySpec.Direction.LowerIsBetter,
        "ratio",
        "sum(rate(requests{revision=\"{{revision}}\"}[{{window}}]))",
        "sum(increase(requests{revision=\"{{revision}}\"}[{{window}}]))",
        new MetricPolicySpec.AllowedThresholds(
            new MetricPolicySpec.NumericRange(0.0, 0.10),
            new MetricPolicySpec.NumericRange(0.0, 0.05)),
        100L,
        "2m",
        "20s",
        "5s");
  }

  private static PodTemplateSpec podTemplate() {
    return new PodTemplateSpecBuilder()
        .withNewMetadata()
        .addToLabels("app.kubernetes.io/name", "user-controlled-label")
        .endMetadata()
        .withNewSpec()
        .addNewContainer()
        .withName("app")
        .withImage(IMAGE)
        .addNewPort()
        .withName("http")
        .withContainerPort(8080)
        .endPort()
        .endContainer()
        .endSpec()
        .build();
  }
}
