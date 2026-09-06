package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;

public interface EventPublisher extends AutoCloseable {
  PublishResult publish(EventEnvelope envelope);

  @Override
  default void close() {}

  record PublishResult(String topic, int partition, long offset) {}
}
