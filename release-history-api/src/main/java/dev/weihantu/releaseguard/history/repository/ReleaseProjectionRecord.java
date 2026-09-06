package dev.weihantu.releaseguard.history.repository;

import java.time.Instant;

public record ReleaseProjectionRecord(
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
