package dev.weihantu.releaseguard.history.api;

import java.time.Instant;
import java.util.Map;

public record ReleaseResponse(ReleaseView release, FreshnessView freshness) {

  public record ReleaseView(
      String releaseId,
      String releaseUid,
      String namespace,
      String name,
      long generation,
      String phase,
      Integer currentStep,
      String stableRevision,
      String canaryRevision,
      long lastTransitionSequence,
      String lastEventId,
      String lastEventType,
      Instant lastEventAt,
      String gateDecision,
      String gateEvidenceRef,
      String gateEvidenceDigest,
      Instant updatedAt) {}

  public record FreshnessView(
      Map<Integer, Long> lastKafkaOffsets,
      Instant projectedThrough,
      long quarantinedEventsCount,
      boolean stale) {}
}
