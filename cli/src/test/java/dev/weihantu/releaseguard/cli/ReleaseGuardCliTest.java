package dev.weihantu.releaseguard.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseGuardCliTest {
  private static final KubernetesSerialization KUBERNETES = new KubernetesSerialization();
  private final Path examples =
      Path.of(System.getProperty("releaseguard.repositoryRoot"), "config", "examples");

  @TempDir Path temporaryDirectory;

  @Test
  void validatesPlanWithSiblingPolicyInHumanAndJsonFormats() {
    Path plan = examples.resolve("releaseplan-checkout.yaml");

    CommandResult human = run("plan", "validate", "-f", plan.toString());
    CommandResult json = run("plan", "validate", "-f", plan.toString(), "-o", "json");

    assertEquals(ExitCode.SUCCESS, human.exitCode());
    assertEquals("valid: demo/checkout (1 accepted policies)\n", human.standardOutput());
    Map<?, ?> result = KUBERNETES.unmarshal(json.standardOutput(), Map.class);
    assertEquals(Boolean.TRUE, result.get("valid"));
    assertEquals(1, result.get("acceptedPolicyCount"));
    assertEquals("demo/checkout", result.get("plan"));
  }

  @Test
  void rejectsInvalidPlanWithDeterministicRequirementErrors() throws IOException {
    Path plan = copyExample("releaseplan-checkout.yaml", "releaseplan.yaml");
    copyExample("metricpolicy-http-errors.yaml", "metricpolicy.yaml");
    String invalid = Files.readString(plan).replaceFirst("@sha256:[0-9a-f]{64}", ":latest");
    Files.writeString(plan, invalid);

    CommandResult result = run("plan", "validate", "-f", plan.toString());

    assertEquals(ExitCode.VALIDATION, result.exitCode());
    assertTrue(result.standardOutput().startsWith("invalid: demo/checkout\n"));
    assertTrue(result.standardOutput().contains("[RG-PLAN-002]"));
    assertTrue(result.standardOutput().contains("[RG-PLAN-003]"));
    assertEquals("", result.standardError());
  }

  @Test
  void resolvesExplicitPolicyOutsideThePlanDirectory() throws IOException {
    Path plan = copyExample("releaseplan-checkout.yaml", "releaseplan.yaml");
    Path policy = examples.resolve("metricpolicy-http-errors.yaml");

    CommandResult result = run("plan", "validate", "-f", plan.toString(), "-p", policy.toString());

    assertEquals(ExitCode.SUCCESS, result.exitCode());
    assertTrue(result.standardOutput().contains("1 accepted policies"));
  }

  @Test
  void rejectsAPlanWhenItsReferencedPolicyIsInvalid() throws IOException {
    Path plan = copyExample("releaseplan-checkout.yaml", "releaseplan.yaml");
    Path policy = copyExample("metricpolicy-http-errors.yaml", "metricpolicy.yaml");
    Files.writeString(
        policy, Files.readString(policy).replace("minimumSamples: 100", "minimumSamples: 0"));

    CommandResult result = run("plan", "validate", "-f", plan.toString());

    assertEquals(ExitCode.VALIDATION, result.exitCode());
    assertTrue(result.standardOutput().contains("policies[http-error-ratio].spec.minimumSamples"));
    assertTrue(result.standardOutput().contains("referenced policy is not accepted"));
  }

  @Test
  void rendersTheOperatorResourceSetDeterministicallyWithExplicitIdentity() {
    Path plan = examples.resolve("releaseplan-checkout.yaml");
    String[] arguments = {
      "plan",
      "render",
      "-f",
      plan.toString(),
      "--uid",
      "11111111-1111-1111-1111-111111111111",
      "--generation",
      "7"
    };

    CommandResult first = run(arguments);
    CommandResult second = run(arguments);

    assertEquals(ExitCode.SUCCESS, first.exitCode());
    assertEquals(first.standardOutput(), second.standardOutput());
    assertEquals(
        4L,
        java.util.regex.Pattern.compile("(?m)^kind: ")
            .matcher(first.standardOutput())
            .results()
            .count());
    assertTrue(first.standardOutput().contains("kind: \"Rollout\""));
    assertTrue(first.standardOutput().contains("delivery.weihantu.dev/plan-generation: \"7\""));
    assertTrue(first.standardOutput().contains("uid: \"11111111-1111-1111-1111-111111111111\""));
    assertFalse(first.standardOutput().contains("---\n---"));
    assertFalse(first.standardError().contains("warning:"));
  }

  @Test
  void usesAStablePreviewUidAndWarnsBeforeRenderedOutputIsApplied() {
    Path plan = examples.resolve("releaseplan-checkout.yaml");

    CommandResult first = run("plan", "render", "-f", plan.toString(), "-o", "json");
    CommandResult second = run("plan", "render", "-f", plan.toString(), "-o", "json");

    assertEquals(ExitCode.SUCCESS, first.exitCode());
    assertEquals(first.standardOutput(), second.standardOutput());
    List<?> resources = KUBERNETES.unmarshal(first.standardOutput(), List.class);
    assertEquals(4, resources.size());
    assertTrue(first.standardOutput().contains("offline-"));
    assertTrue(first.standardError().contains("deterministic offline UID"));
  }

  @Test
  void returnsUsageAndInputExitCodes() {
    CommandResult usage = run("plan", "unknown");
    CommandResult input =
        run("plan", "validate", "-f", temporaryDirectory.resolve("missing.yaml").toString());

    assertEquals(ExitCode.USAGE, usage.exitCode());
    assertTrue(usage.standardError().contains("unknown plan command"));
    assertEquals(ExitCode.INPUT, input.exitCode());
    assertTrue(input.standardError().contains("manifest is not a readable file"));
  }

  private Path copyExample(String sourceName, String targetName) throws IOException {
    return Files.copy(examples.resolve(sourceName), temporaryDirectory.resolve(targetName));
  }

  private static CommandResult run(String... arguments) {
    ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
    ByteArrayOutputStream standardError = new ByteArrayOutputStream();
    int exitCode =
        new ReleaseGuardCli()
            .run(
                arguments,
                new PrintWriter(standardOutput, true, StandardCharsets.UTF_8),
                new PrintWriter(standardError, true, StandardCharsets.UTF_8));
    return new CommandResult(
        exitCode,
        standardOutput.toString(StandardCharsets.UTF_8),
        standardError.toString(StandardCharsets.UTF_8));
  }

  private record CommandResult(int exitCode, String standardOutput, String standardError) {}
}
