package dev.weihantu.releaseguard.history.api;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Health endpoints adhering to RG-HTTP-008 and RG-HTTP-009. */
@RestController
public class HealthController {

  private final JdbcTemplate jdbcTemplate;

  public HealthController(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  /**
   * Process liveness (RG-HTTP-008). MUST NOT fail because an external dependency is unavailable.
   */
  @GetMapping("/livez")
  public ResponseEntity<Map<String, String>> livez() {
    return ResponseEntity.ok(Map.of("status", "UP"));
  }

  /** Serving readiness (RG-HTTP-009). Verifies database connectivity. */
  @GetMapping("/readyz")
  public ResponseEntity<Map<String, String>> readyz() {
    try {
      Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
      if (result != null && result == 1) {
        return ResponseEntity.ok(Map.of("status", "UP", "database", "HEALTHY"));
      }
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("status", "DOWN", "database", "UNHEALTHY", "error", e.getMessage()));
    }
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Map.of("status", "DOWN", "database", "UNHEALTHY"));
  }
}
