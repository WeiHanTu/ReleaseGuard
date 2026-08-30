package dev.weihantu.releaseguard.cli;

import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicy;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ManifestLoader {
  private static final String API_VERSION = "delivery.weihantu.dev/v1alpha1";
  private static final KubernetesSerialization KUBERNETES = new KubernetesSerialization();
  private static final Pattern DOCUMENT_SEPARATOR = Pattern.compile("(?m)^---[ \\t]*(?:#.*)?\\r?$");

  LoadedPlan load(Path planFile, List<Path> policyFiles) throws CliException {
    Path normalizedPlan = normalizeReadableFile(planFile);
    List<Document> planDocuments = documents(normalizedPlan, true);
    List<ReleasePlan> plans = resources(planDocuments, "ReleasePlan", ReleasePlan.class);
    if (plans.size() != 1) {
      throw new CliException(
          ExitCode.INPUT,
          normalizedPlan + " must contain exactly one ReleasePlan; found " + plans.size());
    }

    Set<Path> candidateFiles = new LinkedHashSet<>();
    candidateFiles.add(normalizedPlan);
    Path parent = normalizedPlan.getParent();
    if (parent != null) {
      try (var entries = Files.list(parent)) {
        entries
            .filter(Files::isRegularFile)
            .filter(ManifestLoader::isYaml)
            .map(path -> path.toAbsolutePath().normalize())
            .sorted()
            .forEach(candidateFiles::add);
      } catch (IOException exception) {
        throw new CliException(
            ExitCode.INPUT,
            "cannot inspect policy directory " + parent + ": " + exception.getMessage());
      }
    }
    for (Path policyFile : policyFiles) {
      candidateFiles.add(normalizeReadableFile(policyFile));
    }

    Map<String, MetricPolicy> policies = new LinkedHashMap<>();
    Map<String, Path> policySources = new LinkedHashMap<>();
    for (Path candidate : candidateFiles) {
      boolean strict = candidate.equals(normalizedPlan) || containsPath(policyFiles, candidate);
      List<Document> candidateDocuments =
          candidate.equals(normalizedPlan) ? planDocuments : documents(candidate, strict);
      List<MetricPolicy> discovered =
          resources(candidateDocuments, "MetricPolicy", MetricPolicy.class);
      if (strict && !candidate.equals(normalizedPlan) && discovered.isEmpty()) {
        throw new CliException(ExitCode.INPUT, candidate + " contains no MetricPolicy");
      }
      for (MetricPolicy policy : discovered) {
        String name = policy.getMetadata() == null ? null : policy.getMetadata().getName();
        if (name == null || name.isBlank()) {
          throw new CliException(
              ExitCode.INPUT, candidate + " contains a MetricPolicy without a name");
        }
        Path previous = policySources.putIfAbsent(name, candidate);
        if (previous != null) {
          throw new CliException(
              ExitCode.INPUT,
              "MetricPolicy " + name + " is defined in both " + previous + " and " + candidate);
        }
        policies.put(name, policy);
      }
    }
    return new LoadedPlan(plans.getFirst(), Map.copyOf(policies));
  }

  private static boolean containsPath(List<Path> paths, Path candidate) {
    return paths.stream()
        .map(path -> path.toAbsolutePath().normalize())
        .anyMatch(candidate::equals);
  }

  private static Path normalizeReadableFile(Path path) throws CliException {
    Path normalized = path.toAbsolutePath().normalize();
    if (!Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
      throw new CliException(ExitCode.INPUT, "manifest is not a readable file: " + normalized);
    }
    return normalized;
  }

  private static boolean isYaml(Path path) {
    String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    return name.endsWith(".yaml") || name.endsWith(".yml");
  }

  private static List<Document> documents(Path path, boolean strict) throws CliException {
    String source;
    try {
      source = Files.readString(path);
    } catch (IOException exception) {
      if (!strict) {
        return List.of();
      }
      throw new CliException(ExitCode.INPUT, "cannot read " + path + ": " + exception.getMessage());
    }

    List<Document> documents = new ArrayList<>();
    for (String content : DOCUMENT_SEPARATOR.split(source)) {
      if (content.isBlank()) {
        continue;
      }
      try {
        Map<?, ?> root = KUBERNETES.unmarshal(content, Map.class);
        if (root == null) {
          continue;
        }
        documents.add(new Document(path, content, value(root, "apiVersion"), value(root, "kind")));
      } catch (RuntimeException exception) {
        if (strict) {
          throw new CliException(
              ExitCode.INPUT, "cannot parse " + path + ": " + exception.getMessage());
        }
        return List.of();
      }
    }
    return List.copyOf(documents);
  }

  private static String value(Map<?, ?> document, String key) {
    Object value = document.get(key);
    return value == null ? "" : value.toString();
  }

  private static <T> List<T> resources(List<Document> documents, String kind, Class<T> type)
      throws CliException {
    List<T> resources = new ArrayList<>();
    for (Document document :
        documents.stream().sorted(Comparator.comparing(item -> item.path().toString())).toList()) {
      if (!kind.equals(document.kind())) {
        continue;
      }
      if (!API_VERSION.equals(document.apiVersion())) {
        throw new CliException(
            ExitCode.INPUT,
            document.path()
                + " contains "
                + kind
                + " with unsupported apiVersion "
                + document.apiVersion());
      }
      try {
        resources.add(KUBERNETES.unmarshal(document.content(), type));
      } catch (RuntimeException exception) {
        throw new CliException(
            ExitCode.INPUT,
            "cannot decode " + kind + " in " + document.path() + ": " + exception.getMessage());
      }
    }
    return List.copyOf(resources);
  }

  record LoadedPlan(ReleasePlan plan, Map<String, MetricPolicy> policies) {}

  private record Document(Path path, String content, String apiVersion, String kind) {}
}
