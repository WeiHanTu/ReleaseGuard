package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.processing.GroupVersionKind;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReleasePlanEventSources {
  private static final String MANAGED_SELECTOR =
      ReleaseResourceRenderer.MANAGED_BY_LABEL + "=releaseguard";
  private static final GroupVersionKind ROLLOUT_GVK =
      new GroupVersionKind("argoproj.io", "v1alpha1", "Rollout");
  private static final GroupVersionKind ANALYSIS_RUN_GVK =
      new GroupVersionKind("argoproj.io", "v1alpha1", "AnalysisRun");

  private ReleasePlanEventSources() {}

  static List<EventSource<?, ReleasePlan>> create(EventSourceContext<ReleasePlan> context) {
    var rolloutConfiguration =
        InformerEventSourceConfiguration.from(ROLLOUT_GVK, ReleasePlan.class)
            .withName("argo-rollout-events")
            .withNamespacesInheritedFromController()
            .withLabelSelector(MANAGED_SELECTOR)
            .withSecondaryToPrimaryMapper(ReleasePlanEventSources::primaryResourceIDs)
            .build();
    // ReleaseGuard-owned bootstrap runs carry the managed-by label; the step runs Argo creates for
    // canary analysis do not, and reach the reconciler through Rollout status instead.
    var analysisRunConfiguration =
        InformerEventSourceConfiguration.from(ANALYSIS_RUN_GVK, ReleasePlan.class)
            .withName("argo-analysis-run-events")
            .withNamespacesInheritedFromController()
            .withLabelSelector(MANAGED_SELECTOR)
            .withSecondaryToPrimaryMapper(ReleasePlanEventSources::primaryResourceIDs)
            .build();
    var serviceConfiguration =
        InformerEventSourceConfiguration.from(Service.class, ReleasePlan.class)
            .withName("release-service-events")
            .withNamespacesInheritedFromController()
            .withLabelSelector(MANAGED_SELECTOR)
            .withSecondaryToPrimaryMapper(ReleasePlanEventSources::primaryResourceIDs)
            .build();
    var ingressConfiguration =
        InformerEventSourceConfiguration.from(Ingress.class, ReleasePlan.class)
            .withName("release-ingress-events")
            .withNamespacesInheritedFromController()
            .withLabelSelector(MANAGED_SELECTOR)
            .withSecondaryToPrimaryMapper(ReleasePlanEventSources::primaryResourceIDs)
            .build();

    return List.of(
        new InformerEventSource<GenericKubernetesResource, ReleasePlan>(
            rolloutConfiguration, context),
        new InformerEventSource<GenericKubernetesResource, ReleasePlan>(
            analysisRunConfiguration, context),
        new InformerEventSource<Service, ReleasePlan>(serviceConfiguration, context),
        new InformerEventSource<Ingress, ReleasePlan>(ingressConfiguration, context));
  }

  static Set<ResourceID> primaryResourceIDs(HasMetadata secondary) {
    if (secondary == null || secondary.getMetadata() == null) {
      return Set.of();
    }
    Map<String, String> labels = secondary.getMetadata().getLabels();
    String planName = labels == null ? null : labels.get(ReleaseResourceRenderer.PLAN_NAME_LABEL);
    String namespace = secondary.getMetadata().getNamespace();
    if (planName == null || planName.isBlank() || namespace == null || namespace.isBlank()) {
      return Set.of();
    }
    return Set.of(new ResourceID(planName, namespace));
  }
}
