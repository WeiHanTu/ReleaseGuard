package dev.weihantu.releaseguard.cli;

final class ExitCode {
  static final int SUCCESS = 0;
  static final int USAGE = 2;
  static final int VALIDATION = 3;
  static final int INPUT = 4;

  private ExitCode() {}
}
