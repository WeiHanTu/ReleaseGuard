package dev.weihantu.releaseguard.api.v1alpha1;

import java.util.Map;
import java.util.Objects;

public record EventEnvelope(
    int schemaVersion,
    String eventId,
    String type,
    String releaseId,
    String releaseUid,
    long generation,
    long sequence,
    String namespace,
    String releaseName,
    String gitRevision,
    String imageDigest,
    String occurredAt,
    String traceId,
    Map<String, Object> payload) {

  public static final int CURRENT_SCHEMA_VERSION = 1;
  public static final String RELEASE_NAME_LABEL = "delivery.weihantu.dev/release-name";
  public static final String RELEASE_GENERATION_LABEL = "delivery.weihantu.dev/release-generation";

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(releaseId, "releaseId");
    Objects.requireNonNull(releaseUid, "releaseUid");
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(releaseName, "releaseName");
    Objects.requireNonNull(occurredAt, "occurredAt");
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  public static EventEnvelope from(ReleaseEvent event, ReleasePlan plan) {
    Objects.requireNonNull(event, "event");
    ReleaseEventSpec spec = event.getSpec();
    Objects.requireNonNull(spec, "event.spec");

    String releaseId = spec.releaseId();
    String releaseUid =
        releaseId.contains(":") ? releaseId.substring(0, releaseId.indexOf(':')) : releaseId;
    long generation = 1L;
    if (releaseId.contains(":")) {
      try {
        generation = Long.parseLong(releaseId.substring(releaseId.indexOf(':') + 1));
      } catch (NumberFormatException ignored) {
      }
    }

    String namespace = event.getMetadata().getNamespace();
    String releaseName = event.getMetadata().getName();
    if (event.getMetadata().getLabels() != null
        && event.getMetadata().getLabels().containsKey(RELEASE_NAME_LABEL)) {
      releaseName = event.getMetadata().getLabels().get(RELEASE_NAME_LABEL);
    }

    String gitRevision = payloadString(spec.payload(), "gitRevision");
    String imageDigest = payloadString(spec.payload(), "imageDigest");
    if (plan != null) {
      if (plan.getMetadata() != null) {
        if (plan.getMetadata().getName() != null) {
          releaseName = plan.getMetadata().getName();
        }
        if (plan.getMetadata().getAnnotations() != null) {
          gitRevision =
              plan.getMetadata().getAnnotations().get("delivery.weihantu.dev/git-revision");
          if (gitRevision == null) {
            gitRevision = plan.getMetadata().getAnnotations().get("app.kubernetes.io/version");
          }
        }
      }
      if (plan.getSpec() != null && plan.getSpec().workload() != null) {
        imageDigest = extractImageDigest(plan.getSpec().workload());
      }
    }

    return new EventEnvelope(
        CURRENT_SCHEMA_VERSION,
        spec.eventId(),
        spec.type(),
        spec.releaseId(),
        releaseUid,
        generation,
        spec.sequence(),
        namespace,
        releaseName,
        gitRevision,
        imageDigest,
        spec.occurredAt(),
        null,
        spec.payload());
  }

  private static String payloadString(Map<String, Object> payload, String key) {
    if (payload == null || payload.get(key) == null) {
      return null;
    }
    return payload.get(key).toString();
  }

  private static String extractImageDigest(ReleasePlanSpec.Workload workload) {
    if (workload.podTemplate() == null
        || workload.podTemplate().getSpec() == null
        || workload.podTemplate().getSpec().getContainers() == null) {
      return null;
    }
    String managed = workload.managedContainer();
    return workload.podTemplate().getSpec().getContainers().stream()
        .filter(c -> managed == null || managed.equals(c.getName()))
        .findFirst()
        .map(io.fabric8.kubernetes.api.model.Container::getImage)
        .orElse(null);
  }
}
