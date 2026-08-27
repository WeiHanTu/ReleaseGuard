package dev.weihantu.releaseguard.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Condition;
import java.util.List;

public record ReleaseEventStatus(
    Phase phase,
    Integer attempts,
    KafkaPosition kafka,
    String nextAttemptAt,
    String publishedAt,
    List<Condition> conditions) {

  public enum Phase {
    Pending,
    Publishing,
    Published
  }

  public record KafkaPosition(String topic, Integer partition, Long offset) {}
}
