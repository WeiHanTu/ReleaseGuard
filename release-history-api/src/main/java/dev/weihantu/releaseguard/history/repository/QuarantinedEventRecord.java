package dev.weihantu.releaseguard.history.repository;

import java.time.Instant;

public record QuarantinedEventRecord(
    String eventId,
    String releaseId,
    long sequence,
    Long expectedSequence,
    String reason,
    String rawEnvelope,
    Instant quarantinedAt) {}
