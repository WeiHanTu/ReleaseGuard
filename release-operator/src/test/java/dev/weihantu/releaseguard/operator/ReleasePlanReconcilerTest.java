package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicySpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus.Phase;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleasePlanReconcilerTest {
  @Test
  void patchesValidationStatusThenAppliesTheCompleteDesiredSet() {
    RecordingRuntime runtime = new RecordingRuntime();
    ReleasePlanReconciler reconciler = new ReleasePlanReconciler(new ReleasePlanPlanner(), runtime);
    ReleasePlan plan = OperatorTestFixtures.plan(1);

    var validationControl = reconciler.reconcile(plan, null);

    assertTrue(validationControl.isPatchStatus());
    assertTrue(validationControl.getScheduleDelay().isPresent());
    assertEquals(Phase.Validating, plan.getStatus().phase());
    assertTrue(runtime.applied.isEmpty());

    var applyControl = reconciler.reconcile(plan, null);

    assertTrue(applyControl.isPatchStatus());
    assertFalse(applyControl.getScheduleDelay().isPresent());
    assertEquals(Phase.Bootstrapping, plan.getStatus().phase());
    assertEquals(4, runtime.applied.size());
  }

  @Test
  void applyFailureDoesNotPublishAFalseProgressStatus() {
    ReconciliationRuntime runtime =
        new RecordingRuntime() {
          @Override
          public void apply(List<HasMetadata> resources, Context<ReleasePlan> context) {
            throw new IllegalStateException("Kubernetes API unavailable");
          }
        };
    ReleasePlanReconciler reconciler = new ReleasePlanReconciler(new ReleasePlanPlanner(), runtime);
    ReleasePlan plan = OperatorTestFixtures.plan(2);
    reconciler.reconcile(plan, null);

    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(plan, null));
    assertEquals(Phase.Validating, plan.getStatus().phase());
  }

  private static class RecordingRuntime implements ReconciliationRuntime {
    private final List<HasMetadata> applied = new ArrayList<>();

    @Override
    public Map<String, MetricPolicySpec> acceptedPolicies(
        ReleasePlan plan, Context<ReleasePlan> context) {
      return OperatorTestFixtures.policies();
    }

    @Override
    public void apply(List<HasMetadata> resources, Context<ReleasePlan> context) {
      applied.clear();
      applied.addAll(resources);
    }
  }
}
