package dev.weihantu.releaseguard.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Condition;
import java.util.List;

public record ReleaseActionStatus(
    Phase phase,
    String reason,
    String completedAt,
    String resultDigest,
    List<Condition> conditions) {

  public enum Phase {
    Pending,
    Accepted,
    Rejected,
    Completed,
    Failed
  }
}
