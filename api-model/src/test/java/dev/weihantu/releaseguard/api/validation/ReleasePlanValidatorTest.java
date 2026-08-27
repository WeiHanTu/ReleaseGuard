package dev.weihantu.releaseguard.api.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanSpec;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleasePlanValidatorTest {
  private final ReleasePlanValidator validator = new ReleasePlanValidator();
  private final ReleasePlanValidationContext releaseContext =
      ReleasePlanValidationContext.releaseEnvironment(
          "demo", Map.of("http-errors", TestFixtures.policy()));

  @Test
  void acceptsValidReleasePlan() {
    assertTrue(validator.validate(TestFixtures.plan(), releaseContext).isValid());
  }

  @Test
  void rejectsMutableManagedImageOutsideLocalDevelopment() {
    ReleasePlanSpec invalid =
        withWorkload(
            TestFixtures.plan(),
            new ReleasePlanSpec.Workload(
                10, "app", TestFixtures.podTemplate("ghcr.io/weihantu/checkout:latest")));

    ValidationResult result = validator.validate(invalid, releaseContext);

    assertTrue(result.hasRequirement("RG-PLAN-002"));
    assertTrue(result.hasRequirement("RG-PLAN-003"));
  }

  @Test
  void permitsMutableManagedImageOnlyInExplicitLocalContext() {
    ReleasePlanSpec local =
        withWorkload(
            TestFixtures.plan(),
            new ReleasePlanSpec.Workload(10, "app", TestFixtures.podTemplate("checkout:dev")));

    assertTrue(
        validator
            .validate(
                local,
                ReleasePlanValidationContext.localDevelopment(
                    "releaseguard-local", Map.of("http-errors", TestFixtures.policy())))
            .isValid());
  }

  @Test
  void rejectsMissingOrDuplicateManagedContainer() {
    PodTemplateSpec template = TestFixtures.podTemplate(TestFixtures.IMAGE);
    template
        .getSpec()
        .getContainers()
        .add(new ContainerBuilder().withName("app").withImage(TestFixtures.IMAGE).build());
    ReleasePlanSpec invalid =
        withWorkload(TestFixtures.plan(), new ReleasePlanSpec.Workload(10, "app", template));

    assertTrue(validator.validate(invalid, releaseContext).hasRequirement("RG-PLAN-001"));
  }

  @Test
  void rejectsNonIncreasingWeightsAndMissingTerminalPromotion() {
    ReleasePlanSpec invalid =
        TestFixtures.withSteps(
            List.of(
                new ReleasePlanSpec.Step(25, "1m"),
                new ReleasePlanSpec.Step(25, "1m"),
                new ReleasePlanSpec.Step(50, null)));

    assertTrue(validator.validate(invalid, releaseContext).hasRequirement("RG-PLAN-005"));
  }

  @Test
  void rejectsNonterminalDurationOutsideTenSecondsToTwentyFourHours() {
    ReleasePlanSpec invalid =
        TestFixtures.withSteps(
            List.of(new ReleasePlanSpec.Step(5, "9s"), new ReleasePlanSpec.Step(100, null)));

    assertTrue(validator.validate(invalid, releaseContext).hasRequirement("RG-PLAN-006"));
  }

  @Test
  void rejectsUnknownPolicyAndThresholdOutsideAdministratorBounds() {
    ReleasePlanSpec unknown =
        withGates(
            TestFixtures.plan(), List.of(new ReleasePlanSpec.Gate("missing", 0.01, null, 1, 1)));
    ReleasePlanSpec outOfBounds =
        withGates(
            TestFixtures.plan(),
            List.of(new ReleasePlanSpec.Gate("http-errors", 0.50, null, 1, 1)));

    assertTrue(validator.validate(unknown, releaseContext).hasRequirement("RG-PLAN-007"));
    assertTrue(validator.validate(outOfBounds, releaseContext).hasRequirement("RG-POL-006"));
  }

  @Test
  void rejectsPlanWithoutAbsoluteBootstrapGate() {
    ReleasePlanSpec invalid =
        withGates(
            TestFixtures.plan(),
            List.of(new ReleasePlanSpec.Gate("http-errors", null, 0.01, 1, 1)));

    assertTrue(validator.validate(invalid, releaseContext).hasRequirement("RG-PLAN-013"));
  }

  @Test
  void rejectsHostAccessPrivilegeAndAddedCapabilities() {
    PodTemplateSpec template = TestFixtures.podTemplate(TestFixtures.IMAGE);
    template.getSpec().setHostNetwork(true);
    template
        .getSpec()
        .setVolumes(
            List.of(
                new VolumeBuilder()
                    .withName("host")
                    .withHostPath(new HostPathVolumeSourceBuilder().withPath("/var/run").build())
                    .build()));
    template
        .getSpec()
        .getContainers()
        .getFirst()
        .setSecurityContext(
            new io.fabric8.kubernetes.api.model.SecurityContextBuilder()
                .withPrivileged(true)
                .withNewCapabilities()
                .addToAdd("SYS_ADMIN")
                .endCapabilities()
                .build());
    ReleasePlanSpec invalid =
        withWorkload(TestFixtures.plan(), new ReleasePlanSpec.Workload(10, "app", template));

    ValidationResult result = validator.validate(invalid, releaseContext);

    assertTrue(result.hasRequirement("RG-PLAN-010"));
    assertTrue(
        result.errors().stream().filter(error -> error.requirement().equals("RG-PLAN-010")).count()
            >= 3);
  }

  @Test
  void rejectsSpecMutationDuringActiveReleaseButAllowsItAfterSuccess() {
    ReleasePlanSpec changed =
        TestFixtures.withSteps(
            List.of(new ReleasePlanSpec.Step(10, "2m"), new ReleasePlanSpec.Step(100, null)));

    assertTrue(
        validator
            .validateUpdate(
                TestFixtures.plan(), changed, ReleasePlanStatus.Phase.Progressing, releaseContext)
            .hasRequirement("RG-PLAN-011"));
    assertFalse(
        validator
            .validateUpdate(
                TestFixtures.plan(), changed, ReleasePlanStatus.Phase.Succeeded, releaseContext)
            .hasRequirement("RG-PLAN-011"));
  }

  private static ReleasePlanSpec withWorkload(
      ReleasePlanSpec source, ReleasePlanSpec.Workload workload) {
    return new ReleasePlanSpec(
        workload,
        source.network(),
        source.rollout(),
        source.failurePolicy(),
        source.deletionPolicy());
  }

  private static ReleasePlanSpec withGates(
      ReleasePlanSpec source, List<ReleasePlanSpec.Gate> gates) {
    return new ReleasePlanSpec(
        source.workload(),
        source.network(),
        new ReleasePlanSpec.Rollout(source.rollout().steps(), gates),
        source.failurePolicy(),
        source.deletionPolicy());
  }
}
