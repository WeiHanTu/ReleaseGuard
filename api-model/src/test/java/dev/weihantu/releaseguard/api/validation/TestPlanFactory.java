package dev.weihantu.releaseguard.api.validation;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

public final class TestPlanFactory {
  private TestPlanFactory() {}

  public static ReleasePlan releasePlanResource() {
    ReleasePlan plan = new ReleasePlan();
    plan.setMetadata(new ObjectMetaBuilder().withName("checkout").withNamespace("demo").build());
    plan.setSpec(TestFixtures.plan());
    return plan;
  }
}
