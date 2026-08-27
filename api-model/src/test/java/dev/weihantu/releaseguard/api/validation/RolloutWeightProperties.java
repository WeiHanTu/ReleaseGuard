package dev.weihantu.releaseguard.api.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class RolloutWeightProperties {
  private final ReleasePlanValidator validator = new ReleasePlanValidator();
  private final ReleasePlanValidationContext context =
      ReleasePlanValidationContext.releaseEnvironment(
          "demo", Map.of("http-errors", TestFixtures.policy()));

  @Property(tries = 250)
  void everyStrictlyIncreasingSequenceEndingAtOneHundredIsAccepted(
      @ForAll("validWeights") List<Integer> weights) {
    List<ReleasePlanSpec.Step> steps = new ArrayList<>();
    for (int index = 0; index < weights.size(); index++) {
      steps.add(
          new ReleasePlanSpec.Step(weights.get(index), index == weights.size() - 1 ? null : "10s"));
    }

    ValidationResult result = validator.validate(TestFixtures.withSteps(steps), context);

    assertTrue(
        result.errors().stream().noneMatch(error -> error.requirement().equals("RG-PLAN-005")),
        () -> "valid weights rejected: " + weights + " -> " + result.errors());
  }

  @Provide
  Arbitrary<List<Integer>> validWeights() {
    return Arbitraries.integers()
        .between(1, 99)
        .list()
        .uniqueElements()
        .ofMinSize(1)
        .ofMaxSize(12)
        .map(
            values -> {
              List<Integer> sorted = new ArrayList<>(values);
              sorted.sort(Integer::compareTo);
              sorted.add(100);
              return List.copyOf(sorted);
            });
  }
}
