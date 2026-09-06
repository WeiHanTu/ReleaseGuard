package dev.weihantu.releaseguard.history.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvidenceResponse(String namespace, String name, List<GateEvidenceItem> items) {

  public EvidenceResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record GateEvidenceItem(
      String eventId,
      long sequence,
      Instant occurredAt,
      String decision,
      String evidenceRef,
      String evidenceDigest,
      Map<String, Object> details) {}
}
