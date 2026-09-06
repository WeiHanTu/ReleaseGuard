package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseAction;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.util.List;

final class KubernetesActionRuntime implements ActionRuntime {
  @Override
  public ReleasePlan getPlan(String namespace, String name, Context<ReleaseAction> context) {
    return context
        .getClient()
        .resources(ReleasePlan.class)
        .inNamespace(namespace)
        .withName(name)
        .get();
  }

  @Override
  public List<ReleaseAction> listActions(String namespace, Context<ReleaseAction> context) {
    return context
        .getClient()
        .resources(ReleaseAction.class)
        .inNamespace(namespace)
        .list()
        .getItems();
  }

  @Override
  public void pauseRollout(String namespace, String name, Context<ReleaseAction> context) {
    Resource<GenericKubernetesResource> rollout = rollout(context, namespace, name);
    if (rollout.get() == null) {
      return;
    }
    rollout.patch(
        new PatchContext.Builder()
            .withPatchType(PatchType.JSON_MERGE)
            .withFieldManager(ReleaseResourceRenderer.FIELD_MANAGER)
            .build(),
        "{\"spec\":{\"paused\":true}}");
  }

  @Override
  public void promoteRollout(String namespace, String name, Context<ReleaseAction> context) {
    Resource<GenericKubernetesResource> rollout = rollout(context, namespace, name);
    if (rollout.get() == null) {
      return;
    }
    rollout.patch(
        new PatchContext.Builder()
            .withPatchType(PatchType.JSON_MERGE)
            .withFieldManager(ReleaseResourceRenderer.FIELD_MANAGER)
            .build(),
        "{\"spec\":{\"paused\":false}}");
    rollout
        .subresource("status")
        .patch(PatchContext.of(PatchType.JSON_MERGE), "{\"status\":{\"pauseConditions\":[]}}");
  }

  @Override
  public void abortRollout(String namespace, String name, Context<ReleaseAction> context) {
    Resource<GenericKubernetesResource> rollout = rollout(context, namespace, name);
    if (rollout.get() == null) {
      return;
    }
    rollout
        .subresource("status")
        .patch(PatchContext.of(PatchType.JSON_MERGE), "{\"status\":{\"abort\":true}}");
  }

  @Override
  public void abortBootstrap(String namespace, String name, Context<ReleaseAction> context) {
    Resource<GenericKubernetesResource> rollout = rollout(context, namespace, name);
    if (rollout.get() == null) {
      return;
    }
    rollout.patch(
        new PatchContext.Builder()
            .withPatchType(PatchType.JSON_MERGE)
            .withFieldManager(ReleaseResourceRenderer.FIELD_MANAGER)
            .build(),
        "{\"spec\":{\"replicas\":0}}");
  }

  @Override
  public void retryAnalysis(String namespace, String name, Context<ReleaseAction> context) {
    Resource<GenericKubernetesResource> rollout = rollout(context, namespace, name);
    if (rollout.get() != null) {
      rollout.patch(
          new PatchContext.Builder()
              .withPatchType(PatchType.JSON_MERGE)
              .withFieldManager(ReleaseResourceRenderer.FIELD_MANAGER)
              .build(),
          "{\"spec\":{\"paused\":false}}");
      rollout
          .subresource("status")
          .patch(PatchContext.of(PatchType.JSON_MERGE), "{\"status\":{\"pauseConditions\":[]}}");
    }

    context
        .getClient()
        .genericKubernetesResources(KubernetesReconciliationRuntime.analysisRunDefinition())
        .inNamespace(namespace)
        .list()
        .getItems()
        .stream()
        .filter(run -> isOwnedByRollout(run, name))
        .forEach(run -> context.getClient().resource(run).delete());
  }

  @Override
  public void publishStatus(ReleaseAction action, Context<ReleaseAction> context) {
    context.resourceOperations().updatePrimaryStatus(action);
  }

  private static Resource<GenericKubernetesResource> rollout(
      Context<ReleaseAction> context, String namespace, String name) {
    return context
        .getClient()
        .genericKubernetesResources(KubernetesReconciliationRuntime.rolloutDefinition())
        .inNamespace(namespace)
        .withName(name);
  }

  private static boolean isOwnedByRollout(GenericKubernetesResource run, String rolloutName) {
    if (run.getMetadata() == null || run.getMetadata().getOwnerReferences() == null) {
      return false;
    }
    return run.getMetadata().getOwnerReferences().stream()
        .anyMatch(ref -> "Rollout".equals(ref.getKind()) && rolloutName.equals(ref.getName()));
  }
}
