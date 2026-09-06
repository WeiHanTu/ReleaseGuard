package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEvent;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventSpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ReleaseEventRenderer {
  private ReleaseEventRenderer() {}

  static String eventName(String planName, long generation, long sequence) {
    return String.format("%s-%d-%06d", planName, generation, sequence);
  }

  static ReleaseEvent render(
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
                    "delivery.weihantu.dev/release-name",
                    planName,
                    "delivery.weihantu.dev/release-generation",
                    String.valueOf(generation),
                    ReleaseResourceRenderer.MANAGED_BY_LABEL,
                    "releaseguard"))
            .withOwnerReferences(
                new OwnerReferenceBuilder()
                    .withApiVersion("delivery.weihantu.dev/v1alpha1")
                    .withKind("ReleasePlan")
                    .withName(planName)
                    .withUid(uid)
                    .withController(true)
                    .withBlockOwnerDeletion(true)
                    .build())
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
}
