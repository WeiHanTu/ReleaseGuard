package dev.weihantu.releaseguard.history.repository;

import java.time.Instant;

public record EventRecord(
    String eventId,
    String releaseId,
    String releaseUid,
    long generation,
    long sequence,
    String type,
    Instant occurredAt,
    String namespace,
    String releaseName,
    String gitRevision,
    String imageDigest,
    String traceId,
    String payload,
    String kafkaTopic,
    Integer kafkaPartition,
    Long kafkaOffset,
    Instant ingestedAt) {}
