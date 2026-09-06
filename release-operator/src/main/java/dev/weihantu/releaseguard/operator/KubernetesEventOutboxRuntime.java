package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEvent;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.javaoperatorsdk.operator.api.reconciler.Context;

final class KubernetesEventOutboxRuntime implements EventOutboxRuntime {
  @Override
  public ReleasePlan getPlan(String namespace, String name, Context<ReleaseEvent> context) {
    return context
        .getClient()
        .resources(ReleasePlan.class)
        .inNamespace(namespace)
        .withName(name)
        .get();
  }

  @Override
  public void publishStatus(ReleaseEvent event, Context<ReleaseEvent> context) {
    context.resourceOperations().updatePrimaryStatus(event);
  }
}
