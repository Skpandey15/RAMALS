package io.ramals.learningplatform.grounding;

import java.time.Instant;
import java.util.List;

/** Versioned, bounded facts supplied to an agent; never an unrestricted domain dump. */
public record GroundedContext(
    String contractVersion,
    String contextId,
    String learnerRef,
    Instant asOf,
    Instant expiresAt,
    String retrievalPolicyVersion,
    List<GroundedContextItem> items) {

  public static final String CONTRACT_VERSION = "1.0";
}
