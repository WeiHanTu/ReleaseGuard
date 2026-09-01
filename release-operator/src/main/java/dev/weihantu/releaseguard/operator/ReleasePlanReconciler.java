package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec.DeletionPolicy;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@ControllerConfiguration(
    name = ReleaseResourceRenderer.FIELD_MANAGER,
    finalizerName = "delivery.weihantu.dev/releaseguard-cleanup",
    fieldManager = ReleaseResourceRenderer.FIELD_MANAGER,
    retry = ReleaseGuardRetry.class)
public final class ReleasePlanReconciler implements Reconciler<ReleasePlan>, Cleaner<ReleasePlan> {
  private final ReleasePlanPlanner planner;
  private final ReconciliationRuntime runtime;
  private final ReleaseCleanupPlanner cleanupPlanner;
  private final ReleaseCleanupStatusFactory cleanupStatusFactory;
  private final CleanupPolicy cleanupPolicy;
  private final Clock clock;

  public ReleasePlanReconciler() {
    this(
        new ReleasePlanPlanner(),
        new KubernetesReconciliationRuntime(),
        new ReleaseCleanupPlanner(),
        CleanupPolicy.fromEnvironment(),
        Clock.systemUTC());
  }

  ReleasePlanReconciler(ReleasePlanPlanner planner, ReconciliationRuntime runtime) {
    this(planner, runtime, new ReleaseCleanupPlanner(), CleanupPolicy.DEFAULT, Clock.systemUTC());
  }

  ReleasePlanReconciler(
      ReleasePlanPlanner planner,
      ReconciliationRuntime runtime,
      ReleaseCleanupPlanner cleanupPlanner,
      CleanupPolicy cleanupPolicy,
      Clock clock) {
    this.planner = Objects.requireNonNull(planner, "planner");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.cleanupPlanner = Objects.requireNonNull(cleanupPlanner, "cleanupPlanner");
    this.cleanupPolicy = Objects.requireNonNull(cleanupPolicy, "cleanupPolicy");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.cleanupStatusFactory = new ReleaseCleanupStatusFactory(clock);
  }

  @Override
  public List<EventSource<?, ReleasePlan>> prepareEventSources(
      EventSourceContext<ReleasePlan> context) {
    return ReleasePlanEventSources.create(context);
  }

  @Override
  public UpdateControl<ReleasePlan> reconcile(ReleasePlan resource, Context<ReleasePlan> context) {
    ReconciliationPlan plan =
        planner.plan(
            resource,
            runtime.acceptedPolicies(resource, context),
            runtime.observeRollout(resource, context));
    if (plan.stopCandidateTraffic()) {
      // Performed before the status write so a crash retries the traffic stop instead of
      // recording a failed release that is still serving.
      runtime.stopCandidateTraffic(resource, context);
    }
    if (plan.hasDesiredResources()) {
      runtime.apply(plan.desiredResources(), context);
    }
    publishStatusIfChanged(resource, plan.status(), context);

    UpdateControl<ReleasePlan> control = UpdateControl.noUpdate();
    if (plan.requeueAfter() != null) {
      control.rescheduleAfter(plan.requeueAfter().toMillis(), TimeUnit.MILLISECONDS);
    }
    return control;
  }

  @Override
  public DeleteControl cleanup(ReleasePlan resource, Context<ReleasePlan> context) {
    if (cleanupDeadlineExceeded(resource)) {
      ReleasePlanStatus deadlineStatus =
          cleanupStatusFactory.deleting(
              resource,
              "CleanupDeadlineExceeded",
              "Cleanup exceeded its deadline; deleting generated resources as a fail-safe",
              true);
      runtime.deleteManagedChildren(resource, context);
      publishStatusIfChanged(resource, deadlineStatus, context);
      return DeleteControl.defaultDelete();
    }

    CleanupObservation observation = runtime.observeCleanup(resource, context);
    CleanupAction action = cleanupPlanner.decide(deletionPolicy(resource), observation);
    if (action == CleanupAction.Complete) {
      return DeleteControl.defaultDelete();
    }

    CleanupStatusMessage progress =
        switch (action) {
          case RequestAbort -> {
            runtime.requestAbort(resource, context);
            yield new CleanupStatusMessage(
                "AbortRequested", "Waiting for Argo Rollouts to acknowledge abort");
          }
          case WaitForStableTraffic ->
              new CleanupStatusMessage(
                  "WaitingForStableTraffic",
                  "Waiting for stable ready endpoints and zero canary ingress weight");
          case DeleteChildren -> {
            runtime.deleteManagedChildren(resource, context);
            yield new CleanupStatusMessage(
                "DeletingChildren", "Deleting ReleaseGuard-managed rollout resources");
          }
          case OrphanStable -> {
            runtime.orphanStableResources(resource, context);
            yield new CleanupStatusMessage(
                "OrphaningStableResources",
                "Detaching stable resources after verified traffic restoration");
          }
          case Complete -> throw new IllegalStateException("complete cleanup was handled earlier");
        };
    publishStatusIfChanged(
        resource,
        cleanupStatusFactory.deleting(resource, progress.reason(), progress.message(), false),
        context);
    return DeleteControl.noFinalizerRemoval()
        .rescheduleAfter(cleanupPolicy.requeueAfter().toMillis());
  }

  private boolean cleanupDeadlineExceeded(ReleasePlan resource) {
    String deletionTimestamp = resource.getMetadata().getDeletionTimestamp();
    return deletionTimestamp != null
        && !deletionTimestamp.isBlank()
        && Instant.parse(deletionTimestamp)
            .plus(cleanupPolicy.deadline())
            .isBefore(clock.instant());
  }

  private void publishStatusIfChanged(
      ReleasePlan resource, ReleasePlanStatus desired, Context<ReleasePlan> context) {
    if (Objects.equals(resource.getStatus(), desired)) {
      return;
    }
    resource.setStatus(desired);
    runtime.publishStatus(resource, context);
  }

  private static DeletionPolicy deletionPolicy(ReleasePlan resource) {
    return resource.getSpec() == null || resource.getSpec().deletionPolicy() == null
        ? DeletionPolicy.Delete
        : resource.getSpec().deletionPolicy();
  }

  private record CleanupStatusMessage(String reason, String message) {}
}
