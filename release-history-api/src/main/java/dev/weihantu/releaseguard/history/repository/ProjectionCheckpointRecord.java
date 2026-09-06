package dev.weihantu.releaseguard.history.repository;

import java.time.Instant;

public record ProjectionCheckpointRecord(
    String topic, int partitionId, long lastOffset, Instant updatedAt) {}
