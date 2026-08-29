package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus.Phase;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ReleaseCleanupStatusFactory {
  private final Clock clock;

  ReleaseCleanupStatusFactory(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  ReleasePlanStatus deleting(ReleasePlan plan, String reason, String message, boolean degraded) {
    ReleasePlanStatus current = plan.getStatus();
    long generation = Objects.requireNonNull(plan.getMetadata().getGeneration(), "generation");
    String uid = Objects.requireNonNull(plan.getMetadata().getUid(), "uid");
    Map<String, Condition> existing = conditionsByType(current);
    List<Condition> conditions = new ArrayList<>();
    conditions.add(condition(existing, "Ready", "False", generation, reason, message));
    conditions.add(condition(existing, "Progressing", "False", generation, reason, message));
    conditions.add(
        existing.getOrDefault(
            "GateHealthy",
            condition(
                existing,
                "GateHealthy",
                "Unknown",
                generation,
                "DeletionInProgress",
                "Metric evaluation is not authoritative during deletion")));
    conditions.add(
        existing.getOrDefault(
            "AuditHealthy",
            condition(
                existing,
                "AuditHealthy",
                "Unknown",
                generation,
                "DeletionInProgress",
                "Audit health is not yet available during deletion")));
    conditions.add(
        condition(existing, "Degraded", degraded ? "True" : "False", generation, reason, message));

    return new ReleasePlanStatus(
        generation,
        uid + ":" + generation,
        Phase.Deleting,
        current == null ? null : current.currentStep(),
        current == null ? null : current.stableRevision(),
        current == null ? null : current.canaryRevision(),
        current == null ? null : current.rolloutRef(),
        current == null ? null : current.lastGateDecision(),
        current == null ? null : current.lastTransition(),
        current == null ? null : current.pendingOutboxEvents(),
        List.copyOf(conditions));
  }

  private Condition condition(
      Map<String, Condition> existing,
      String type,
      String status,
      long generation,
      String reason,
      String message) {
    Condition previous = existing.get(type);
    String transitionTime =
        previous != null
                && status.equals(previous.getStatus())
                && reason.equals(previous.getReason())
                && message.equals(previous.getMessage())
            ? previous.getLastTransitionTime()
            : Instant.now(clock).toString();
    return new ConditionBuilder()
        .withType(type)
        .withStatus(status)
        .withObservedGeneration(generation)
        .withReason(reason)
        .withMessage(message)
        .withLastTransitionTime(transitionTime)
        .build();
  }

  private static Map<String, Condition> conditionsByType(ReleasePlanStatus status) {
    Map<String, Condition> byType = new LinkedHashMap<>();
    if (status != null && status.conditions() != null) {
      for (Condition condition : status.conditions()) {
        byType.put(condition.getType(), condition);
      }
    }
    return byType;
  }
}
