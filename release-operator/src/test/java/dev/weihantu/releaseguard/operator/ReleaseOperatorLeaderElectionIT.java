package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.weihantu.releaseguard.api.v1alpha1.ReleaseEvent;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.fabric8.kubeapitest.junit.EnableKubeAPIServer;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceSubresourcesBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceValidationBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.JSONSchemaPropsBuilder;
import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.javaoperatorsdk.operator.Operator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Validates active-passive multi-replica operator leader election and failover via Kubernetes
 * coordination.k8s.io/v1 Lease (RG-REC-007, RG-REC-008).
 */
@EnableKubeAPIServer
class ReleaseOperatorLeaderElectionIT {
  private static final String NAMESPACE = "leader-election-it";
  private static final String LEASE_NAME = "releaseguard-operator-lock";

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
  private static final String ANALYSIS_RUN_CRD =
      """
      apiVersion: apiextensions.k8s.io/v1
      kind: CustomResourceDefinition
      metadata:
        name: analysisruns.argoproj.io
      spec:
        group: argoproj.io
        scope: Namespaced
        names:
          plural: analysisruns
          singular: analysisrun
          kind: AnalysisRun
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
  void activePassiveFailoverMaintainsSingleActiveLeader() throws Exception {
    String namespace = "leader-failover";
    prepareNamespace(namespace);
    prepareCrds();

    LeaderElectionPolicy policy1 =
        new LeaderElectionPolicy(
            true,
            LEASE_NAME,
            namespace,
            "replica-1",
            Duration.ofSeconds(3),
            Duration.ofSeconds(2),
            Duration.ofMillis(300));
    LeaderElectionPolicy policy2 =
        new LeaderElectionPolicy(
            true,
            LEASE_NAME,
            namespace,
            "replica-2",
            Duration.ofSeconds(3),
            Duration.ofSeconds(2),
            Duration.ofMillis(300));

    Operator operator1 = ReleaseGuardOperator.createOperator(client, policy1);
    Operator operator2 = ReleaseGuardOperator.createOperator(client, policy2);
    operator1.register(
        new ReleasePlanReconciler(new ReleasePlanPlanner(), new KubernetesReconciliationRuntime()));
    operator2.register(
        new ReleasePlanReconciler(new ReleasePlanPlanner(), new KubernetesReconciliationRuntime()));

    try {
      // 1. Start replica-1: it must acquire the lease as the sole active leader
      operator1.start();
      await(
          () -> {
            Lease lease = getLease(namespace);
            return lease != null
                && lease.getSpec() != null
                && "replica-1".equals(lease.getSpec().getHolderIdentity());
          },
          Duration.ofSeconds(10),
          () -> "replica-1 did not acquire the lease. Current lease: " + getLease(namespace));

      Lease initialLease = getLease(namespace);
      assertNotNull(initialLease);
      assertEquals("replica-1", initialLease.getSpec().getHolderIdentity());
      assertEquals(3, initialLease.getSpec().getLeaseDurationSeconds());

      // 2. Start replica-2: it must remain as standby without seizing the active lease
      operator2.start();
      Thread.sleep(1000);

      Lease concurrentLease = getLease(namespace);
      assertNotNull(concurrentLease);
      assertEquals(
          "replica-1",
          concurrentLease.getSpec().getHolderIdentity(),
          "Standby replica-2 must not take over while replica-1 is active");

      // 3. Stop replica-1: replica-2 must observe lease expiration and failover cleanly
      operator1.stop();

      await(
          () -> {
            Lease lease = getLease(namespace);
            return lease != null
                && lease.getSpec() != null
                && "replica-2".equals(lease.getSpec().getHolderIdentity());
          },
          Duration.ofSeconds(15),
          () ->
              "replica-2 did not failover as leader after replica-1 stopped. Lease: "
                  + getLease(namespace));

      Lease failoverLease = getLease(namespace);
      assertNotNull(failoverLease);
      assertEquals("replica-2", failoverLease.getSpec().getHolderIdentity());
    } finally {
      try {
        operator1.stop();
      } catch (Exception ignored) {
      }
      try {
        operator2.stop();
      } catch (Exception ignored) {
      }
      client.resources(Lease.class).inNamespace(namespace).withName(LEASE_NAME).delete();
    }
  }

  @Test
  void disabledLeaderElectionDoesNotCreateLease() throws Exception {
    String namespace = "leader-disabled";
    prepareNamespace(namespace);
    prepareCrds();

    Operator operator = ReleaseGuardOperator.createOperator(client, LeaderElectionPolicy.DISABLED);
    operator.register(
        new ReleasePlanReconciler(new ReleasePlanPlanner(), new KubernetesReconciliationRuntime()));

    try {
      operator.start();
      Thread.sleep(500);
      assertNull(
          getLease(namespace),
          "When leader election is disabled, no coordination Lease should be created");
    } finally {
      try {
        operator.stop();
      } catch (Exception ignored) {
      }
    }
  }

  private static Lease getLease(String namespace) {
    return client.resources(Lease.class).inNamespace(namespace).withName(LEASE_NAME).get();
  }

  private static void prepareNamespace(String namespace) {
    client
        .namespaces()
        .resource(
            new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build())
        .serverSideApply();
  }

  private static void prepareCrds() {

    var releasePlanCrd =
        CustomResourceDefinitionContext.v1CRDFromCustomResourceType(ReleasePlan.class).build();
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

    var releaseEventCrd =
        CustomResourceDefinitionContext.v1CRDFromCustomResourceType(ReleaseEvent.class).build();
    releaseEventCrd
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
        .resource(releaseEventCrd)
        .serverSideApply();
    client
        .load(new ByteArrayInputStream(ROLLOUT_CRD.getBytes(StandardCharsets.UTF_8)))
        .items()
        .forEach(resource -> client.resource(resource).serverSideApply());
    client
        .load(new ByteArrayInputStream(ANALYSIS_RUN_CRD.getBytes(StandardCharsets.UTF_8)))
        .items()
        .forEach(resource -> client.resource(resource).serverSideApply());
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
    throw new AssertionError("Timed out waiting for condition: " + diagnostics.get());
  }
}
