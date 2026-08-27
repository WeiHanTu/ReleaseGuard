package dev.weihantu.releaseguard.api.v1alpha1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.validation.TestPlanFactory;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

class ApiSerializationTest {
  @Test
  void roundTripsRecordBasedSpecThroughFabric8Yaml() {
    ReleasePlan original = TestPlanFactory.releasePlanResource();

    String yaml = Serialization.asYaml(original);
    ReleasePlan decoded = Serialization.unmarshal(yaml, ReleasePlan.class);

    assertEquals(original.getSpec(), decoded.getSpec());
    assertEquals("checkout", decoded.getMetadata().getName());
    assertTrue(yaml.contains("delivery.weihantu.dev/v1alpha1"));
  }
}
