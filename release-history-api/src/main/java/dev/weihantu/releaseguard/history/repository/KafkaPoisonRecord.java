package dev.weihantu.releaseguard.history.repository;

import java.time.Instant;

public record KafkaPoisonRecord(
    String topic,
    int partition,
    long offset,
    String recordKey,
    String rawValue,
    String reason,
    Instant quarantinedAt) {}
