package dev.weihantu.releaseguard.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiContractTest {

  @Test
  @DisplayName(
      "RG-HTTP-001: OpenAPI document defines all required external endpoints and error schemas")
  void testOpenApiContract() throws Exception {
    File committedDoc = new File("../docs/openapi.yaml");
    if (!committedDoc.exists()) {
      committedDoc = new File("docs/openapi.yaml");
    }
    File packagedDoc = new File("src/main/resources/openapi.yaml");

    assertTrue(committedDoc.exists(), "docs/openapi.yaml must exist (RG-HTTP-001)");
    assertTrue(packagedDoc.exists(), "src/main/resources/openapi.yaml must exist");

    byte[] committedBytes = Files.readAllBytes(committedDoc.toPath());
    byte[] packagedBytes = Files.readAllBytes(packagedDoc.toPath());
    assertArrayEquals(
        committedBytes,
        packagedBytes,
        "docs/openapi.yaml and packaged openapi.yaml must be byte-identical");

    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    @SuppressWarnings("unchecked")
    Map<String, Object> openApi = yamlMapper.readValue(committedDoc, Map.class);

    assertNotNull(openApi.get("openapi"), "Must specify openapi version");

    @SuppressWarnings("unchecked")
    Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
    assertNotNull(paths, "Must specify paths");

    Set<String> requiredPaths =
        Set.of(
            "/v1/plans:validate",
            "/v1/plans:render",
            "/v1/releases/{namespace}/{name}",
            "/v1/releases/{namespace}/{name}/events",
            "/v1/releases/{namespace}/{name}/evidence",
            "/v1/releases/{namespace}/{name}:pause",
            "/v1/releases/{namespace}/{name}:promote",
            "/v1/releases/{namespace}/{name}:abort",
            "/v1/releases/{namespace}/{name}:retryAnalysis",
            "/livez",
            "/readyz");

    for (String path : requiredPaths) {
      assertTrue(paths.containsKey(path), "OpenAPI must define path: " + path);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> components = (Map<String, Object>) openApi.get("components");
    assertNotNull(components, "Must specify components");

    @SuppressWarnings("unchecked")
    Map<String, Object> securitySchemes = (Map<String, Object>) components.get("securitySchemes");
    assertNotNull(securitySchemes, "Mutations must define authenticated identity");
    assertTrue(securitySchemes.containsKey("bearerAuth"), "Must define OIDC bearer JWT auth");

    for (String path :
        Set.of(
            "/v1/releases/{namespace}/{name}:pause",
            "/v1/releases/{namespace}/{name}:promote",
            "/v1/releases/{namespace}/{name}:abort",
            "/v1/releases/{namespace}/{name}:retryAnalysis")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> operation = (Map<String, Object>) paths.get(path);
      @SuppressWarnings("unchecked")
      Map<String, Object> post = (Map<String, Object>) operation.get("post");
      assertTrue(post.containsKey("security"), "Mutation must require bearerAuth: " + path);
      @SuppressWarnings("unchecked")
      Map<String, Object> responses = (Map<String, Object>) post.get("responses");
      assertTrue(responses.containsKey("401"), "Mutation must document authentication: " + path);
      assertTrue(responses.containsKey("403"), "Mutation must document authorization: " + path);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
    assertNotNull(schemas, "Must specify schemas");

    assertTrue(schemas.containsKey("ApiError"), "Schemas must include ApiError (RG-HTTP-005)");
    assertTrue(schemas.containsKey("FieldViolation"), "Schemas must include FieldViolation");
    assertTrue(schemas.containsKey("ReleaseResponse"), "Schemas must include ReleaseResponse");
    assertTrue(schemas.containsKey("EventListResponse"), "Schemas must include EventListResponse");
  }
}
