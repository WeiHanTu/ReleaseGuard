package dev.weihantu.releaseguard.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.Operator;
import java.io.IOException;

public final class ReleaseGuardOperator {
  private ReleaseGuardOperator() {}

  public static void main(String[] args) throws IOException {
    OperatorHealthServer healthServer =
        OperatorHealthServer.start(OperatorHealthServer.portFromEnvironment(System.getenv()));
    Runtime.getRuntime()
        .addShutdownHook(new Thread(healthServer::close, "releaseguard-health-shutdown"));

    try {
      LeaderElectionPolicy leaderElectionPolicy = LeaderElectionPolicy.fromEnvironment();
      Operator operator = createOperator(null, leaderElectionPolicy);
      ReleaseEventReconciler eventReconciler = new ReleaseEventReconciler();
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(eventReconciler::close, "releaseguard-event-publisher-shutdown"));

      operator.register(new ReleasePlanReconciler());
      operator.register(new ReleaseActionReconciler());
      operator.register(eventReconciler);
      operator.installShutdownHook();
      operator.start();
      healthServer.markReady();
    } catch (RuntimeException | Error exception) {
      healthServer.close();
      throw exception;
    }
  }

  static Operator createOperator(
      KubernetesClient client, LeaderElectionPolicy leaderElectionPolicy) {
    return new Operator(
        overrider -> {
          if (client != null) {
            overrider.withKubernetesClient(client).withCloseClientOnStop(false);
          }
          leaderElectionPolicy
              .toConfiguration()
              .ifPresent(overrider::withLeaderElectionConfiguration);
        });
  }
}
