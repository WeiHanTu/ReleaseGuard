package dev.weihantu.releaseguard.history.service;

public class ActionSubmissionException extends RuntimeException {
  public ActionSubmissionException(String message) {
    super(message);
  }

  public ActionSubmissionException(String message, Throwable cause) {
    super(message, cause);
  }
}
