package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class ReleasePlanReconciler implements Reconciler<ReleasePlan> {
  private final ReleasePlanPlanner planner;
  private final ReconciliationRuntime runtime;

  public ReleasePlanReconciler() {
    this(new ReleasePlanPlanner(), new KubernetesReconciliationRuntime());
  }

  ReleasePlanReconciler(ReleasePlanPlanner planner, ReconciliationRuntime runtime) {
    this.planner = Objects.requireNonNull(planner, "planner");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override
  public UpdateControl<ReleasePlan> reconcile(ReleasePlan resource, Context<ReleasePlan> context) {
    ReconciliationPlan plan = planner.plan(resource, runtime.acceptedPolicies(resource, context));
    if (plan.hasDesiredResources()) {
      runtime.apply(plan.desiredResources(), context);
    }
    resource.setStatus(plan.status());

    UpdateControl<ReleasePlan> control = UpdateControl.patchStatus(resource);
    if (plan.requeueAfter() != null) {
      control.rescheduleAfter(plan.requeueAfter().toMillis(), TimeUnit.MILLISECONDS);
    }
    return control;
  }
}
