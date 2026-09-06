package dev.weihantu.releaseguard.history.projection;

import java.time.Instant;
import java.util.Map;

public record ProjectionFreshness(
    Map<Integer, Long> lastKafkaOffsets,
    Instant projectedThrough,
    long quarantinedEventsCount,
    boolean stale) {

  public ProjectionFreshness {
    lastKafkaOffsets = lastKafkaOffsets == null ? Map.of() : Map.copyOf(lastKafkaOffsets);
  }
}
