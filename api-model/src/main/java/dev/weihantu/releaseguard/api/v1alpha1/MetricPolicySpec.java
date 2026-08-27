package dev.weihantu.releaseguard.api.v1alpha1;

import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;

public record MetricPolicySpec(
    @Required Direction direction,
    @Required @Size(min = 1) String unit,
    @Required @Size(min = 1) String valueQueryTemplate,
    @Required @Size(min = 1) String sampleCountQueryTemplate,
    @Required AllowedThresholds allowedThresholds,
    @Required @Min(1) Long minimumSamples,
    @Required @Pattern(KubernetesDuration.PATTERN) String window,
    @Required @Pattern(KubernetesDuration.PATTERN) String interval,
    @Required @Pattern(KubernetesDuration.PATTERN) String timeout) {

  public enum Direction {
    LowerIsBetter,
    HigherIsBetter
  }

  public record AllowedThresholds(@Required NumericRange canary, NumericRange stableDelta) {}

  public record NumericRange(@Required Double minimum, @Required Double maximum) {}
}
