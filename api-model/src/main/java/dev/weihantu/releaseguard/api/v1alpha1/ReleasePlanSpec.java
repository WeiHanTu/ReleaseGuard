package dev.weihantu.releaseguard.api.v1alpha1;

import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import java.util.List;

public record ReleasePlanSpec(
    @Required Workload workload,
    @Required Network network,
    @Required Rollout rollout,
    @Required FailurePolicy failurePolicy,
    @Default("Delete") DeletionPolicy deletionPolicy) {

  public record Workload(
      @Required @Min(2) @Max(100) Integer replicas,
      @Required @Size(min = 1) String managedContainer,
      @Required PodTemplateSpec podTemplate) {}

  public record Network(
      @Required @Min(1) @Max(65535) Integer servicePort,
      @Required IntOrString targetPort,
      @Required Ingress ingress) {}

  public record Ingress(
      @Required @Size(min = 1) String className,
      @Required @Size(min = 1) String host,
      @Default("/") String path) {}

  public record Rollout(
      @Required @Size(min = 1) List<Step> steps, @Required @Size(min = 1) List<Gate> gates) {}

  public record Step(
      @Required @Min(1) @Max(100) Integer weight,
      @Pattern(KubernetesDuration.PATTERN) String duration) {}

  public record Gate(
      @Required @Size(min = 1) String policyRef,
      Double maxCanaryValue,
      Double maxDeltaFromStable,
      @Required @Min(1) Integer failureLimit,
      @Required @Min(1) Integer consecutiveSuccesses,
      @Required @Min(1) Integer inconclusiveLimit) {}

  public record FailurePolicy(
      @Required MetricUnavailable metricUnavailable,
      @Required GateFailed gateFailed,
      @Required @Pattern(KubernetesDuration.PATTERN) String rollbackDeadline,
      @Required AuditBackpressure auditBackpressure) {}

  public enum MetricUnavailable {
    Pause
  }

  public enum GateFailed {
    Rollback
  }

  public enum AuditBackpressure {
    PausePromotion
  }

  public enum DeletionPolicy {
    Delete,
    OrphanStable
  }
}
