package dev.weihantu.releaseguard.history.api;

public class MutationForbiddenException extends RuntimeException {

  public MutationForbiddenException(String message) {
    super(message);
  }
}
