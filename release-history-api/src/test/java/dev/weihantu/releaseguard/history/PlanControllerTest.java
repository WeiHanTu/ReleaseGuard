package dev.weihantu.releaseguard.history;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.weihantu.releaseguard.api.v1alpha1.MetricPolicy;
import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlan;
import dev.weihantu.releaseguard.history.api.PlanRenderRequest;
import dev.weihantu.releaseguard.history.api.PlanValidationRequest;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanControllerTest {

  private static final KubernetesSerialization KUBERNETES = new KubernetesSerialization();

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("POST /v1/plans:validate validates plan without cluster mutation")
  void testValidatePlan() throws Exception {
    File planFile = new File("../config/examples/releaseplan-checkout.yaml");
    if (!planFile.exists()) {
      planFile = new File("config/examples/releaseplan-checkout.yaml");
    }
    File policyFile = new File("../config/examples/metricpolicy-http-errors.yaml");
    if (!policyFile.exists()) {
      policyFile = new File("config/examples/metricpolicy-http-errors.yaml");
    }

    ReleasePlan plan;
    MetricPolicy policy;
    try (var in = new FileInputStream(planFile)) {
      plan = KUBERNETES.unmarshal(in, ReleasePlan.class);
    }
    try (var in = new FileInputStream(policyFile)) {
      policy = KUBERNETES.unmarshal(in, MetricPolicy.class);
    }

    PlanValidationRequest request = new PlanValidationRequest(plan, List.of(policy));
    String jsonBody = objectMapper.writeValueAsString(request);

    mockMvc
        .perform(
            post("/v1/plans:validate").contentType(MediaType.APPLICATION_JSON).content(jsonBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid", is(true)))
        .andExpect(jsonPath("$.acceptedPoliciesCount", is(1)))
        .andExpect(jsonPath("$.errors", hasSize(0)));
  }

  @Test
  @DisplayName("POST /v1/plans:render renders generated resources without applying them")
  void testRenderPlan() throws Exception {
    File planFile = new File("../config/examples/releaseplan-checkout.yaml");
    if (!planFile.exists()) {
      planFile = new File("config/examples/releaseplan-checkout.yaml");
    }
    File policyFile = new File("../config/examples/metricpolicy-http-errors.yaml");
    if (!policyFile.exists()) {
      policyFile = new File("config/examples/metricpolicy-http-errors.yaml");
    }

    ReleasePlan plan;
    MetricPolicy policy;
    try (var in = new FileInputStream(planFile)) {
      plan = KUBERNETES.unmarshal(in, ReleasePlan.class);
    }
    try (var in = new FileInputStream(policyFile)) {
      policy = KUBERNETES.unmarshal(in, MetricPolicy.class);
    }

    PlanRenderRequest request = new PlanRenderRequest(plan, List.of(policy), "render-uid-123", 2L);
    String jsonBody = objectMapper.writeValueAsString(request);

    mockMvc
        .perform(post("/v1/plans:render").contentType(MediaType.APPLICATION_JSON).content(jsonBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetName", is("checkout")))
        .andExpect(jsonPath("$.namespace", is("demo")))
        .andExpect(jsonPath("$.uid", is("render-uid-123")))
        .andExpect(jsonPath("$.generation", is(2)))
        .andExpect(jsonPath("$.generatedResources", hasSize(5)));
  }
}
