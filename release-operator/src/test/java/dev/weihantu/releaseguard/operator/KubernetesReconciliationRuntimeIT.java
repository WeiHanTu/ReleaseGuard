package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.fabric8.kubeapitest.junit.EnableKubeAPIServer;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@EnableKubeAPIServer
class KubernetesReconciliationRuntimeIT {
  private static final String ROLLOUT_CRD =
      """
      apiVersion: apiextensions.k8s.io/v1
      kind: CustomResourceDefinition
      metadata:
        name: rollouts.argoproj.io
      spec:
        group: argoproj.io
        scope: Namespaced
        names:
          plural: rollouts
          singular: rollout
          kind: Rollout
        versions:
          - name: v1alpha1
            served: true
            storage: true
            subresources:
              status: {}
            schema:
              openAPIV3Schema:
                type: object
                x-kubernetes-preserve-unknown-fields: true
      """;

  static KubernetesClient client;

  @Test
  void repeatedServerSideApplyConvergesWithoutDuplicateResources() {
    prepareNamespace("demo");
    KubernetesReconciliationRuntime runtime = new KubernetesReconciliationRuntime();
    List<HasMetadata> desired = new ReleaseResourceRenderer().render(OperatorTestFixtures.plan(11));
    Context<ReleasePlan> context = context(client);

    runtime.apply(desired, context);
    runtime.apply(desired, context);

    assertEquals(2, client.services().inNamespace("demo").list().getItems().size());
    assertEquals(1, client.network().v1().ingresses().inNamespace("demo").list().getItems().size());
    assertEquals(
        1,
        client
            .genericKubernetesResources(rolloutDefinition())
            .inNamespace("demo")
            .list()
            .getItems()
            .size());
  }

  @Test
  void observesAbortStableEndpointsAndNginxCanaryWeight() {
    String namespace = "cleanup-observe";
    ReleasePlan plan = prepareRelease(namespace, 12);
    KubernetesReconciliationRuntime runtime = new KubernetesReconciliationRuntime();
    Context<ReleasePlan> context = context(client);
    prepareStableRestoration(namespace, "5");

    CleanupObservation beforeAbort = runtime.observeCleanup(plan, context);
    assertFalse(beforeAbort.abortAcknowledged());
    assertTrue(beforeAbort.stableTrafficRestored());
    assertFalse(beforeAbort.canaryTrafficZero());

    runtime.requestAbort(plan, context);
    assertFalse(runtime.observeCleanup(plan, context).abortAcknowledged());
    acknowledgeAbort(namespace);
    client
        .network()
        .v1()
        .ingresses()
        .inNamespace(namespace)
        .withName("checkout-checkout-canary")
        .edit(
            ingress -> {
              ingress
                  .getMetadata()
                  .getAnnotations()
                  .put("nginx.ingress.kubernetes.io/canary-weight", "0");
              return ingress;
            });

    CleanupObservation restored = runtime.observeCleanup(plan, context);
    assertTrue(restored.abortAcknowledged());
    assertTrue(restored.stableTrafficRestored());
    assertTrue(restored.canaryTrafficZero());
  }

  @Test
  void orphaningRestoresArgoServiceCleanupAndRetainsOnlyStableResources() {
    String namespace = "cleanup-orphan";
    ReleasePlan plan = prepareRelease(namespace, 13);
    KubernetesReconciliationRuntime runtime = new KubernetesReconciliationRuntime();
    Context<ReleasePlan> context = context(client);
    prepareStableRestoration(namespace, "0");
    runtime.requestAbort(plan, context);
    acknowledgeAbort(namespace);

    runtime.orphanStableResources(plan, context);
    completeOrphanDeletionWithoutGarbageCollector(namespace);
    removeStableRevisionFromService(namespace);

    assertFalse(runtime.observeCleanup(plan, context).stableTrafficRestored());
    runtime.orphanStableResources(plan, context);

    CleanupObservation observation = runtime.observeCleanup(plan, context);
    assertTrue(observation.orphanComplete(), observation.toString());
    assertEquals(
        0,
        client
            .genericKubernetesResources(rolloutDefinition())
            .inNamespace(namespace)
            .list()
            .getItems()
            .size());
    List<ReplicaSet> retainedReplicaSets =
        client.apps().replicaSets().inNamespace(namespace).list().getItems();
    assertEquals(1, retainedReplicaSets.size());
    assertEquals("checkout-stable-hash", retainedReplicaSets.getFirst().getMetadata().getName());
    assertTrue(
        retainedReplicaSets.getFirst().getMetadata().getOwnerReferences() == null
            || retainedReplicaSets.getFirst().getMetadata().getOwnerReferences().stream()
                .noneMatch(owner -> "Rollout".equals(owner.getKind())));
    assertFalse(
        retainedReplicaSets
            .getFirst()
            .getMetadata()
            .getLabels()
            .containsKey(ReleaseResourceRenderer.MANAGED_BY_LABEL));
    assertEquals(1, client.services().inNamespace(namespace).list().getItems().size());
    assertEquals(
        1, client.network().v1().ingresses().inNamespace(namespace).list().getItems().size());
    assertFalse(
        client
            .services()
            .inNamespace(namespace)
            .withName("checkout-stable")
            .get()
            .getMetadata()
            .getLabels()
            .containsKey(ReleaseResourceRenderer.MANAGED_BY_LABEL));
  }

  @Test
  void deletingRemovesEveryManagedChild() {
    String namespace = "cleanup-delete";
    ReleasePlan plan = prepareRelease(namespace, 14);
    KubernetesReconciliationRuntime runtime = new KubernetesReconciliationRuntime();
    Context<ReleasePlan> context = context(client);
    prepareStableRestoration(namespace, "0");

    runtime.deleteManagedChildren(plan, context);

    assertFalse(runtime.observeCleanup(plan, context).managedChildrenPresent());
    assertTrue(client.services().inNamespace(namespace).list().getItems().isEmpty());
    assertTrue(
        client.network().v1().ingresses().inNamespace(namespace).list().getItems().isEmpty());
    assertTrue(
        client
            .genericKubernetesResources(rolloutDefinition())
            .inNamespace(namespace)
            .list()
            .getItems()
            .isEmpty());
    assertTrue(client.apps().replicaSets().inNamespace(namespace).list().getItems().isEmpty());
  }

  @SuppressWarnings("unchecked")
  private static Context<ReleasePlan> context(KubernetesClient client) {
    return (Context<ReleasePlan>)
        Proxy.newProxyInstance(
            Context.class.getClassLoader(),
            new Class<?>[] {Context.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getClient")) {
                return client;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static ReleasePlan prepareRelease(String namespace, long generation) {
    prepareNamespace(namespace);
    ReleasePlan plan =
        OperatorTestFixtures.plan(
            generation,
            namespace,
            dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec.DeletionPolicy.Delete);
    new KubernetesReconciliationRuntime()
        .apply(new ReleaseResourceRenderer().render(plan), context(client));
    return plan;
  }

  private static void prepareNamespace(String namespace) {
    client
        .namespaces()
        .resource(
            new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build())
        .serverSideApply();
    client
        .load(new ByteArrayInputStream(ROLLOUT_CRD.getBytes(StandardCharsets.UTF_8)))
        .items()
        .forEach(resource -> client.resource(resource).serverSideApply());
  }

  private static void prepareStableRestoration(String namespace, String canaryWeight) {
    var rollout =
        client
            .genericKubernetesResources(rolloutDefinition())
            .inNamespace(namespace)
            .withName("checkout")
            .get();
    client
        .genericKubernetesResources(rolloutDefinition())
        .inNamespace(namespace)
        .withName("checkout")
        .subresource("status")
        .patch(
            PatchContext.of(PatchType.JSON_MERGE),
            "{\"status\":{\"abort\":false,\"stableRS\":\"stable-hash\"}}");
    prepareReplicaSet(namespace, rollout.getMetadata().getUid(), "stable-hash");
    prepareReplicaSet(namespace, rollout.getMetadata().getUid(), "canary-hash");
    client
        .services()
        .inNamespace(namespace)
        .withName("checkout-stable")
        .edit(
            service -> {
              Map<String, String> selector = new LinkedHashMap<>(service.getSpec().getSelector());
              selector.put("rollouts-pod-template-hash", "stable-hash");
              service.getSpec().setSelector(Map.copyOf(selector));
              return service;
            });
    client
        .endpoints()
        .inNamespace(namespace)
        .resource(
            new EndpointsBuilder()
                .withNewMetadata()
                .withName("checkout-stable")
                .withNamespace(namespace)
                .endMetadata()
                .addNewSubset()
                .addNewAddress()
                .withIp("10.0.0.10")
                .endAddress()
                .endSubset()
                .build())
        .serverSideApply();
    client
        .network()
        .v1()
        .ingresses()
        .inNamespace(namespace)
        .resource(
            new IngressBuilder()
                .withNewMetadata()
                .withName("checkout-checkout-canary")
                .withNamespace(namespace)
                .addToAnnotations("nginx.ingress.kubernetes.io/canary", "true")
                .addToAnnotations("nginx.ingress.kubernetes.io/canary-weight", canaryWeight)
                .endMetadata()
                .withNewSpec()
                .withNewDefaultBackend()
                .withNewService()
                .withName("checkout-canary")
                .withNewPort()
                .withNumber(80)
                .endPort()
                .endService()
                .endDefaultBackend()
                .endSpec()
                .build())
        .serverSideApply();
  }

  private static void prepareReplicaSet(String namespace, String rolloutUid, String revision) {
    Map<String, String> labels =
        new LinkedHashMap<>(
            client
                .genericKubernetesResources(rolloutDefinition())
                .inNamespace(namespace)
                .withName("checkout")
                .get()
                .getMetadata()
                .getLabels());
    labels.put("rollouts-pod-template-hash", revision);
    client
        .apps()
        .replicaSets()
        .inNamespace(namespace)
        .resource(
            new ReplicaSetBuilder()
                .withNewMetadata()
                .withName("checkout-" + revision)
                .withNamespace(namespace)
                .withLabels(labels)
                .addNewOwnerReference()
                .withApiVersion("argoproj.io/v1alpha1")
                .withKind("Rollout")
                .withName("checkout")
                .withUid(rolloutUid)
                .withController(true)
                .withBlockOwnerDeletion(true)
                .endOwnerReference()
                .endMetadata()
                .withNewSpec()
                .withReplicas(2)
                .withNewSelector()
                .addToMatchLabels("rollouts-pod-template-hash", revision)
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("rollouts-pod-template-hash", revision)
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("app")
                .withImage("registry.k8s.io/pause:3.10")
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build())
        .serverSideApply();
  }

  private static void acknowledgeAbort(String namespace) {
    client
        .genericKubernetesResources(rolloutDefinition())
        .inNamespace(namespace)
        .withName("checkout")
        .subresource("status")
        .patch(
            PatchContext.of(PatchType.JSON_MERGE),
            "{\"status\":{\"abortedAt\":\"2026-08-29T12:00:00Z\"}}");
  }

  private static void completeOrphanDeletionWithoutGarbageCollector(String namespace) {
    var rollout =
        client
            .genericKubernetesResources(rolloutDefinition())
            .inNamespace(namespace)
            .withName("checkout");
    if (rollout.get() != null) {
      rollout.edit(
          current -> {
            // kube-api-test does not run the garbage collector that completes orphan deletion.
            current.getMetadata().setFinalizers(List.of());
            return current;
          });
    }
  }

  private static void removeStableRevisionFromService(String namespace) {
    client
        .services()
        .inNamespace(namespace)
        .withName("checkout-stable")
        .edit(
            service -> {
              Map<String, String> selector = new LinkedHashMap<>(service.getSpec().getSelector());
              selector.remove("rollouts-pod-template-hash");
              service.getSpec().setSelector(Map.copyOf(selector));
              return service;
            });
  }

  private static ResourceDefinitionContext rolloutDefinition() {
    return KubernetesReconciliationRuntime.rolloutDefinition();
  }
}
