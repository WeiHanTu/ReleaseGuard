package dev.weihantu.releaseguard.operator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.weihantu.releaseguard.api.v1alpha1.ReleasePlanStatus.Phase;
import org.junit.jupiter.api.Test;

class ReleasePhaseMachineTest {
  private final ReleasePhaseMachine machine = new ReleasePhaseMachine();

  @Test
  void permitsDocumentedTransitionsAndIdempotentObservation() {
    assertTrue(machine.permits(null, Phase.Validating));
    assertTrue(machine.permits(Phase.Validating, Phase.Bootstrapping));
    assertTrue(machine.permits(Phase.Validating, Phase.Progressing));
    assertTrue(machine.permits(Phase.Progressing, Phase.RollingBack));
    assertTrue(machine.permits(Phase.Paused, Phase.Progressing));
    assertTrue(machine.permits(Phase.RollingBack, Phase.RolledBack));
    assertTrue(machine.permits(Phase.Succeeded, Phase.Validating));
    assertTrue(machine.permits(Phase.Progressing, Phase.Progressing));
  }

  @Test
  void rejectsUnsafeOrRegressingTransitions() {
    assertFalse(machine.permits(null, Phase.Succeeded));
    assertFalse(machine.permits(Phase.Progressing, Phase.Bootstrapping));
    assertFalse(machine.permits(Phase.Succeeded, Phase.Progressing));
    assertFalse(machine.permits(Phase.RolledBack, Phase.RollingBack));
    assertThrows(
        IllegalStateException.class,
        () -> machine.requireTransition(Phase.Progressing, Phase.Bootstrapping));
    assertDoesNotThrow(() -> machine.requireTransition(Phase.Failed, Phase.Validating));
  }
}
