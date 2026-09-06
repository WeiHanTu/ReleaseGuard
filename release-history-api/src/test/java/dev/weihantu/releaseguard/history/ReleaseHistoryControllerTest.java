package dev.weihantu.releaseguard.history;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.weihantu.releaseguard.api.v1alpha1.EventEnvelope;
import dev.weihantu.releaseguard.history.projection.AuditProjectionService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReleaseHistoryControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AuditProjectionService projectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanDatabase() {
    jdbcTemplate.update("DELETE FROM quarantined_events");
    jdbcTemplate.update("DELETE FROM events");
    jdbcTemplate.update("DELETE FROM releases");
    jdbcTemplate.update("DELETE FROM projection_checkpoints");
  }

  @Test
  @DisplayName(
      "GET /v1/releases/{namespace}/{name} returns release projection and freshness headers")
  void testGetReleaseSuccess() throws Exception {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    EventEnvelope event =
        new EventEnvelope(
            1,
            "evt-http-1",
            "release.progressing",
            "uid-http:1",
            "uid-http",
            1L,
            1L,
            "demo",
            "frontend",
            "sha-abc",
            null,
            now.toString(),
            null,
            Map.of("step", 1));
    projectionService.project(event, "deployment-events.v1", 0, 100L);

    mockMvc
        .perform(get("/v1/releases/demo/frontend"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Projected-Through", now.toString()))
        .andExpect(header().string("X-Kafka-Offset", "100"))
        .andExpect(jsonPath("$.release.name", is("frontend")))
        .andExpect(jsonPath("$.release.namespace", is("demo")))
        .andExpect(jsonPath("$.release.phase", is("Progressing")))
        .andExpect(jsonPath("$.release.currentStep", is(1)))
        .andExpect(jsonPath("$.freshness.lastKafkaOffsets.0", is(100)))
        .andExpect(jsonPath("$.freshness.stale", is(false)));
  }

  @Test
  @DisplayName(
      "GET /v1/releases/{namespace}/{name} returns 404 with ReleaseNotFound when release unknown")
  void testGetReleaseNotFound() throws Exception {
    mockMvc
        .perform(get("/v1/releases/nonexistent/service"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code", is("ReleaseNotFound")))
        .andExpect(jsonPath("$.message", containsString("nonexistent/service")));
  }

  @Test
  @DisplayName("GET /v1/releases/{namespace}/{name}/events returns paginated audit history")
  void testGetEventsCursorPagination() throws Exception {
    String releaseId = "uid-events:1";
    for (int i = 1; i <= 3; i++) {
      EventEnvelope event =
          new EventEnvelope(
              1,
              "evt-seq-" + i,
              "release.progressing",
              releaseId,
              "uid-events",
              1L,
              (long) i,
              "demo",
              "api-gateway",
              "rev-1",
              null,
              "2026-09-06T00:0" + i + ":00Z",
              null,
              Map.of("step", i));
      projectionService.project(event, "deployment-events.v1", 0, (long) i);
    }

    // Page 1: limit 2
    mockMvc
        .perform(get("/v1/releases/demo/api-gateway/events?limit=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(2)))
        .andExpect(jsonPath("$.items[0].sequence", is(1)))
        .andExpect(jsonPath("$.items[1].sequence", is(2)))
        .andExpect(jsonPath("$.nextCursor", is("2")));

    // Page 2: cursor=2
    mockMvc
        .perform(get("/v1/releases/demo/api-gateway/events?limit=2&cursor=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].sequence", is(3)));
  }

  @Test
  @DisplayName("GET /v1/releases/{namespace}/{name}/evidence returns gate evaluation evidence")
  void testGetEvidence() throws Exception {
    String releaseId = "uid-evid:1";
    EventEnvelope e1 =
        new EventEnvelope(
            1,
            "evt-gate-eval-1",
            "gate.evaluated",
            releaseId,
            "uid-evid",
            1L,
            1L,
            "demo",
            "payments",
            "rev-1",
            null,
            "2026-09-06T00:00:00Z",
            null,
            Map.of(
                "decision",
                "Pass",
                "evidenceDigest",
                "sha256:abcd1234ef",
                "evidenceRef",
                "analysisrun/demo-payments-gate-0"));
    projectionService.project(e1, "deployment-events.v1", 0, 1L);

    mockMvc
        .perform(get("/v1/releases/demo/payments/evidence"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.namespace", is("demo")))
        .andExpect(jsonPath("$.name", is("payments")))
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].decision", is("Pass")))
        .andExpect(jsonPath("$.items[0].evidenceDigest", is("sha256:abcd1234ef")));
  }

  @Test
  @DisplayName(
      "POST /v1/releases/{namespace}/{name}:pause requires Idempotency-Key header (RG-HTTP-002)")
  void testMutatingRequestRequiresIdempotencyKey() throws Exception {
    mockMvc
        .perform(
            post("/v1/releases/demo/payments:pause")
                .header("X-Actor-Identity", "engineer@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Urgent pause\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code", is("MissingIdempotencyKey")));
  }

  @Test
  @DisplayName(
      "POST /v1/releases/{namespace}/{name}:pause requires authenticated actor (RG-HTTP-002)")
  void testMutatingRequestRequiresAuthentication() throws Exception {
    mockMvc
        .perform(
            post("/v1/releases/demo/payments:pause")
                .header("Idempotency-Key", "uuid-pause-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Urgent pause\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code", is("MissingAuthentication")));
  }

  @Test
  @DisplayName("POST /v1/releases/{namespace}/{name}:pause creates ReleaseAction when valid")
  void testMutatingRequestSucceeds() throws Exception {
    EventEnvelope event =
        new EventEnvelope(
            1,
            "evt-init",
            "release.progressing",
            "uid-act:1",
            "uid-act",
            1L,
            1L,
            "demo",
            "payments",
            "rev-1",
            null,
            "2026-09-06T00:00:00Z",
            null,
            Map.of("step", 0));
    projectionService.project(event, "deployment-events.v1", 0, 1L);

    mockMvc
        .perform(
            post("/v1/releases/demo/payments:pause")
                .header("Idempotency-Key", "uuid-pause-12345")
                .header("X-Actor-Identity", "operator@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Hold for manual review\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.type", is("Pause")))
        .andExpect(jsonPath("$.actor", is("operator@example.com")))
        .andExpect(jsonPath("$.idempotencyKey", is("uuid-pause-12345")))
        .andExpect(jsonPath("$.actionName", notNullValue()));
  }

  @Test
  @DisplayName("GET /livez and GET /readyz health probes (RG-HTTP-008 & RG-HTTP-009)")
  void testHealthProbes() throws Exception {
    mockMvc
        .perform(get("/livez"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("UP")));

    mockMvc
        .perform(get("/readyz"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("UP")))
        .andExpect(jsonPath("$.database", is("HEALTHY")));
  }
}
