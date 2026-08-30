package dev.weihantu.releaseguard.gate.api;

import dev.weihantu.releaseguard.gate.core.GateEvaluationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/evaluations")
public class GateEvaluationController {
  private final GateEvaluationService service;

  public GateEvaluationController(GateEvaluationService service) {
    this.service = service;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  GateEvaluationResponse evaluate(@RequestBody GateEvaluationRequest request) {
    return service.evaluate(request);
  }
}
