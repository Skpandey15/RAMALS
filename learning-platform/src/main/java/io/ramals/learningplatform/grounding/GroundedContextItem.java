package io.ramals.learningplatform.grounding;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** One scalar fact with stable source identity, version, authority, and freshness metadata. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroundedContextItem(
    String evidenceId,
    SourceType sourceType,
    String sourceVersion,
    ContextAuthority authority,
    String factType,
    Object value,
    Instant observedAt,
    Instant expiresAt) {

  public enum ContextAuthority { AUTHORITATIVE_FACT, MODEL_GENERATED_SUMMARY }

  public enum SourceType {
    LEARNER_EVIDENCE, MASTERY, SKILL_GRAPH, ASSESSMENT, APPROVED_CONTENT,
    CURRICULUM_POLICY, DOMAIN_POLICY
  }
}
