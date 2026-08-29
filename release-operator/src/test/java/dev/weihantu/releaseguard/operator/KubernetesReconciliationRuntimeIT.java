package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.fabric8.kubeapitest.junit.EnableKubeAPIServer;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
            schema:
              openAPIV3Schema:
                type: object
                x-kubernetes-preserve-unknown-fields: true
      """;

  static KubernetesClient client;

  @Test
  void repeatedServerSideApplyConvergesWithoutDuplicateResources() {
    client
        .namespaces()
        .resource(new NamespaceBuilder().withNewMetadata().withName("demo").endMetadata().build())
        .create();
    client
        .load(new ByteArrayInputStream(ROLLOUT_CRD.getBytes(StandardCharsets.UTF_8)))
        .items()
        .forEach(resource -> client.resource(resource).serverSideApply());
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

  private static ResourceDefinitionContext rolloutDefinition() {
    return new ResourceDefinitionContext.Builder()
        .withGroup("argoproj.io")
        .withVersion("v1alpha1")
        .withKind("Rollout")
        .withPlural("rollouts")
        .withNamespaced(true)
        .build();
  }
}
