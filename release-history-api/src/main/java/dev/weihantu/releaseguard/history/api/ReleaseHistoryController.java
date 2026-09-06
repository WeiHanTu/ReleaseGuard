package dev.weihantu.releaseguard.history.api;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseAction;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionSpec;
import dev.weihantu.releaseguard.history.config.HistoryProperties;
import dev.weihantu.releaseguard.history.projection.AuditProjectionService;
import dev.weihantu.releaseguard.history.projection.ProjectionFreshness;
import dev.weihantu.releaseguard.history.repository.AuditRepository;
import dev.weihantu.releaseguard.history.repository.EventRecord;
import dev.weihantu.releaseguard.history.repository.ReleaseProjectionRecord;
import dev.weihantu.releaseguard.history.service.ReleaseActionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/releases")
public class ReleaseHistoryController {

  private final AuditRepository auditRepository;
  private final AuditProjectionService projectionService;
  private final ReleaseActionService actionService;
  private final HistoryProperties properties;

  public ReleaseHistoryController(
      AuditRepository auditRepository,
      AuditProjectionService projectionService,
      ReleaseActionService actionService,
      HistoryProperties properties) {
    this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
    this.projectionService = Objects.requireNonNull(projectionService, "projectionService");
    this.actionService = Objects.requireNonNull(actionService, "actionService");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @GetMapping("/{namespace}/{name}")
  public ResponseEntity<ReleaseResponse> getRelease(
      @PathVariable("namespace") String namespace, @PathVariable("name") String name) {
    ReleaseProjectionRecord release =
        auditRepository
            .findLatestRelease(namespace, name)
            .orElseThrow(() -> new ReleaseNotFoundException(namespace, name));

    ProjectionFreshness freshness =
        projectionService.getFreshness(properties.getKafka().getTopic());
    ReleaseResponse.FreshnessView freshnessView =
        new ReleaseResponse.FreshnessView(
            freshness.lastKafkaOffsets(),
            freshness.projectedThrough(),
            freshness.quarantinedEventsCount(),
            freshness.stale());

    ReleaseResponse.ReleaseView releaseView =
        new ReleaseResponse.ReleaseView(
            release.releaseId(),
            release.releaseUid(),
            release.namespace(),
            release.name(),
            release.generation(),
            release.phase(),
            release.currentStep(),
            release.stableRevision(),
            release.canaryRevision(),
            release.lastTransitionSequence(),
            release.lastEventId(),
            release.lastEventType(),
            release.lastEventAt(),
            release.gateDecision(),
            release.gateEvidenceRef(),
            release.gateEvidenceDigest(),
            release.updatedAt());

    HttpHeaders headers = new HttpHeaders();
    headers.add("X-Projected-Through", freshness.projectedThrough().toString());
    long maxOffset =
        freshness.lastKafkaOffsets().values().stream().mapToLong(v -> v).max().orElse(0L);
    headers.add("X-Kafka-Offset", String.valueOf(maxOffset));

    return ResponseEntity.ok()
        .headers(headers)
        .body(new ReleaseResponse(releaseView, freshnessView));
  }

  @GetMapping("/{namespace}/{name}/events")
  public ResponseEntity<EventListResponse> getEvents(
      @PathVariable("namespace") String namespace,
      @PathVariable("name") String name,
      @RequestParam(value = "limit", defaultValue = "50") int limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    // Verify release exists
    if (auditRepository.findLatestRelease(namespace, name).isEmpty()) {
      throw new ReleaseNotFoundException(namespace, name);
    }

    int boundedLimit = Math.max(1, Math.min(100, limit));
    Long afterSequence = parseCursor(cursor);

    List<EventRecord> events =
        auditRepository.findEventsByRelease(namespace, name, afterSequence, boundedLimit);

    List<EventListResponse.EventView> items = new ArrayList<>(events.size());
    for (EventRecord e : events) {
      Map<String, Object> payload = projectionService.deserializePayload(e.payload());
      items.add(
          new EventListResponse.EventView(
              e.eventId(),
              e.releaseId(),
              e.releaseUid(),
              e.generation(),
              e.sequence(),
              e.type(),
              e.occurredAt(),
              e.namespace(),
              e.releaseName(),
              e.gitRevision(),
              e.imageDigest(),
              e.traceId(),
              payload));
    }

    String nextCursor = null;
    if (events.size() == boundedLimit) {
      nextCursor = String.valueOf(events.getLast().sequence());
    }

    ProjectionFreshness freshness =
        projectionService.getFreshness(properties.getKafka().getTopic());
    ReleaseResponse.FreshnessView freshnessView =
        new ReleaseResponse.FreshnessView(
            freshness.lastKafkaOffsets(),
            freshness.projectedThrough(),
            freshness.quarantinedEventsCount(),
            freshness.stale());

    return ResponseEntity.ok(new EventListResponse(items, nextCursor, freshnessView));
  }

  @GetMapping("/{namespace}/{name}/evidence")
  public ResponseEntity<EvidenceResponse> getEvidence(
      @PathVariable("namespace") String namespace, @PathVariable("name") String name) {
    if (auditRepository.findLatestRelease(namespace, name).isEmpty()) {
      throw new ReleaseNotFoundException(namespace, name);
    }

    List<EventRecord> events = auditRepository.findEventsByRelease(namespace, name, null, 100);

    List<EvidenceResponse.GateEvidenceItem> items = new ArrayList<>();
    for (EventRecord e : events) {
      Map<String, Object> payload = projectionService.deserializePayload(e.payload());
      if ("gate.evaluated".equals(e.type()) || payload.containsKey("evidenceDigest")) {
        String decision = (String) payload.get("decision");
        String evidenceRef = (String) payload.get("evidenceRef");
        String evidenceDigest = (String) payload.get("evidenceDigest");
        items.add(
            new EvidenceResponse.GateEvidenceItem(
                e.eventId(),
                e.sequence(),
                e.occurredAt(),
                decision,
                evidenceRef,
                evidenceDigest,
                payload));
      }
    }

    return ResponseEntity.ok(new EvidenceResponse(namespace, name, items));
  }

  @PostMapping("/{namespace}/{name}:{action}")
  public ResponseEntity<ActionResponse> submitAction(
      @PathVariable("namespace") String namespace,
      @PathVariable("name") String name,
      @PathVariable("action") String action,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "X-Actor-Identity", required = false) String actorHeader,
      @RequestHeader(value = "Authorization", required = false) String authHeader,
      @RequestBody(required = false) ActionRequest request) {

    String actor = actorHeader;
    if (actor == null || actor.isBlank()) {
      actor = authHeader;
    }
    if (actor == null || actor.isBlank()) {
      throw new MissingAuthenticationException();
    }

    ReleaseActionSpec.Type type = parseActionType(action);

    ReleaseProjectionRecord release =
        auditRepository
            .findLatestRelease(namespace, name)
            .orElseThrow(() -> new ReleaseNotFoundException(namespace, name));

    String targetUid =
        request != null && request.uid() != null && !request.uid().isBlank()
            ? request.uid()
            : release.releaseUid();
    long generation =
        request != null && request.generation() != null
            ? request.generation()
            : release.generation();
    String reason =
        request != null && request.reason() != null && !request.reason().isBlank()
            ? request.reason()
            : "Operational action " + type.name() + " submitted via REST API";

    ReleaseAction createdAction =
        actionService.submitAction(
            namespace, name, targetUid, generation, type, reason, idempotencyKey, actor);

    String phase =
        createdAction.getStatus() != null && createdAction.getStatus().phase() != null
            ? createdAction.getStatus().phase().name()
            : "Pending";

    ActionResponse response =
        new ActionResponse(
            createdAction.getMetadata() != null ? createdAction.getMetadata().getName() : name,
            namespace,
            name,
            type.name(),
            phase,
            idempotencyKey,
            actor,
            Instant.now().toString());

    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }

  private static ReleaseActionSpec.Type parseActionType(String action) {
    if (action == null) {
      throw new InvalidActionException("Action must not be null");
    }
    return switch (action.toLowerCase()) {
      case "pause" -> ReleaseActionSpec.Type.Pause;
      case "promote" -> ReleaseActionSpec.Type.Promote;
      case "abort" -> ReleaseActionSpec.Type.Abort;
      case "retryanalysis" -> ReleaseActionSpec.Type.RetryAnalysis;
      default -> throw new InvalidActionException("Unsupported release action: " + action);
    };
  }

  private static Long parseCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(cursor);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public static class MissingAuthenticationException extends RuntimeException {
    public MissingAuthenticationException() {
      super("Authenticated identity is required for mutating requests");
    }
  }
}
