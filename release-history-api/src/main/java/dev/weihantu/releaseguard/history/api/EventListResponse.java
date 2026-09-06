package dev.weihantu.releaseguard.history.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EventListResponse(
    List<EventView> items, String nextCursor, ReleaseResponse.FreshnessView freshness) {

  public EventListResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record EventView(
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
      Map<String, Object> payload) {}
}
