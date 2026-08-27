package dev.weihantu.releaseguard.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Condition;
import java.util.List;

public record ReleasePlanStatus(
    Long observedGeneration,
    String releaseId,
    Phase phase,
    Integer currentStep,
    String stableRevision,
    String canaryRevision,
    ResourceReference rolloutRef,
    GateDecision lastGateDecision,
    Transition lastTransition,
    Integer pendingOutboxEvents,
    List<Condition> conditions) {

  public enum Phase {
    Pending,
    Validating,
    Bootstrapping,
    Progressing,
    Paused,
    RollingBack,
    Succeeded,
    RolledBack,
    Failed,
    Deleting
  }

  public record ResourceReference(String name) {}

  public record GateDecision(String decision, String evidenceRef, String observedAt) {}

  public record Transition(Long sequence, String eventId, String type, String occurredAt) {}
}
