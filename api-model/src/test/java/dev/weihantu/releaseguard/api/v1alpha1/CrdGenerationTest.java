package dev.weihantu.releaseguard.api.v1alpha1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrdGenerationTest {
  @Test
  void generatesFourStructuralCrdsWithStatusAndPrinterColumns() throws IOException {
    Path directory = Path.of(System.getProperty("releaseguard.generatedCrdDirectory"));
    List<Path> crds;
    try (var files = Files.list(directory)) {
      crds = files.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList();
    }

    assertEquals(4, crds.size());
    for (Path crd : crds) {
      String yaml = Files.readString(crd);
      assertTrue(yaml.contains("openAPIV3Schema:"), crd::toString);
      assertTrue(yaml.contains("subresources:\n      status: {}"), crd::toString);
      assertTrue(yaml.contains("additionalPrinterColumns:"), crd::toString);
      assertTrue(yaml.contains("name: \"v1alpha1\""), crd::toString);
      assertTrue(yaml.contains("required:\n        - \"spec\""), crd::toString);
    }
  }

  @Test
  void scopesPoliciesToClusterAndOtherResourcesToNamespaces() throws IOException {
    Path directory = Path.of(System.getProperty("releaseguard.generatedCrdDirectory"));

    String policy =
        Files.readString(directory.resolve("metricpolicies.delivery.weihantu.dev-v1.yml"));
    assertTrue(policy.contains("scope: \"Cluster\""));
    for (String resource : List.of("releaseplans", "releaseactions", "releaseevents")) {
      String yaml = Files.readString(directory.resolve(resource + ".delivery.weihantu.dev-v1.yml"));
      assertTrue(yaml.contains("scope: \"Namespaced\""));
    }
  }
}
