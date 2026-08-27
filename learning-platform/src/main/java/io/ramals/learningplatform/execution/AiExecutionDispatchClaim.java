package io.ramals.learningplatform.execution;

import java.util.UUID;

/** Result of attempting to own the first provider dispatch for one durable AI commission. */
public record AiExecutionDispatchClaim(
    boolean acquired, UUID ownerToken, long fence, String requestDigest, DispatchState state) {

  public enum DispatchState {
    AVAILABLE,
    DISPATCH_OWNED,
    IN_FLIGHT,
    LEGACY_INDETERMINATE,
    TERMINAL,
    ABSENT
  }

  public AiExecutionDispatchClaim {
    if (state == null) {
      throw new IllegalArgumentException("dispatch state is required");
    }
    if (acquired
        && (ownerToken == null
            || fence < 1
            || requestDigest == null
            || !requestDigest.matches("[0-9a-f]{64}")
            || state != DispatchState.DISPATCH_OWNED)) {
      throw new IllegalArgumentException("an acquired dispatch claim requires its token and fence");
    }
    if (!acquired && (ownerToken != null || fence != 0 || requestDigest != null)) {
      throw new IllegalArgumentException(
          "an unavailable dispatch claim cannot expose an owner token or fence");
    }
  }

  public static AiExecutionDispatchClaim acquired(
      UUID ownerToken, long fence, String requestDigest) {
    return new AiExecutionDispatchClaim(
        true, ownerToken, fence, requestDigest, DispatchState.DISPATCH_OWNED);
  }

  public static AiExecutionDispatchClaim unavailable(DispatchState state) {
    return new AiExecutionDispatchClaim(false, null, 0, null, state);
  }
}
