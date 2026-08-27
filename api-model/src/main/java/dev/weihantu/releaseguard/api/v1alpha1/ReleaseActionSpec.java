package dev.weihantu.releaseguard.api.v1alpha1;

import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;

public record ReleaseActionSpec(
    @Required Target target,
    @Required Type type,
    @Required @Size(min = 1, max = 1024) String reason,
    @Required @Size(min = 1, max = 128) String idempotencyKey,
    @Required @Size(min = 1, max = 256) String actor,
    @Required String requestedAt) {

  public record Target(
      @Required @Size(min = 1) String name,
      @Required @Size(min = 1) String uid,
      @Required @Min(1) Long generation) {}

  public enum Type {
    Pause,
    Promote,
    Abort,
    RetryAnalysis
  }
}
