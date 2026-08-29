package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicy;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicyStatus;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class KubernetesReconciliationRuntime implements ReconciliationRuntime {
  private static final String CANARY_WEIGHT_ANNOTATION =
      "nginx.ingress.kubernetes.io/canary-weight";

  @Override
  public Map<String, MetricPolicySpec> acceptedPolicies(
      ReleasePlan plan, Context<ReleasePlan> context) {
    Set<String> references = new LinkedHashSet<>();
    if (plan.getSpec() != null && plan.getSpec().rollout() != null) {
      List<ReleasePlanSpec.Gate> gates = plan.getSpec().rollout().gates();
      if (gates != null) {
        gates.stream()
            .filter(Objects::nonNull)
            .map(ReleasePlanSpec.Gate::policyRef)
            .filter(Objects::nonNull)
            .forEach(references::add);
      }
    }

    Map<String, MetricPolicySpec> accepted = new LinkedHashMap<>();
    for (String reference : references) {
      MetricPolicy policy =
          context.getClient().resources(MetricPolicy.class).withName(reference).get();
      if (isAcceptedCurrentVersion(policy)) {
        accepted.put(reference, policy.getSpec());
      }
    }
    return Map.copyOf(accepted);
  }

  @Override
  public void apply(List<HasMetadata> resources, Context<ReleasePlan> context) {
    for (HasMetadata resource : resources) {
      context
          .getClient()
          .resource(resource)
          .fieldManager(ReleaseResourceRenderer.FIELD_MANAGER)
          .serverSideApply();
    }
  }

  @Override
  public CleanupObservation observeCleanup(ReleasePlan plan, Context<ReleasePlan> context) {
    String namespace = plan.getMetadata().getNamespace();
    String name = plan.getMetadata().getName();
    String uid = plan.getMetadata().getUid();
    GenericKubernetesResource rollout = rollout(context, namespace, name).get();
    Service stableService =
        context.getClient().services().inNamespace(namespace).withName(name + "-stable").get();
    Service canaryService =
        context.getClient().services().inNamespace(namespace).withName(name + "-canary").get();
    Ingress stableIngress =
        context.getClient().network().v1().ingresses().inNamespace(namespace).withName(name).get();
    Ingress canaryIngress =
        context
            .getClient()
            .network()
            .v1()
            .ingresses()
            .inNamespace(namespace)
            .withName(canaryIngressName(name))
            .get();

    Map<?, ?> rolloutStatus =
        rollout == null ? Map.of() : asMap(rollout.getAdditionalProperties().get("status"));
    boolean abortAcknowledged =
        Boolean.TRUE.equals(rolloutStatus.get("abort")) && rolloutStatus.get("abortedAt") != null;
    String stableReplicaSet = stringValue(rolloutStatus.get("stableRS"));
    boolean stableRevisionPresent = stableReplicaSet != null && !stableReplicaSet.isBlank();
    boolean stableSelectorRestored =
        stableService != null
            && stableService.getSpec() != null
            && stableService.getSpec().getSelector() != null
            && stableReplicaSet != null
            && stableReplicaSet.equals(
                stableService.getSpec().getSelector().get("rollouts-pod-template-hash"));
    boolean stableTrafficRestored =
        stableSelectorRestored && hasReadyEndpoint(context, namespace, name + "-stable");
    boolean canaryTrafficZero =
        canaryIngress == null
            || (canaryIngress.getMetadata().getAnnotations() != null
                && "0"
                    .equals(
                        canaryIngress
                            .getMetadata()
                            .getAnnotations()
                            .get(CANARY_WEIGHT_ANNOTATION)));
    boolean managedChildrenPresent =
        rollout != null
            || stableService != null
            || canaryService != null
            || stableIngress != null
            || canaryIngress != null;
    boolean retainedDetached =
        rollout != null
            && stableService != null
            && stableIngress != null
            && detached(rollout.getMetadata(), uid)
            && detached(stableService.getMetadata(), uid)
            && detached(stableIngress.getMetadata(), uid);
    boolean orphanComplete = retainedDetached && canaryService == null && canaryIngress == null;

    return new CleanupObservation(
        rollout != null,
        abortAcknowledged,
        stableRevisionPresent,
        stableTrafficRestored,
        canaryTrafficZero,
        managedChildrenPresent,
        orphanComplete);
  }

  @Override
  public void requestAbort(ReleasePlan plan, Context<ReleasePlan> context) {
    String namespace = plan.getMetadata().getNamespace();
    String name = plan.getMetadata().getName();
    rollout(context, namespace, name)
        .subresource("status")
        .patch(PatchContext.of(PatchType.JSON_MERGE), "{\"status\":{\"abort\":true}}");
  }

  @Override
  public void deleteManagedChildren(ReleasePlan plan, Context<ReleasePlan> context) {
    String namespace = plan.getMetadata().getNamespace();
    String name = plan.getMetadata().getName();
    rollout(context, namespace, name).delete();
    context
        .getClient()
        .network()
        .v1()
        .ingresses()
        .inNamespace(namespace)
        .withName(canaryIngressName(name))
        .delete();
    context.getClient().network().v1().ingresses().inNamespace(namespace).withName(name).delete();
    context.getClient().services().inNamespace(namespace).withName(name + "-canary").delete();
    context.getClient().services().inNamespace(namespace).withName(name + "-stable").delete();
  }

  @Override
  public void orphanStableResources(ReleasePlan plan, Context<ReleasePlan> context) {
    String namespace = plan.getMetadata().getNamespace();
    String name = plan.getMetadata().getName();
    String uid = plan.getMetadata().getUid();

    context.getClient().services().inNamespace(namespace).withName(name + "-canary").delete();
    context
        .getClient()
        .network()
        .v1()
        .ingresses()
        .inNamespace(namespace)
        .withName(canaryIngressName(name))
        .delete();
    detach(rollout(context, namespace, name), uid);
    detach(context.getClient().services().inNamespace(namespace).withName(name + "-stable"), uid);
    detach(
        context.getClient().network().v1().ingresses().inNamespace(namespace).withName(name), uid);
  }

  @Override
  public void publishCleanupStatus(ReleasePlan plan, Context<ReleasePlan> context) {
    context.resourceOperations().updatePrimaryStatus(plan);
  }

  static boolean isAcceptedCurrentVersion(MetricPolicy policy) {
    if (policy == null || policy.getMetadata() == null) {
      return false;
    }
    MetricPolicyStatus status = policy.getStatus();
    return status != null
        && Boolean.TRUE.equals(status.accepted())
        && Objects.equals(policy.getMetadata().getGeneration(), status.observedGeneration());
  }

  private static Resource<GenericKubernetesResource> rollout(
      Context<ReleasePlan> context, String namespace, String name) {
    return context
        .getClient()
        .genericKubernetesResources(rolloutDefinition())
        .inNamespace(namespace)
        .withName(name);
  }

  static ResourceDefinitionContext rolloutDefinition() {
    return new ResourceDefinitionContext.Builder()
        .withGroup("argoproj.io")
        .withVersion("v1alpha1")
        .withKind("Rollout")
        .withPlural("rollouts")
        .withNamespaced(true)
        .build();
  }

  private static boolean hasReadyEndpoint(
      Context<ReleasePlan> context, String namespace, String serviceName) {
    Endpoints endpoints =
        context.getClient().endpoints().inNamespace(namespace).withName(serviceName).get();
    return endpoints != null
        && endpoints.getSubsets() != null
        && endpoints.getSubsets().stream()
            .anyMatch(subset -> subset.getAddresses() != null && !subset.getAddresses().isEmpty());
  }

  private static String canaryIngressName(String name) {
    return name + "-" + name + "-canary";
  }

  private static Map<?, ?> asMap(Object value) {
    return value instanceof Map<?, ?> map ? map : Map.of();
  }

  private static String stringValue(Object value) {
    return value instanceof String string ? string : null;
  }

  private static boolean detached(ObjectMeta metadata, String ownerUid) {
    if (metadata == null) {
      return false;
    }
    boolean ownerDetached =
        metadata.getOwnerReferences() == null
            || metadata.getOwnerReferences().stream()
                .noneMatch(owner -> ownerUid.equals(owner.getUid()));
    boolean managerDetached =
        metadata.getLabels() == null
            || !"releaseguard"
                .equals(metadata.getLabels().get(ReleaseResourceRenderer.MANAGED_BY_LABEL));
    return ownerDetached && managerDetached;
  }

  private static <T extends HasMetadata> void detach(Resource<T> resource, String ownerUid) {
    if (resource.get() == null) {
      return;
    }
    resource.edit(
        current -> {
          ObjectMeta metadata = current.getMetadata();
          if (metadata.getOwnerReferences() != null) {
            metadata.setOwnerReferences(
                metadata.getOwnerReferences().stream()
                    .filter(owner -> !ownerUid.equals(owner.getUid()))
                    .toList());
          }
          if (metadata.getLabels() != null) {
            Map<String, String> labels = new LinkedHashMap<>(metadata.getLabels());
            labels.remove(ReleaseResourceRenderer.MANAGED_BY_LABEL);
            metadata.setLabels(Map.copyOf(labels));
          }
          return current;
        });
  }
}
