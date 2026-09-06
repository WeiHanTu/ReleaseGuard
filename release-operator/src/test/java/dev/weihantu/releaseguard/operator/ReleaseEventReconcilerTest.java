package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEvent;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventSpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventStatus.Phase;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReleaseEventReconcilerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
  private static final String NAMESPACE = "demo";
  private static final String PLAN_NAME = "checkout";
  private static final String RELEASE_UID = "plan-uid-1234";

  @Test
  void successfulPublicationUpdatesStatusToPublished() {
    AtomicReference<EventEnvelope> publishedEnvelope = new AtomicReference<>();
    EventPublisher publisher =
        envelope -> {
          publishedEnvelope.set(envelope);
          return new EventPublisher.PublishResult("deployment-events.v1", 2, 42L);
        };

    RecordingRuntime runtime = new RecordingRuntime();
    ReleaseEventReconciler reconciler = new ReleaseEventReconciler(publisher, runtime, CLOCK);

    ReleaseEvent event = event(1L, "release.bootstrapped");

    UpdateControl<ReleaseEvent> control = reconciler.reconcile(event, null);

    assertTrue(control.isNoUpdate());
    assertNotNull(publishedEnvelope.get());
    assertEquals("sha256:event-1", publishedEnvelope.get().eventId());
    assertEquals(RELEASE_UID, publishedEnvelope.get().releaseUid());

    ReleaseEventStatus status = runtime.lastPublishedStatus;
    assertNotNull(status);
    assertEquals(Phase.Published, status.phase());
    assertEquals(1, status.attempts());
    assertEquals("2026-08-29T12:00:00Z", status.publishedAt());
    assertNull(status.nextAttemptAt());
    assertNotNull(status.kafka());
    assertEquals("deployment-events.v1", status.kafka().topic());
    assertEquals(2, status.kafka().partition());
    assertEquals(42L, status.kafka().offset());
  }

  @Test
  void alreadyPublishedEventIsNoop() {
    AtomicReference<EventEnvelope> publishedEnvelope = new AtomicReference<>();
    EventPublisher publisher =
        envelope -> {
          publishedEnvelope.set(envelope);
          return new EventPublisher.PublishResult("deployment-events.v1", 0, 1L);
        };

    RecordingRuntime runtime = new RecordingRuntime();
    ReleaseEventReconciler reconciler = new ReleaseEventReconciler(publisher, runtime, CLOCK);

    ReleaseEvent event = event(1L, "release.bootstrapped");
    event.setStatus(
        new ReleaseEventStatus(
            Phase.Published,
            1,
            new ReleaseEventStatus.KafkaPosition("deployment-events.v1", 0, 1L),
            null,
            "2026-08-29T11:59:00Z",
            List.of()));

    UpdateControl<ReleaseEvent> control = reconciler.reconcile(event, null);

    assertTrue(control.isNoUpdate());
    assertNull(publishedEnvelope.get());
    assertNull(runtime.lastPublishedStatus);
  }

  @Test
  void transportFailureRecordsAttemptAndReschedulesWithBackoff() {
    EventPublisher publisher =
        envelope -> {
          throw new EventPublishException("Kafka cluster unreachable");
        };

    RecordingRuntime runtime = new RecordingRuntime();
    ReleaseEventReconciler reconciler = new ReleaseEventReconciler(publisher, runtime, CLOCK);

    ReleaseEvent event = event(1L, "release.bootstrapped");

    UpdateControl<ReleaseEvent> control = reconciler.reconcile(event, null);

    assertTrue(control.getScheduleDelay().isPresent());
    assertTrue(control.getScheduleDelay().get() >= 1000L);

    ReleaseEventStatus status = runtime.lastPublishedStatus;
    assertNotNull(status);
    assertEquals(Phase.Pending, status.phase());
    assertEquals(1, status.attempts());
    assertNull(status.publishedAt());
    assertNotNull(status.nextAttemptAt());
    assertTrue(Instant.parse(status.nextAttemptAt()).isAfter(CLOCK.instant()));
  }

  @Test
  void waitingForNextAttemptReschedulesWithoutPublishing() {
    AtomicReference<EventEnvelope> publishedEnvelope = new AtomicReference<>();
    EventPublisher publisher =
        envelope -> {
          publishedEnvelope.set(envelope);
          return new EventPublisher.PublishResult("deployment-events.v1", 0, 1L);
        };

    RecordingRuntime runtime = new RecordingRuntime();
    ReleaseEventReconciler reconciler = new ReleaseEventReconciler(publisher, runtime, CLOCK);

    ReleaseEvent event = event(1L, "release.bootstrapped");
    Instant futureAttempt = CLOCK.instant().plus(Duration.ofSeconds(15));
    event.setStatus(
        new ReleaseEventStatus(Phase.Pending, 1, null, futureAttempt.toString(), null, List.of()));

    UpdateControl<ReleaseEvent> control = reconciler.reconcile(event, null);

    assertTrue(control.getScheduleDelay().isPresent());
    assertEquals(15000L, control.getScheduleDelay().get());
    assertNull(publishedEnvelope.get());
    assertNull(runtime.lastPublishedStatus);
  }

  @Test
  void backoffIncreasesExponentiallyWithAttempts() {
    ReleaseEventReconciler reconciler =
        new ReleaseEventReconciler(NoopEventPublisher.INSTANCE, new RecordingRuntime(), CLOCK);

    Duration backoff1 = reconciler.calculateBackoff(1);
    Duration backoff2 = reconciler.calculateBackoff(2);
    Duration backoff4 = reconciler.calculateBackoff(4);

    assertTrue(backoff1.toMillis() >= 1000L);
    assertTrue(backoff2.toMillis() >= 2000L);
    assertTrue(backoff4.toMillis() >= 8000L);
    assertTrue(reconciler.calculateBackoff(10).toMillis() <= 60000L);
  }

  private static ReleaseEvent event(long sequence, String type) {
    ReleaseEvent event = new ReleaseEvent();
    event.setMetadata(
        new ObjectMetaBuilder()
            .withNamespace(NAMESPACE)
            .withName(PLAN_NAME + "-1-" + String.format("%06d", sequence))
            .withLabels(
                Map.of(
                    ReleaseResourceRenderer.PLAN_UID_LABEL,
                    RELEASE_UID,
                    "delivery.weihantu.dev/release-name",
                    PLAN_NAME,
                    "delivery.weihantu.dev/release-generation",
                    "1"))
            .build());
    event.setSpec(
        new ReleaseEventSpec(
            "sha256:event-" + sequence,
            RELEASE_UID + ":1",
            sequence,
            type,
            "2026-08-29T12:00:00Z",
            Map.of("phase", "Bootstrapping")));
    return event;
  }

  private static final class RecordingRuntime implements EventOutboxRuntime {
    ReleaseEventStatus lastPublishedStatus;

    @Override
    public ReleasePlan getPlan(String namespace, String name, Context<ReleaseEvent> context) {
      return null;
    }

    @Override
    public void publishStatus(ReleaseEvent event, Context<ReleaseEvent> context) {
      this.lastPublishedStatus = event.getStatus();
    }
  }
}
