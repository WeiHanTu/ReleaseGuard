package dev.weihantu.releaseguard.operator;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus.Phase;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

public final class ReleasePhaseMachine {
  private static final Map<Phase, EnumSet<Phase>> ALLOWED = allowedTransitions();

  public boolean permits(Phase current, Phase next) {
    if (current == next) {
      return true;
    }
    if (current == null) {
      return next == Phase.Validating;
    }
    return ALLOWED.getOrDefault(current, EnumSet.noneOf(Phase.class)).contains(next);
  }

  public void requireTransition(Phase current, Phase next) {
    if (!permits(current, next)) {
      throw new IllegalStateException(
          "forbidden release phase transition: " + current + " -> " + next);
    }
  }

  private static Map<Phase, EnumSet<Phase>> allowedTransitions() {
    Map<Phase, EnumSet<Phase>> transitions = new EnumMap<>(Phase.class);
    transitions.put(Phase.Pending, EnumSet.of(Phase.Validating));
    transitions.put(
        Phase.Validating,
        EnumSet.of(Phase.Bootstrapping, Phase.Progressing, Phase.Failed, Phase.Deleting));
    transitions.put(Phase.Bootstrapping, EnumSet.of(Phase.Succeeded, Phase.Failed, Phase.Deleting));
    transitions.put(
        Phase.Progressing,
        EnumSet.of(Phase.Paused, Phase.Succeeded, Phase.RollingBack, Phase.Deleting));
    transitions.put(Phase.Paused, EnumSet.of(Phase.Progressing, Phase.RollingBack, Phase.Deleting));
    transitions.put(Phase.RollingBack, EnumSet.of(Phase.RolledBack, Phase.Failed, Phase.Deleting));
    transitions.put(Phase.Succeeded, EnumSet.of(Phase.Validating, Phase.Deleting));
    transitions.put(Phase.RolledBack, EnumSet.of(Phase.Validating, Phase.Deleting));
    transitions.put(Phase.Failed, EnumSet.of(Phase.Validating, Phase.Deleting));
    transitions.put(Phase.Deleting, EnumSet.noneOf(Phase.class));
    return Map.copyOf(transitions);
  }
}
