package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReleasePlanEventSourcesTest {
  @Test
  void mapsManagedChildrenBackToTheirReleasePlan() {
    var service =
        new ServiceBuilder()
            .withNewMetadata()
            .withName("checkout-stable")
            .withNamespace("demo")
            .withLabels(
                Map.of(
                    ReleaseResourceRenderer.MANAGED_BY_LABEL,
                    "releaseguard",
                    ReleaseResourceRenderer.PLAN_NAME_LABEL,
                    "checkout"))
            .endMetadata()
            .build();

    assertEquals(
        Set.of(new ResourceID("checkout", "demo")),
        ReleasePlanEventSources.primaryResourceIDs(service));
  }

  @Test
  void ignoresChildrenWithoutACompletePrimaryIdentity() {
    var missingPlan =
        new ServiceBuilder()
            .withNewMetadata()
            .withName("checkout-stable")
            .withNamespace("demo")
            .endMetadata()
            .build();
    var missingNamespace =
        new ServiceBuilder()
            .withNewMetadata()
            .withName("checkout-stable")
            .addToLabels(ReleaseResourceRenderer.PLAN_NAME_LABEL, "checkout")
            .endMetadata()
            .build();

    assertTrue(ReleasePlanEventSources.primaryResourceIDs(missingPlan).isEmpty());
    assertTrue(ReleasePlanEventSources.primaryResourceIDs(missingNamespace).isEmpty());
  }
}
