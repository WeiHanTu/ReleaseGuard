package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEvent;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventSpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReleaseEventRenderer {
  private ReleaseEventRenderer() {}

  public static String eventName(String planName, long generation, long sequence) {
    return String.format("%s-%d-%06d", planName, generation, sequence);
  }

  public static ReleaseEvent render(
      ReleasePlan plan, ReleasePlanStatus.Transition transition, ReleasePlanStatus status) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(transition, "transition");
    Objects.requireNonNull(status, "status");

    String planName = plan.getMetadata().getName();
    String namespace = plan.getMetadata().getNamespace();
    String uid = plan.getMetadata().getUid();
    long generation =
        plan.getMetadata().getGeneration() == null ? 1L : plan.getMetadata().getGeneration();
    long sequence = transition.sequence() == null ? 1L : transition.sequence();

    String name = eventName(planName, generation, sequence);
    String releaseId = uid + ":" + generation;

    Map<String, Object> payload = new LinkedHashMap<>();
    if (status.phase() != null) {
      payload.put("phase", status.phase().name());
    }
    if (status.stableRevision() != null) {
      payload.put("stableRevision", status.stableRevision());
    }
    if (status.canaryRevision() != null) {
      payload.put("canaryRevision", status.canaryRevision());
    }
    if (status.currentStep() != null) {
      payload.put("currentStep", status.currentStep());
    }
    String gitRevision = gitRevision(plan);
    if (gitRevision != null) {
      payload.put("gitRevision", gitRevision);
    }
    String imageDigest = imageDigest(plan);
    if (imageDigest != null) {
      payload.put("imageDigest", imageDigest);
    }
    if (status.lastGateDecision() != null) {
      Map<String, Object> gate = new LinkedHashMap<>();
      gate.put("decision", status.lastGateDecision().decision());
      gate.put("evidenceRef", status.lastGateDecision().evidenceRef());
      gate.put("observedAt", status.lastGateDecision().observedAt());
      payload.put("lastGateDecision", gate);
    }

    ObjectMeta metadata =
        new ObjectMetaBuilder()
            .withName(name)
            .withNamespace(namespace)
            .withLabels(
                Map.of(
                    ReleaseResourceRenderer.PLAN_UID_LABEL,
                    uid,
                    ReleaseResourceRenderer.PLAN_NAME_LABEL,
                    planName,
                    EventEnvelope.RELEASE_NAME_LABEL,
                    planName,
                    EventEnvelope.RELEASE_GENERATION_LABEL,
                    String.valueOf(generation),
                    ReleaseResourceRenderer.MANAGED_BY_LABEL,
                    "releaseguard"))
            .build();

    ReleaseEventSpec spec =
        new ReleaseEventSpec(
            transition.eventId(),
            releaseId,
            sequence,
            transition.type(),
            transition.occurredAt(),
            Map.copyOf(payload));

    ReleaseEvent event = new ReleaseEvent();
    event.setMetadata(metadata);
    event.setSpec(spec);
    event.setStatus(
        new ReleaseEventStatus(ReleaseEventStatus.Phase.Pending, 0, null, null, null, List.of()));
    return event;
  }

  private static String gitRevision(ReleasePlan plan) {
    if (plan.getMetadata().getAnnotations() == null) {
      return null;
    }
    String gitRevision =
        plan.getMetadata().getAnnotations().get("delivery.weihantu.dev/git-revision");
    return gitRevision != null
        ? gitRevision
        : plan.getMetadata().getAnnotations().get("app.kubernetes.io/version");
  }

  private static String imageDigest(ReleasePlan plan) {
    if (plan.getSpec() == null
        || plan.getSpec().workload() == null
        || plan.getSpec().workload().podTemplate() == null
        || plan.getSpec().workload().podTemplate().getSpec() == null
        || plan.getSpec().workload().podTemplate().getSpec().getContainers() == null) {
      return null;
    }
    String managed = plan.getSpec().workload().managedContainer();
    return plan.getSpec().workload().podTemplate().getSpec().getContainers().stream()
        .filter(container -> managed == null || managed.equals(container.getName()))
        .findFirst()
        .map(io.fabric8.kubernetes.api.model.Container::getImage)
        .orElse(null);
  }
}
