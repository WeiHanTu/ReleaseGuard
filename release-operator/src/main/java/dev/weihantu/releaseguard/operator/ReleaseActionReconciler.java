package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseAction;
import dev.weihantu.releaseguard.api.v1alpha1.ReleaseActionStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.List;
import java.util.Objects;

@ControllerConfiguration(
    name = "releaseguard-action-reconciler",
    fieldManager = ReleaseResourceRenderer.FIELD_MANAGER)
public final class ReleaseActionReconciler implements Reconciler<ReleaseAction> {
  private final ReleaseActionPlanner planner;
  private final ActionRuntime runtime;

  public ReleaseActionReconciler() {
    this(new ReleaseActionPlanner(), new KubernetesActionRuntime());
  }

  ReleaseActionReconciler(ReleaseActionPlanner planner, ActionRuntime runtime) {
    this.planner = Objects.requireNonNull(planner, "planner");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override
  public UpdateControl<ReleaseAction> reconcile(
      ReleaseAction action, Context<ReleaseAction> context) {
    if (action.getStatus() != null && isTerminal(action.getStatus().phase())) {
      return UpdateControl.noUpdate();
    }

    String namespace = action.getMetadata().getNamespace();
    ReleasePlan plan = null;
    if (action.getSpec() != null
        && action.getSpec().target() != null
        && action.getSpec().target().name() != null) {
      plan = runtime.getPlan(namespace, action.getSpec().target().name(), context);
    }
    List<ReleaseAction> existingActions = runtime.listActions(namespace, context);

    ReleaseActionPlanner.Result result = planner.plan(action, plan, existingActions);

    if (result.effect() != ReleaseActionPlanner.Effect.None) {
      applyEffect(result.effect(), action, context);
    }

    publishStatusIfChanged(action, result.status(), context);
    return UpdateControl.noUpdate();
  }

  private void applyEffect(
      ReleaseActionPlanner.Effect effect, ReleaseAction action, Context<ReleaseAction> context) {
    String namespace = action.getMetadata().getNamespace();
    String targetName = action.getSpec().target().name();
    switch (effect) {
      case PauseRollout -> runtime.pauseRollout(namespace, targetName, context);
      case PromoteRollout -> runtime.promoteRollout(namespace, targetName, context);
      case AbortRollout -> runtime.abortRollout(namespace, targetName, context);
      case AbortBootstrap -> runtime.abortBootstrap(namespace, targetName, context);
      case RetryAnalysis -> runtime.retryAnalysis(namespace, targetName, context);
      case None -> {}
    }
  }

  private void publishStatusIfChanged(
      ReleaseAction action, ReleaseActionStatus desired, Context<ReleaseAction> context) {
    if (Objects.equals(action.getStatus(), desired)) {
      return;
    }
    action.setStatus(desired);
    runtime.publishStatus(action, context);
  }

  private static boolean isTerminal(ReleaseActionStatus.Phase phase) {
    return phase == ReleaseActionStatus.Phase.Completed
        || phase == ReleaseActionStatus.Phase.Rejected
        || phase == ReleaseActionStatus.Phase.Failed;
  }
}
