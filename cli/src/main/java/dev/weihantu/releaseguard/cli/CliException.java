package dev.weihantu.releaseguard.cli;

final class CliException extends Exception {
  private final int exitCode;

  CliException(int exitCode, String message) {
    super(message);
    this.exitCode = exitCode;
  }

  int exitCode() {
    return exitCode;
  }
}
