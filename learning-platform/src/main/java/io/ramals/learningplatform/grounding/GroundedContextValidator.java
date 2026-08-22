package io.ramals.learningplatform.grounding;

import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** Fail-closed contract policy shared by every Java context producer. */
public final class GroundedContextValidator {

  public static final int MAX_ITEMS = 64;
  public static final int MAX_SERIALIZED_BYTES = 65_536;
  public static final int MAX_VALUE_CHARACTERS = 2_048;
  private static final Set<String> SENSITIVE_FACT_TOKENS = Set.of(
      "EMAIL", "FULL_NAME", "DISPLAY_NAME", "PHONE", "POSTAL_ADDRESS", "AUTH_TOKEN",
      "SECRET", "PASSWORD", "RAW_PROMPT");

  private final ObjectMapper mapper;

  public GroundedContextValidator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void validate(GroundedContext context, Set<SourceType> requiredSources, Instant now) {
    require(context != null, "GROUNDING_MISSING");
    require(GroundedContext.CONTRACT_VERSION.equals(context.contractVersion()),
        "GROUNDING_VERSION_UNSUPPORTED");
    bounded(context.contextId(), 64, "GROUNDING_CONTEXT_ID_INVALID");
    bounded(context.learnerRef(), 64, "GROUNDING_LEARNER_REF_INVALID");
    bounded(context.retrievalPolicyVersion(), 64, "GROUNDING_POLICY_VERSION_INVALID");
    require(context.asOf() != null && context.expiresAt() != null
        && context.expiresAt().isAfter(context.asOf()) && context.expiresAt().isAfter(now),
        "GROUNDING_STALE");
    require(context.items() != null && context.items().size() <= MAX_ITEMS,
        "GROUNDING_ITEM_LIMIT_EXCEEDED");

    EnumSet<SourceType> authoritative = EnumSet.noneOf(SourceType.class);
    for (GroundedContextItem item : context.items()) {
      validateItem(item, now);
      if (item.authority() == ContextAuthority.AUTHORITATIVE_FACT) {
        authoritative.add(item.sourceType());
      }
    }
    require(authoritative.containsAll(requiredSources), "GROUNDING_REQUIRED_SOURCE_MISSING");
    try {
      require(mapper.writeValueAsBytes(context).length <= MAX_SERIALIZED_BYTES,
          "GROUNDING_SIZE_LIMIT_EXCEEDED");
    } catch (RuntimeException failure) {
      throw new GroundedContextException("GROUNDING_SERIALIZATION_FAILED", failure);
    }
  }

  private static void validateItem(GroundedContextItem item, Instant now) {
    require(item != null && item.sourceType() != null && item.authority() != null
        && item.observedAt() != null, "GROUNDING_ITEM_INVALID");
    bounded(item.evidenceId(), 64, "GROUNDING_EVIDENCE_ID_INVALID");
    bounded(item.sourceVersion(), 64, "GROUNDING_SOURCE_VERSION_INVALID");
    bounded(item.factType(), 64, "GROUNDING_FACT_TYPE_INVALID");
    String normalized = item.factType().toUpperCase(Locale.ROOT);
    require(SENSITIVE_FACT_TOKENS.stream().noneMatch(normalized::contains),
        "GROUNDING_SENSITIVE_FIELD_REJECTED");
    require(item.value() instanceof String || item.value() instanceof Number
        || item.value() instanceof Boolean, "GROUNDING_VALUE_TYPE_INVALID");
    if (item.value() instanceof String value) {
      require(value.length() <= MAX_VALUE_CHARACTERS, "GROUNDING_VALUE_LIMIT_EXCEEDED");
    }
    require(item.expiresAt() == null || item.expiresAt().isAfter(now), "GROUNDING_ITEM_STALE");
  }

  private static void bounded(String value, int max, String code) {
    require(value != null && !value.isBlank() && value.length() <= max, code);
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new GroundedContextException(code);
  }

  public static final class GroundedContextException extends RuntimeException {
    private final String code;
    public GroundedContextException(String code) { super(code); this.code = code; }
    public GroundedContextException(String code, Throwable cause) { super(code, cause); this.code = code; }
    public String code() { return code; }
  }
}
