package dev.weihantu.releaseguard.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEvent;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import dev.weihantu.releaseguard.history.projection.AuditProjectionService;
import dev.weihantu.releaseguard.history.projection.ProjectionFreshness;
import dev.weihantu.releaseguard.history.projection.ProjectionResult;
import dev.weihantu.releaseguard.history.repository.AuditRepository;
import dev.weihantu.releaseguard.history.repository.EventRecord;
import dev.weihantu.releaseguard.history.repository.ReleaseProjectionRecord;
import dev.weihantu.releaseguard.operator.ReleaseEventRenderer;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AuditProjectionServiceTest {

  @Autowired private AuditProjectionService projectionService;
  @Autowired private AuditRepository auditRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  @BeforeEach
  void cleanDatabase() {
    jdbcTemplate.update("DELETE FROM kafka_poison_records");
    jdbcTemplate.update("DELETE FROM quarantined_events");
    jdbcTemplate.update("DELETE FROM events");
    jdbcTemplate.update("DELETE FROM releases");
    jdbcTemplate.update("DELETE FROM projection_checkpoints");
  }

  @Test
  @DisplayName("RG-AUD-001 & RG-AUD-002: Persist event and project release state atomically")
  void testPersistEventAndProjectReleaseAtomically() {
    EventEnvelope event =
        new EventEnvelope(
            1,
            "evt-001",
            "release.bootstrapped",
            "uid-100:1",
            "uid-100",
            1L,
            1L,
            "demo",
            "checkout",
            "git-rev-1",
            "sha256:image-1",
            "2026-09-06T00:00:00Z",
            "trace-1",
            Map.of("step", 0, "canaryWeight", 5));

    ProjectionResult result = projectionService.project(event, "deployment-events.v1", 0, 101L);

    assertEquals(ProjectionResult.Status.APPLIED, result.status());
    assertTrue(auditRepository.eventExists("evt-001"));

    Optional<ReleaseProjectionRecord> releaseOpt = auditRepository.findReleaseById("uid-100:1");
    assertTrue(releaseOpt.isPresent());
    ReleaseProjectionRecord release = releaseOpt.get();
    assertEquals("uid-100", release.releaseUid());
    assertEquals("demo", release.namespace());
    assertEquals("checkout", release.name());
    assertEquals("Succeeded", release.phase());
    assertEquals(1L, release.lastTransitionSequence());
    assertEquals("evt-001", release.lastEventId());
    assertEquals("release.bootstrapped", release.lastEventType());
    assertEquals(0, release.currentStep());
  }

  @Test
  @DisplayName("Operator event payload projects without a producer-consumer schema translation")
  void testProducerToProjectorContract() {
    ReleasePlan plan = new ReleasePlan();
    plan.setMetadata(
        new ObjectMetaBuilder()
            .withName("checkout")
            .withNamespace("demo")
            .withUid("uid-contract")
            .withGeneration(7L)
            .build());
    ReleasePlanStatus.Transition transition =
        new ReleasePlanStatus.Transition(
            3L, "evt-contract-3", "release.bootstrapped", "2026-09-08T20:00:00Z");
    ReleasePlanStatus status =
        new ReleasePlanStatus(
            7L,
            "uid-contract:7",
            ReleasePlanStatus.Phase.Succeeded,
            2,
            "checkout-candidate-hash",
            null,
            null,
            new ReleasePlanStatus.GateDecision(
                "Successful", "AnalysisRun/checkout-bootstrap-g7", "2026-09-08T19:59:59Z"),
            transition,
            0,
            List.of());

    ReleaseEvent produced = ReleaseEventRenderer.render(plan, transition, status);
    EventEnvelope envelope = EventEnvelope.from(produced, plan);
    ProjectionResult result = projectionService.project(envelope, "deployment-events.v1", 1, 33L);

    assertEquals(ProjectionResult.Status.APPLIED, result.status());
    ReleaseProjectionRecord projected =
        auditRepository.findReleaseById("uid-contract:7").orElseThrow();
    assertEquals("Succeeded", projected.phase());
    assertEquals(2, projected.currentStep());
    assertEquals("checkout-candidate-hash", projected.stableRevision());
    assertEquals("Successful", projected.gateDecision());
    assertEquals("AnalysisRun/checkout-bootstrap-g7", projected.gateEvidenceRef());
  }

  @Test
  @DisplayName("RG-AUD-003: Duplicate event processing is a successful no-op")
  void testDuplicateEventProcessingIsNoOp() {
    EventEnvelope event =
        new EventEnvelope(
            1,
            "evt-dup-1",
            "release.progressing",
            "uid-100:1",
            "uid-100",
            1L,
            1L,
            "demo",
            "checkout",
            "git-rev-1",
            null,
            "2026-09-06T00:00:00Z",
            null,
            Map.of("step", 1));

    ProjectionResult firstResult = projectionService.project(event, "deployment-events.v1", 0, 10L);
    assertEquals(ProjectionResult.Status.APPLIED, firstResult.status());

    // Duplicate delivery with same eventId
    ProjectionResult secondResult =
        projectionService.project(event, "deployment-events.v1", 0, 11L);
    assertEquals(ProjectionResult.Status.ALREADY_PROCESSED, secondResult.status());

    // Only one row in events table
    List<EventRecord> events = auditRepository.findEventsByRelease("demo", "checkout", null, 10);
    assertEquals(1, events.size());
  }

  @Test
  @DisplayName("RG-AUD-004: State and sequence regression is quarantined and counted")
  void testSequenceRegressionQuarantinedAndCounted() {
    // Initial progressing event sequence 5
    EventEnvelope event1 =
        new EventEnvelope(
            1,
            "evt-seq-5",
            "release.progressing",
            "uid-200:1",
            "uid-200",
            1L,
            5L,
            "demo",
            "payment",
            "rev-1",
            null,
            "2026-09-06T00:00:00Z",
            null,
            Map.of("step", 2));
    projectionService.project(event1, "deployment-events.v1", 0, 50L);

    // Regressive event: new ID but sequence 3 <= current sequence 5
    EventEnvelope regressiveEvent =
        new EventEnvelope(
            1,
            "evt-seq-3-bad",
            "release.progressing",
            "uid-200:1",
            "uid-200",
            1L,
            3L,
            "demo",
            "payment",
            "rev-1",
            null,
            "2026-09-06T00:00:01Z",
            null,
            Map.of("step", 1));

    ProjectionResult result =
        projectionService.project(regressiveEvent, "deployment-events.v1", 0, 51L);

    assertEquals(ProjectionResult.Status.QUARANTINED, result.status());
    assertTrue(result.reason().contains("SequenceRegression"));

    // Event must NOT be in events table
    assertFalse(auditRepository.eventExists("evt-seq-3-bad"));

    // Release projection must still be at sequence 5
    ReleaseProjectionRecord release = auditRepository.findReleaseById("uid-200:1").orElseThrow();
    assertEquals(5L, release.lastTransitionSequence());

    // Quarantined count must be 1
    assertEquals(1, auditRepository.countQuarantinedEvents("uid-200:1"));
  }

  @Test
  @DisplayName("Malformed Kafka records are quarantined idempotently at their source offset")
  void testMalformedKafkaRecordQuarantineIsIdempotent() {
    boolean first =
        projectionService.quarantineMalformedKafkaRecord(
            "deployment-events.v1",
            2,
            81L,
            "uid-malformed",
            "{not-json",
            new IllegalArgumentException("invalid envelope"));
    boolean duplicate =
        projectionService.quarantineMalformedKafkaRecord(
            "deployment-events.v1",
            2,
            81L,
            "uid-malformed",
            "{not-json",
            new IllegalArgumentException("invalid envelope"));

    assertTrue(first);
    assertFalse(duplicate);
    assertEquals(
        1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kafka_poison_records", Long.class));
    assertEquals(81L, auditRepository.getCheckpoints("deployment-events.v1").get(2));
    assertEquals(
        1L, projectionService.getFreshness("deployment-events.v1").quarantinedEventsCount());
  }

  @Test
  @DisplayName("RG-AUD-005: Rebuilding projection from event stream yields equivalent view")
  void testRebuildProjectionYieldsEquivalentView() {
    String releaseId = "uid-300:1";
    EventEnvelope e1 =
        new EventEnvelope(
            1,
            "e-1",
            "release.bootstrapped",
            releaseId,
            "uid-300",
            1L,
            1L,
            "prod",
            "cart",
            "rev-a",
            null,
            "2026-09-06T00:00:00Z",
            null,
            Map.of("step", 0));
    EventEnvelope e2 =
        new EventEnvelope(
            1,
            "e-2",
            "release.progressing",
            releaseId,
            "uid-300",
            1L,
            2L,
            "prod",
            "cart",
            "rev-a",
            null,
            "2026-09-06T00:01:00Z",
            null,
            Map.of("step", 1));
    EventEnvelope e3 =
        new EventEnvelope(
            1,
            "e-3",
            "gate.evaluated",
            releaseId,
            "uid-300",
            1L,
            3L,
            "prod",
            "cart",
            "rev-a",
            null,
            "2026-09-06T00:02:00Z",
            null,
            Map.of("decision", "Pass", "evidenceDigest", "sha256:evidence123"));
    EventEnvelope e4 =
        new EventEnvelope(
            1,
            "e-4",
            "rollout.promoted",
            releaseId,
            "uid-300",
            1L,
            4L,
            "prod",
            "cart",
            "rev-a",
            null,
            "2026-09-06T00:03:00Z",
            null,
            Map.of("step", 2));
    EventEnvelope e5 =
        new EventEnvelope(
            1,
            "e-5",
            "release.succeeded",
            releaseId,
            "uid-300",
            1L,
            5L,
            "prod",
            "cart",
            "rev-a",
            null,
            "2026-09-06T00:04:00Z",
            null,
            Map.of());

    projectionService.project(e1, "deployment-events.v1", 0, 1L);
    projectionService.project(e2, "deployment-events.v1", 0, 2L);
    projectionService.project(e3, "deployment-events.v1", 0, 3L);
    projectionService.project(e4, "deployment-events.v1", 0, 4L);
    projectionService.project(e5, "deployment-events.v1", 0, 5L);

    ReleaseProjectionRecord beforeRebuild =
        auditRepository.findReleaseById(releaseId).orElseThrow();
    assertEquals("Succeeded", beforeRebuild.phase());
    assertEquals(5L, beforeRebuild.lastTransitionSequence());
    assertEquals("Pass", beforeRebuild.gateDecision());
    assertEquals("sha256:evidence123", beforeRebuild.gateEvidenceDigest());

    // Rebuild projection from stored events (RG-AUD-005)
    projectionService.rebuildProjection();

    ReleaseProjectionRecord afterRebuild = auditRepository.findReleaseById(releaseId).orElseThrow();

    assertEquals(beforeRebuild.releaseId(), afterRebuild.releaseId());
    assertEquals(beforeRebuild.releaseUid(), afterRebuild.releaseUid());
    assertEquals(beforeRebuild.namespace(), afterRebuild.namespace());
    assertEquals(beforeRebuild.name(), afterRebuild.name());
    assertEquals(beforeRebuild.generation(), afterRebuild.generation());
    assertEquals(beforeRebuild.phase(), afterRebuild.phase());
    assertEquals(beforeRebuild.lastTransitionSequence(), afterRebuild.lastTransitionSequence());
    assertEquals(beforeRebuild.lastEventId(), afterRebuild.lastEventId());
    assertEquals(beforeRebuild.lastEventType(), afterRebuild.lastEventType());
    assertEquals(beforeRebuild.gateDecision(), afterRebuild.gateDecision());
    assertEquals(beforeRebuild.gateEvidenceDigest(), afterRebuild.gateEvidenceDigest());
  }

  @Test
  @DisplayName("RG-AUD-006: Freshness metadata reports Kafka offsets and projected-through time")
  void testFreshnessReporting() {
    EventEnvelope event =
        new EventEnvelope(
            1,
            "evt-fresh-1",
            "release.progressing",
            "uid-fresh:1",
            "uid-fresh",
            1L,
            1L,
            "demo",
            "fresh",
            "rev-1",
            null,
            "2026-09-06T00:00:00Z",
            null,
            Map.of("step", 0));
    projectionService.project(event, "deployment-events.v1", 2, 42L);

    ProjectionFreshness freshness = projectionService.getFreshness("deployment-events.v1");
    assertNotNull(freshness);
    assertEquals(42L, freshness.lastKafkaOffsets().get(2));
    assertEquals(Instant.parse("2026-09-06T00:00:00Z"), freshness.projectedThrough());
    assertEquals(0L, freshness.quarantinedEventsCount());
  }
}
