package dev.weihantu.releaseguard.operator;

import io.javaoperatorsdk.operator.Operator;

public final class ReleaseGuardOperator {
  private ReleaseGuardOperator() {}

  public static void main(String[] args) {
    Operator operator = new Operator();
    operator.register(new ReleasePlanReconciler());
    operator.installShutdownHook();
    operator.start();
  }
}
