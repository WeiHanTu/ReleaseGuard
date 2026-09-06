package dev.weihantu.releaseguard.operator;

public final class EventPublishException extends RuntimeException {
  public EventPublishException(String message) {
    super(message);
  }

  public EventPublishException(String message, Throwable cause) {
    super(message, cause);
  }
}
