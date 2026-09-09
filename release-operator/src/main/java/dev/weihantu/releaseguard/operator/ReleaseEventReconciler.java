package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEvent;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEventStatus.Phase;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@ControllerConfiguration(
    name = "releaseguard-event-publisher",
    fieldManager = ReleaseResourceRenderer.FIELD_MANAGER)
public final class ReleaseEventReconciler implements Reconciler<ReleaseEvent>, AutoCloseable {
  private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

  private final EventPublisher publisher;
  private final EventOutboxRuntime runtime;
  private final Clock clock;

  public ReleaseEventReconciler() {
    this(defaultPublisher(System.getenv()), new KubernetesEventOutboxRuntime(), Clock.systemUTC());
  }

  public ReleaseEventReconciler(EventPublisher publisher, EventOutboxRuntime runtime, Clock clock) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  private static EventPublisher defaultPublisher(Map<String, String> env) {
    EventPublisher kafkaPublisher = KafkaEventPublisher.fromEnvironment(env);
    return kafkaPublisher != null ? kafkaPublisher : NoopEventPublisher.INSTANCE;
  }

  @Override
  public void close() {
    publisher.close();
  }

  @Override
  public UpdateControl<ReleaseEvent> reconcile(ReleaseEvent event, Context<ReleaseEvent> context) {
    ReleaseEventStatus status = event.getStatus();
    if (status != null && status.phase() == Phase.Published) {
      return UpdateControl.noUpdate();
    }
    if (event.getSpec() == null) {
      return UpdateControl.noUpdate();
    }

    if (status != null && status.nextAttemptAt() != null && !status.nextAttemptAt().isBlank()) {
      Instant nextAttempt = Instant.parse(status.nextAttemptAt());
      if (clock.instant().isBefore(nextAttempt)) {
        Duration remaining = Duration.between(clock.instant(), nextAttempt);
        return UpdateControl.<ReleaseEvent>noUpdate()
            .rescheduleAfter(Math.max(1L, remaining.toMillis()));
      }
    }

    String namespace = event.getMetadata().getNamespace();
    String planName = resolvePlanName(event);
    ReleasePlan plan = planName != null ? runtime.getPlan(namespace, planName, context) : null;
    EventEnvelope envelope = EventEnvelope.from(event, plan);

    int currentAttempts = (status == null || status.attempts() == null) ? 0 : status.attempts();

    try {
      EventPublisher.PublishResult result = publisher.publish(envelope);
      int attempts = currentAttempts + 1;
      ReleaseEventStatus updatedStatus =
          new ReleaseEventStatus(
              Phase.Published,
              attempts,
              new ReleaseEventStatus.KafkaPosition(
                  result.topic(), result.partition(), result.offset()),
              null,
              clock.instant().toString(),
              List.of());
      event.setStatus(updatedStatus);
      runtime.publishStatus(event, context);
      return UpdateControl.noUpdate();
    } catch (Exception exception) {
      int attempts = currentAttempts + 1;
      Duration backoff = calculateBackoff(attempts);
      Instant nextAttempt = clock.instant().plus(backoff);
      ReleaseEventStatus updatedStatus =
          new ReleaseEventStatus(
              Phase.Pending,
              attempts,
              status == null ? null : status.kafka(),
              nextAttempt.toString(),
              null,
              List.of());
      event.setStatus(updatedStatus);
      runtime.publishStatus(event, context);
      return UpdateControl.<ReleaseEvent>noUpdate().rescheduleAfter(backoff.toMillis());
    }
  }

  Duration calculateBackoff(int attempts) {
    int shift = Math.min(Math.max(0, attempts - 1), 6);
    long baseMillis = Math.min(MAX_BACKOFF.toMillis(), INITIAL_BACKOFF.toMillis() * (1L << shift));
    // Add jitter up to 20%
    long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, baseMillis / 5));
    return Duration.ofMillis(Math.min(MAX_BACKOFF.toMillis(), baseMillis + jitter));
  }

  private static String resolvePlanName(ReleaseEvent event) {
    if (event.getMetadata() == null) {
      return null;
    }
    if (event.getMetadata().getLabels() != null
        && event.getMetadata().getLabels().containsKey(EventEnvelope.RELEASE_NAME_LABEL)) {
      return event.getMetadata().getLabels().get(EventEnvelope.RELEASE_NAME_LABEL);
    }
    if (event.getMetadata().getOwnerReferences() != null) {
      for (OwnerReference ref : event.getMetadata().getOwnerReferences()) {
        if ("ReleasePlan".equals(ref.getKind())) {
          return ref.getName();
        }
      }
    }
    return null;
  }
}
