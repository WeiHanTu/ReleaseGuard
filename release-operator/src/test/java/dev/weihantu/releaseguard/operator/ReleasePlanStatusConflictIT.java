package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import io.fabric8.kubeapitest.junit.EnableKubeAPIServer;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceSubresourcesBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceValidationBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.JSONSchemaPropsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.http.AsyncBody;
import io.fabric8.kubernetes.client.http.BasicBuilder;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import io.fabric8.kubernetes.client.http.Interceptor;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

@EnableKubeAPIServer
class ReleasePlanStatusConflictIT {
  private static final String NAMESPACE = "status-conflict";
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
  private static final String ANALYSIS_TEMPLATE_CRD =
      """
      apiVersion: apiextensions.k8s.io/v1
      kind: CustomResourceDefinition
      metadata:
        name: analysistemplates.argoproj.io
      spec:
        group: argoproj.io
        scope: Namespaced
        names:
          plural: analysistemplates
          singular: analysistemplate
          kind: AnalysisTemplate
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

  /** Covers RG-REC-001 and RG-REC-003 against a real kube-apiserver. */
  @Test
  void conflictForcesFreshReadAndIdempotentRecomputation() throws Exception {
    prepareCluster();
    ReleasePlan created = createValidatingPlan();
    StatusConflictInjector injector =
        new StatusConflictInjector(
            "/apis/delivery.weihantu.dev/v1alpha1/namespaces/"
                + NAMESPACE
                + "/releaseplans/checkout/status",
            () -> publishConcurrentProgress(created));
    KubernetesClient operatorClient =
        new KubernetesClientBuilder()
            .withConfig(client.getConfiguration())
            .withHttpClientBuilderConsumer(
                builder -> builder.addOrReplaceInterceptor("status-conflict", injector))
            .build();
    RecordingRuntime runtime = new RecordingRuntime();
    Operator operator =
        new Operator(
            configuration -> {
              configuration
                  .withKubernetesClient(operatorClient)
                  .withCloseClientOnStop(false)
                  .withCacheSyncTimeout(Duration.ofSeconds(15));
            });
    operator.register(new ReleasePlanReconciler(new ReleasePlanPlanner(), runtime));

    try {
      operator.start();
      await(
          () ->
              injector.conflictResponses.get() == 1
                  && runtime.sawPhase(ReleasePlanStatus.Phase.Progressing)
                  && finalStatusWasRecomputed(),
          Duration.ofSeconds(20),
          () ->
              "statusWriteAttempts="
                  + injector.statusWriteAttempts
                  + ", conflicts="
                  + injector.conflictResponses
                  + ", inputs="
                  + runtime.inputs
                  + ", finalStatus="
                  + releasePlan().getStatus());

      ReleasePlan converged = releasePlan();
      InputSnapshot stale = runtime.first(ReleasePlanStatus.Phase.Validating);
      InputSnapshot fresh = runtime.first(ReleasePlanStatus.Phase.Progressing);

      assertEquals(1, injector.injectedWrites.get());
      assertEquals(1, injector.conflictResponses.get());
      assertEquals(2, injector.statusWriteAttempts.get());
      assertNotEquals(stale.resourceVersion(), fresh.resourceVersion());
      assertEquals("checkout-concurrent-stable", fresh.stableRevision());
      assertEquals(ReleasePlanStatus.Phase.Progressing, converged.getStatus().phase());
      assertEquals("checkout-concurrent-stable", converged.getStatus().stableRevision());
      assertEquals("checkout-concurrent-canary", converged.getStatus().canaryRevision());
      assertEquals("RolloutStatusStale", conditionReason(converged, "Ready"));
      assertTrue(runtime.applyAttempts.get() >= 2);
      assertEquals(2, client.services().inNamespace(NAMESPACE).list().getItems().size());
      assertEquals(
          1, client.network().v1().ingresses().inNamespace(NAMESPACE).list().getItems().size());
      assertEquals(
          1,
          client
              .genericKubernetesResources("argoproj.io/v1alpha1", "Rollout")
              .inNamespace(NAMESPACE)
              .list()
              .getItems()
              .size());
    } finally {
      operator.stop();
      operatorClient.close();
    }
  }

  private static void prepareCluster() {
    client
        .namespaces()
        .resource(
            new NamespaceBuilder().withNewMetadata().withName(NAMESPACE).endMetadata().build())
        .serverSideApply();
    var releasePlanCrd =
        CustomResourceDefinitionContext.v1CRDFromCustomResourceType(ReleasePlan.class).build();
    // Schema correctness is covered by CRD freshness tests; this ephemeral CRD isolates status
    // concurrency behavior while retaining the real status subresource.
    releasePlanCrd
        .getSpec()
        .getVersions()
        .forEach(
            version -> {
              version.setSchema(
                  new CustomResourceValidationBuilder()
                      .withOpenAPIV3Schema(
                          new JSONSchemaPropsBuilder()
                              .withType("object")
                              .withXKubernetesPreserveUnknownFields(true)
                              .build())
                      .build());
              version.setSubresources(
                  new CustomResourceSubresourcesBuilder().withNewStatus().endStatus().build());
            });
    client
        .apiextensions()
        .v1()
        .customResourceDefinitions()
        .resource(releasePlanCrd)
        .serverSideApply();
    client
        .load(new ByteArrayInputStream(ROLLOUT_CRD.getBytes(StandardCharsets.UTF_8)))
        .items()
        .forEach(resource -> client.resource(resource).serverSideApply());
    client
        .load(new ByteArrayInputStream(ANALYSIS_TEMPLATE_CRD.getBytes(StandardCharsets.UTF_8)))
        .items()
        .forEach(resource -> client.resource(resource).serverSideApply());
  }

  private static ReleasePlan createValidatingPlan() {
    ReleasePlan desired =
        OperatorTestFixtures.plan(1, NAMESPACE, ReleasePlanSpec.DeletionPolicy.Delete);
    desired.getMetadata().setUid(null);
    desired.getMetadata().setGeneration(null);
    ReleasePlan created = client.resource(desired).create();
    long generation = created.getMetadata().getGeneration();
    String status =
        "{\"status\":{\"observedGeneration\":"
            + generation
            + ",\"releaseId\":\""
            + created.getMetadata().getUid()
            + ":"
            + generation
            + "\",\"phase\":\"Validating\",\"conditions\":[]}}";
    client
        .resource(created)
        .subresource("status")
        .patch(PatchContext.of(PatchType.JSON_MERGE), status);
    return releasePlan();
  }

  private static void publishConcurrentProgress(ReleasePlan initial) {
    ReleasePlan latest = releasePlan();
    long generation = latest.getMetadata().getGeneration();
    String status =
        "{\"status\":{\"observedGeneration\":"
            + generation
            + ",\"releaseId\":\""
            + initial.getMetadata().getUid()
            + ":"
            + generation
            + "\",\"phase\":\"Progressing\",\"currentStep\":1,"
            + "\"stableRevision\":\"checkout-concurrent-stable\","
            + "\"canaryRevision\":\"checkout-concurrent-canary\",\"conditions\":[]}}";
    client
        .resource(latest)
        .subresource("status")
        .patch(PatchContext.of(PatchType.JSON_MERGE), status);
  }

  private static boolean finalStatusWasRecomputed() {
    ReleasePlan plan = releasePlan();
    return plan != null
        && plan.getStatus() != null
        && "checkout-concurrent-stable".equals(plan.getStatus().stableRevision())
        && "RolloutStatusStale".equals(conditionReasonOrNull(plan, "Ready"));
  }

  private static ReleasePlan releasePlan() {
    return client.resources(ReleasePlan.class).inNamespace(NAMESPACE).withName("checkout").get();
  }

  private static String conditionReason(ReleasePlan plan, String type) {
    return plan.getStatus().conditions().stream()
        .filter(condition -> type.equals(condition.getType()))
        .findFirst()
        .orElseThrow()
        .getReason();
  }

  private static String conditionReasonOrNull(ReleasePlan plan, String type) {
    if (plan.getStatus().conditions() == null) {
      return null;
    }
    return plan.getStatus().conditions().stream()
        .filter(condition -> type.equals(condition.getType()))
        .findFirst()
        .map(condition -> condition.getReason())
        .orElse(null);
  }

  private static void await(
      BooleanSupplier condition, Duration timeout, Supplier<String> diagnostics) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("condition was not met within " + timeout + "; " + diagnostics.get());
  }

  private record InputSnapshot(
      ReleasePlanStatus.Phase phase, String resourceVersion, String stableRevision) {}

  private static final class RecordingRuntime implements ReconciliationRuntime {
    private final KubernetesReconciliationRuntime delegate = new KubernetesReconciliationRuntime();
    private final List<InputSnapshot> inputs = new CopyOnWriteArrayList<>();
    private final AtomicInteger applyAttempts = new AtomicInteger();

    @Override
    public Map<String, MetricPolicySnapshot> acceptedPolicies(
        ReleasePlan plan, Context<ReleasePlan> context) {
      ReleasePlanStatus status = plan.getStatus();
      inputs.add(
          new InputSnapshot(
              status == null ? null : status.phase(),
              plan.getMetadata().getResourceVersion(),
              status == null ? null : status.stableRevision()));
      return OperatorTestFixtures.policies();
    }

    @Override
    public void apply(List<HasMetadata> resources, Context<ReleasePlan> context) {
      applyAttempts.incrementAndGet();
      delegate.apply(resources, context);
    }

    @Override
    public RolloutObservation observeRollout(ReleasePlan plan, Context<ReleasePlan> context) {
      return delegate.observeRollout(plan, context);
    }

    @Override
    public CleanupObservation observeCleanup(ReleasePlan plan, Context<ReleasePlan> context) {
      return delegate.observeCleanup(plan, context);
    }

    @Override
    public void requestAbort(ReleasePlan plan, Context<ReleasePlan> context) {
      delegate.requestAbort(plan, context);
    }

    @Override
    public void deleteManagedChildren(ReleasePlan plan, Context<ReleasePlan> context) {
      delegate.deleteManagedChildren(plan, context);
    }

    @Override
    public void orphanStableResources(ReleasePlan plan, Context<ReleasePlan> context) {
      delegate.orphanStableResources(plan, context);
    }

    @Override
    public void publishStatus(ReleasePlan plan, Context<ReleasePlan> context) {
      delegate.publishStatus(plan, context);
    }

    boolean sawPhase(ReleasePlanStatus.Phase phase) {
      return inputs.stream().anyMatch(input -> input.phase() == phase);
    }

    InputSnapshot first(ReleasePlanStatus.Phase phase) {
      return inputs.stream().filter(input -> input.phase() == phase).findFirst().orElseThrow();
    }
  }

  private static final class StatusConflictInjector implements Interceptor {
    private final String statusPath;
    private final Runnable concurrentWrite;
    private final AtomicBoolean armed = new AtomicBoolean(true);
    private final AtomicInteger injectedWrites = new AtomicInteger();
    private final AtomicInteger statusWriteAttempts = new AtomicInteger();
    private final AtomicInteger conflictResponses = new AtomicInteger();

    private StatusConflictInjector(String statusPath, Runnable concurrentWrite) {
      this.statusPath = statusPath;
      this.concurrentWrite = concurrentWrite;
    }

    @Override
    public void before(BasicBuilder builder, HttpRequest request, RequestTags tags) {
      if (!isStatusWrite(request)) {
        return;
      }
      statusWriteAttempts.incrementAndGet();
      if (armed.compareAndSet(true, false)) {
        concurrentWrite.run();
        injectedWrites.incrementAndGet();
      }
    }

    @Override
    public void after(
        HttpRequest request,
        HttpResponse<?> response,
        AsyncBody.Consumer<List<ByteBuffer>> consumer) {
      if (isStatusWrite(request) && response.code() == 409) {
        conflictResponses.incrementAndGet();
      }
    }

    private boolean isStatusWrite(HttpRequest request) {
      return "PUT".equals(request.method()) && statusPath.equals(request.uri().getPath());
    }
  }
}
