package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.util.List;
import java.util.Map;

interface ReconciliationRuntime {
  Map<String, MetricPolicySnapshot> acceptedPolicies(
      ReleasePlan plan, Context<ReleasePlan> context);

  void apply(List<HasMetadata> resources, Context<ReleasePlan> context);

  RolloutObservation observeRollout(ReleasePlan plan, Context<ReleasePlan> context);

  CleanupObservation observeCleanup(ReleasePlan plan, Context<ReleasePlan> context);

  void requestAbort(ReleasePlan plan, Context<ReleasePlan> context);

  void deleteManagedChildren(ReleasePlan plan, Context<ReleasePlan> context);

  void orphanStableResources(ReleasePlan plan, Context<ReleasePlan> context);

  void publishStatus(ReleasePlan plan, Context<ReleasePlan> context);
}
