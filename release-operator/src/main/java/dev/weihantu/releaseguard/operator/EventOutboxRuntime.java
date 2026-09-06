package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEvent;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.javaoperatorsdk.operator.api.reconciler.Context;

interface EventOutboxRuntime {
  ReleasePlan getPlan(String namespace, String name, Context<ReleaseEvent> context);

  void publishStatus(ReleaseEvent event, Context<ReleaseEvent> context);
}
