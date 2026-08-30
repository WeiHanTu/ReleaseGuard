package dev.weihantu.releaseguard.api.v1alpha1;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.validation.MetricPolicyValidator;
import dev.weihantu.releaseguard.api.validation.ReleaseActionValidator;
import dev.weihantu.releaseguard.api.validation.ReleaseEventValidator;
import dev.weihantu.releaseguard.api.validation.ReleasePlanValidationContext;
import dev.weihantu.releaseguard.api.validation.ReleasePlanValidator;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExampleResourcesTest {
  private final Path examples =
      Path.of(System.getProperty("releaseguard.repositoryRoot"), "config", "examples");

  @Test
  void committedExamplesDeserializeAndPassSemanticValidation() throws IOException {
    MetricPolicy policy = read("metricpolicy-http-errors.yaml", MetricPolicy.class);
    MetricPolicy latencyPolicy = read("metricpolicy-request-p95.yaml", MetricPolicy.class);
    ReleasePlan plan = read("releaseplan-checkout.yaml", ReleasePlan.class);
    ReleaseAction action = read("releaseaction-abort.yaml", ReleaseAction.class);
    ReleaseEvent event = read("releaseevent-step-passed.yaml", ReleaseEvent.class);

    assertTrue(new MetricPolicyValidator().validate(policy.getSpec()).isValid());
    assertTrue(new MetricPolicyValidator().validate(latencyPolicy.getSpec()).isValid());
    assertTrue(
        new ReleasePlanValidator()
            .validate(
                plan.getSpec(),
                ReleasePlanValidationContext.releaseEnvironment(
                    plan.getMetadata().getNamespace(),
                    Map.of(policy.getMetadata().getName(), policy.getSpec())))
            .isValid());
    assertTrue(new ReleaseActionValidator().validate(action.getSpec()).isValid());
    assertTrue(new ReleaseEventValidator().validate(event.getSpec()).isValid());
  }

  private <T> T read(String name, Class<T> type) throws IOException {
    return Serialization.unmarshal(Files.readString(examples.resolve(name)), type);
  }
}
