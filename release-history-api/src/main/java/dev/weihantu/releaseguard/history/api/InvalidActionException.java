package dev.weihantu.releaseguard.history.api;

public class InvalidActionException extends RuntimeException {

  public InvalidActionException(String message) {
    super(message);
  }
}
