package dev.weihantu.releaseguard.history.api;

public class ProjectionUnavailableException extends RuntimeException {

  public ProjectionUnavailableException(String message) {
    super(message);
  }

  public ProjectionUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
