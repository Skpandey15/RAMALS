package io.ramals.learningplatform.learning;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Deterministic, versioned learning-session state machine. Only these transitions are legal:
 *
 * <pre>
 *   ACTIVE  --PAUSE-->    PAUSED
 *   ACTIVE  --COMPLETE--> COMPLETED
 *   ACTIVE  --ABANDON-->  ABANDONED
 *   PAUSED  --RESUME-->   ACTIVE
 *   PAUSED  --ABANDON-->  ABANDONED
 * </pre>
 *
 * COMPLETED and ABANDONED are terminal; any command from them is invalid.
 */
@Component
public class LearningSessionPolicy {

  public static final String POLICY_VERSION = "SESSION_POLICY_V1";

  public Optional<LearningSessionStatus> target(
      LearningSessionStatus current, LearningSessionCommand command) {
    return switch (current) {
      case ACTIVE -> switch (command) {
        case PAUSE -> Optional.of(LearningSessionStatus.PAUSED);
        case COMPLETE -> Optional.of(LearningSessionStatus.COMPLETED);
        case ABANDON -> Optional.of(LearningSessionStatus.ABANDONED);
        case RESUME -> Optional.empty();
      };
      case PAUSED -> switch (command) {
        case RESUME -> Optional.of(LearningSessionStatus.ACTIVE);
        case ABANDON -> Optional.of(LearningSessionStatus.ABANDONED);
        case PAUSE, COMPLETE -> Optional.empty();
      };
      case COMPLETED, ABANDONED -> Optional.empty();
    };
  }
}
