package dev.weihantu.releaseguard.api.validation;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import java.util.List;

final class TestFixtures {
  static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  static final String IMAGE = "ghcr.io/weihantu/checkout@sha256:" + DIGEST;

  private TestFixtures() {}

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

  static ReleasePlanSpec plan() {
    return withSteps(
        List.of(
            new ReleasePlanSpec.Step(5, "2m"),
            new ReleasePlanSpec.Step(25, "5m"),
            new ReleasePlanSpec.Step(100, null)));
  }

  static ReleasePlanSpec withSteps(List<ReleasePlanSpec.Step> steps) {
    return new ReleasePlanSpec(
        new ReleasePlanSpec.Workload(10, "app", podTemplate(IMAGE)),
        new ReleasePlanSpec.Network(
            80,
            new IntOrString("http"),
            new ReleasePlanSpec.Ingress("nginx", "checkout.releaseguard.local", "/")),
        new ReleasePlanSpec.Rollout(
            steps, List.of(new ReleasePlanSpec.Gate("http-errors", 0.01, 0.005, 3, 2, 3))),
        new ReleasePlanSpec.FailurePolicy(
            ReleasePlanSpec.MetricUnavailable.Pause,
            ReleasePlanSpec.GateFailed.Rollback,
            "60s",
            ReleasePlanSpec.AuditBackpressure.PausePromotion),
        ReleasePlanSpec.DeletionPolicy.Delete);
  }

  static PodTemplateSpec podTemplate(String image) {
    return new PodTemplateSpecBuilder()
        .withNewMetadata()
        .addToLabels("app.kubernetes.io/name", "checkout")
        .endMetadata()
        .withNewSpec()
        .addNewContainer()
        .withName("app")
        .withImage(image)
        .addNewPort()
        .withName("http")
        .withContainerPort(8080)
        .endPort()
        .endContainer()
        .endSpec()
        .build();
  }
}
