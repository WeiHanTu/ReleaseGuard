package dev.weihantu.releaseguard.history.api;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ReleaseNotFoundException.class)
  public ResponseEntity<ApiError> handleReleaseNotFound(ReleaseNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError("ReleaseNotFound", ex.getMessage()));
  }

  @ExceptionHandler(ProjectionUnavailableException.class)
  public ResponseEntity<ApiError> handleProjectionUnavailable(ProjectionUnavailableException ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ApiError("ProjectionUnavailable", ex.getMessage()));
  }

  @ExceptionHandler(InvalidActionException.class)
  public ResponseEntity<ApiError> handleInvalidAction(InvalidActionException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ApiError("InvalidAction", ex.getMessage()));
  }

  @ExceptionHandler(ReleaseHistoryController.MissingAuthenticationException.class)
  public ResponseEntity<ApiError> handleMissingAuthentication(
      ReleaseHistoryController.MissingAuthenticationException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            new ApiError(
                "MissingAuthentication",
                ex.getMessage(),
                List.of(
                    new ApiError.FieldViolation("header.X-Actor-Identity", "Header is missing"))));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
    String headerName = ex.getHeaderName();
    if ("Idempotency-Key".equalsIgnoreCase(headerName)) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(
              new ApiError(
                  "MissingIdempotencyKey",
                  "Idempotency-Key header is required for mutating requests",
                  List.of(
                      new ApiError.FieldViolation("header.Idempotency-Key", "Header is missing"))));
    }
    if ("X-Actor-Identity".equalsIgnoreCase(headerName)
        || "Authorization".equalsIgnoreCase(headerName)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(
              new ApiError(
                  "MissingAuthentication",
                  "Authenticated identity header is required for mutating requests",
                  List.of(
                      new ApiError.FieldViolation(
                          "header.X-Actor-Identity", "Header is missing"))));
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new ApiError(
                "MissingRequiredHeader",
                "Required request header is missing: " + headerName,
                List.of(new ApiError.FieldViolation("header." + headerName, "Header is missing"))));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleMalformedJson(HttpMessageNotReadableException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new ApiError(
                "MalformedJson",
                "Request body is missing or cannot be parsed as JSON",
                List.of(new ApiError.FieldViolation("body", "Invalid JSON format"))));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ApiError("InvalidArgument", ex.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleGenericException(Exception ex) {
    log.error("Unhandled error processing request: {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("InternalServerError", "An unexpected error occurred"));
  }
}
