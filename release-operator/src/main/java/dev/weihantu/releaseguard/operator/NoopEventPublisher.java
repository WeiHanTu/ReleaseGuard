package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;

public final class NoopEventPublisher implements EventPublisher {
  public static final NoopEventPublisher INSTANCE = new NoopEventPublisher();

  @Override
  public PublishResult publish(EventEnvelope envelope) {
    return new PublishResult("noop-topic", 0, envelope.sequence());
  }
}
