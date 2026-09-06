package dev.weihantu.releaseguard.history.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;
import dev.weihantu.releaseguard.history.config.HistoryProperties;
import dev.weihantu.releaseguard.history.repository.AuditRepository;
import dev.weihantu.releaseguard.history.repository.EventRecord;
import dev.weihantu.releaseguard.history.repository.QuarantinedEventRecord;
import dev.weihantu.releaseguard.history.repository.ReleaseProjectionRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuditProjectionService implements the PostgreSQL read-model projection from lifecycle events
 * according to RG-AUD-001 through RG-AUD-006.
 */
@Service
public class AuditProjectionService {

  private static final Logger log = LoggerFactory.getLogger(AuditProjectionService.class);
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final AuditRepository auditRepository;
  private final HistoryProperties properties;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  public AuditProjectionService(
      AuditRepository auditRepository,
      HistoryProperties properties,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
  }

  /**
   * Projects an event into the audit database within a single transaction (RG-AUD-002). Handles
   * duplicate detection (RG-AUD-003) and state regression quarantining (RG-AUD-004).
   */
  @Transactional
  public ProjectionResult project(
      EventEnvelope envelope, String kafkaTopic, Integer kafkaPartition, Long kafkaOffset) {
    Objects.requireNonNull(envelope, "envelope");
    String eventId = envelope.eventId();

    // RG-AUD-003: Reprocessing an existing event MUST be a successful no-op
    if (auditRepository.eventExists(eventId)) {
      log.debug("Event {} already processed, skipping duplicate (RG-AUD-003)", eventId);
      if (kafkaTopic != null && kafkaPartition != null && kafkaOffset != null) {
        auditRepository.upsertCheckpoint(kafkaTopic, kafkaPartition, kafkaOffset, Instant.now());
      }
      return ProjectionResult.alreadyProcessed(eventId);
    }

    // RG-AUD-004: Check for state and sequence regression
    Optional<ReleaseProjectionRecord> currentOpt =
        auditRepository.findReleaseById(envelope.releaseId());
    if (currentOpt.isPresent()) {
      ReleaseProjectionRecord current = currentOpt.get();
      if (envelope.sequence() <= current.lastTransitionSequence()) {
        String reason =
            "SequenceRegression: event sequence "
                + envelope.sequence()
                + " <= last transition sequence "
                + current.lastTransitionSequence();
        log.warn(
            "Quarantining event {} for release {}: {} (RG-AUD-004)",
            eventId,
            envelope.releaseId(),
            reason);
        quarantineEvent(envelope, reason, current.lastTransitionSequence() + 1);
        if (kafkaTopic != null && kafkaPartition != null && kafkaOffset != null) {
          auditRepository.upsertCheckpoint(kafkaTopic, kafkaPartition, kafkaOffset, Instant.now());
        }
        return ProjectionResult.quarantined(eventId, reason);
      }
    }

    // Valid event: persist event and update projection in one transaction (RG-AUD-002)
    Instant occurredAt = parseInstant(envelope.occurredAt());
    String payloadJson = serializePayload(envelope.payload());
    EventRecord eventRecord =
        new EventRecord(
            envelope.eventId(),
            envelope.releaseId(),
            envelope.releaseUid(),
            envelope.generation(),
            envelope.sequence(),
            envelope.type(),
            occurredAt,
            envelope.namespace(),
            envelope.releaseName(),
            envelope.gitRevision(),
            envelope.imageDigest(),
            envelope.traceId(),
            payloadJson,
            kafkaTopic,
            kafkaPartition,
            kafkaOffset,
            Instant.now());
    auditRepository.insertEvent(eventRecord);

    ReleaseProjectionRecord updatedProjection =
        computeUpdatedProjection(eventRecord, envelope.payload(), currentOpt.orElse(null));
    auditRepository.upsertRelease(updatedProjection);

    if (kafkaTopic != null && kafkaPartition != null && kafkaOffset != null) {
      auditRepository.upsertCheckpoint(kafkaTopic, kafkaPartition, kafkaOffset, Instant.now());
    }

    log.debug(
        "Successfully projected event {} for release {} (seq={})",
        eventId,
        envelope.releaseId(),
        envelope.sequence());
    return ProjectionResult.applied(eventId);
  }

  /**
   * Rebuilds the projection from all stored events (RG-AUD-005). Deleting and rebuilding the
   * projection yields equivalent release views.
   */
  @Transactional
  public void rebuildProjection() {
    log.info("Rebuilding projection from retained audit events (RG-AUD-005)...");
    auditRepository.deleteReleaseProjections();
    List<EventRecord> allEvents = auditRepository.findAllEventsOrdered();
    for (EventRecord event : allEvents) {
      Optional<ReleaseProjectionRecord> currentOpt =
          auditRepository.findReleaseById(event.releaseId());
      Map<String, Object> payload = deserializePayload(event.payload());
      ReleaseProjectionRecord updated =
          computeUpdatedProjection(event, payload, currentOpt.orElse(null));
      auditRepository.upsertRelease(updated);
    }
    log.info("Rebuild complete. Processed {} events.", allEvents.size());
  }

  /** Reports projection freshness including offsets and projected-through time (RG-AUD-006). */
  @Transactional(readOnly = true)
  public ProjectionFreshness getFreshness(String kafkaTopic) {
    Map<Integer, Long> checkpoints = auditRepository.getCheckpoints(kafkaTopic);
    Instant projectedThrough =
        auditRepository.getLatestProjectedTime().orElse(Instant.ofEpochMilli(0));
    long quarantinedCount = auditRepository.countAllQuarantinedEvents();
    boolean stale = isStale(projectedThrough);
    return new ProjectionFreshness(checkpoints, projectedThrough, quarantinedCount, stale);
  }

  private boolean isStale(Instant projectedThrough) {
    if (projectedThrough.equals(Instant.ofEpochMilli(0))) {
      return false;
    }
    Duration lag = Duration.between(projectedThrough, Instant.now());
    return lag.getSeconds() > properties.getStaleThresholdSeconds();
  }

  private void quarantineEvent(EventEnvelope envelope, String reason, long expectedSequence) {
    String rawEnvelope;
    try {
      rawEnvelope = objectMapper.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      rawEnvelope = "{\"error\": \"serialization_failed\"}";
    }
    QuarantinedEventRecord quarantined =
        new QuarantinedEventRecord(
            envelope.eventId(),
            envelope.releaseId(),
            envelope.sequence(),
            expectedSequence,
            reason,
            rawEnvelope,
            Instant.now());
    auditRepository.insertQuarantinedEvent(quarantined);

    Counter.builder("releaseguard_projection_quarantined_total")
        .tag("reason", reason.startsWith("SequenceRegression") ? "SequenceRegression" : "Other")
        .register(meterRegistry)
        .increment();
  }

  private ReleaseProjectionRecord computeUpdatedProjection(
      EventRecord event, Map<String, Object> payload, ReleaseProjectionRecord existing) {
    String phase = determinePhase(event.type(), existing != null ? existing.phase() : null);
    Integer step = extractStep(payload, existing != null ? existing.currentStep() : null);
    String stableRevision =
        extractString(
            payload,
            "stableRevision",
            existing != null ? existing.stableRevision() : event.gitRevision());
    String canaryRevision =
        extractString(
            payload,
            "canaryRevision",
            existing != null ? existing.canaryRevision() : event.gitRevision());
    String gateDecision =
        extractString(payload, "decision", existing != null ? existing.gateDecision() : null);
    String evidenceRef =
        extractString(payload, "evidenceRef", existing != null ? existing.gateEvidenceRef() : null);
    String evidenceDigest =
        extractString(
            payload, "evidenceDigest", existing != null ? existing.gateEvidenceDigest() : null);

    return new ReleaseProjectionRecord(
        event.releaseId(),
        event.releaseUid(),
        event.namespace(),
        event.releaseName(),
        event.generation(),
        phase,
        step,
        stableRevision,
        canaryRevision,
        event.sequence(),
        event.eventId(),
        event.type(),
        event.occurredAt(),
        gateDecision,
        evidenceRef,
        evidenceDigest,
        Instant.now());
  }

  private static String determinePhase(String eventType, String existingPhase) {
    return switch (eventType) {
      case "release.bootstrapped" -> "Bootstrapping";
      case "release.progressing" -> "Progressing";
      case "rollout.promoted" -> "Progressing";
      case "rollback.started" -> "RollingBack";
      case "rollback.completed" -> "RolledBack";
      case "release.succeeded" -> "Succeeded";
      case "release.failed" -> "Failed";
      default -> existingPhase != null ? existingPhase : "Progressing";
    };
  }

  private static Integer extractStep(Map<String, Object> payload, Integer defaultStep) {
    if (payload == null) {
      return defaultStep;
    }
    Object stepObj = payload.get("step");
    if (stepObj == null) {
      stepObj = payload.get("currentStep");
    }
    if (stepObj instanceof Number num) {
      return num.intValue();
    }
    if (stepObj instanceof String str) {
      try {
        return Integer.parseInt(str);
      } catch (NumberFormatException ignored) {
      }
    }
    return defaultStep;
  }

  private static String extractString(
      Map<String, Object> payload, String key, String defaultValue) {
    if (payload == null) {
      return defaultValue;
    }
    Object val = payload.get(key);
    return val != null ? val.toString() : defaultValue;
  }

  private Instant parseInstant(String text) {
    if (text == null) {
      return Instant.now();
    }
    try {
      return Instant.parse(text);
    } catch (Exception e) {
      return Instant.now();
    }
  }

  private String serializePayload(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize payload: {}", e.getMessage());
      return "{}";
    }
  }

  public Map<String, Object> deserializePayload(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException e) {
      log.error("Failed to deserialize payload: {}", e.getMessage());
      return Map.of();
    }
  }
}
