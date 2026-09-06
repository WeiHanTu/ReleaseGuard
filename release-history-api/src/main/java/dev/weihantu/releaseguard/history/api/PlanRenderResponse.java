package dev.weihantu.releaseguard.history.api;

import java.util.List;
import java.util.Map;

public record PlanRenderResponse(
    String targetName,
    String namespace,
    String uid,
    long generation,
    List<Map<String, Object>> generatedResources) {

  public PlanRenderResponse {
    generatedResources = generatedResources == null ? List.of() : List.copyOf(generatedResources);
  }
}
