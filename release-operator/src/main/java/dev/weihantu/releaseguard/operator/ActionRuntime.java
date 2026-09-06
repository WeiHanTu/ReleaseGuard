package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseAction;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.util.List;

interface ActionRuntime {
  ReleasePlan getPlan(String namespace, String name, Context<ReleaseAction> context);

  List<ReleaseAction> listActions(String namespace, Context<ReleaseAction> context);

  void pauseRollout(String namespace, String name, Context<ReleaseAction> context);

  void promoteRollout(String namespace, String name, Context<ReleaseAction> context);

  void abortRollout(String namespace, String name, Context<ReleaseAction> context);

  void abortBootstrap(String namespace, String name, Context<ReleaseAction> context);

  void retryAnalysis(String namespace, String name, Context<ReleaseAction> context);

  void publishStatus(ReleaseAction action, Context<ReleaseAction> context);
}
