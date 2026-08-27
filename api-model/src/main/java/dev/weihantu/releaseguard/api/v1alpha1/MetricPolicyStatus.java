package dev.weihantu.releaseguard.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Condition;
import java.util.List;

public record MetricPolicyStatus(
    Boolean accepted,
    String observedResourceVersion,
    String lastDryRunAt,
    String reason,
    List<Condition> conditions) {}
