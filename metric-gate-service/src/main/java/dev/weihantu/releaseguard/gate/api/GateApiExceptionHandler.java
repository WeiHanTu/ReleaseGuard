package dev.weihantu.releaseguard.gate.api;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GateApiExceptionHandler {
  @ExceptionHandler(InvalidEvaluationRequestException.class)
  ResponseEntity<ApiError> invalidEvaluation(InvalidEvaluationRequestException exception) {
    return ResponseEntity.badRequest()
        .body(new ApiError("InvalidEvaluationRequest", exception.violations()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> malformedJson() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new ApiError(
                "MalformedJson",
                List.of(
                    new InvalidEvaluationRequestException.Violation(
                        "request", "request body must be valid JSON"))));
  }

  record ApiError(String code, List<InvalidEvaluationRequestException.Violation> violations) {
    ApiError {
      violations = List.copyOf(violations);
    }
  }
}
