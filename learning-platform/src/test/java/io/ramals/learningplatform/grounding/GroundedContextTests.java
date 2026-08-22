package io.ramals.learningplatform.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GroundedContextTests {

  private static final Instant AS_OF = Instant.parse("2026-08-22T12:00:00Z");
  private final GroundedContextValidator validator =
      new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build());
  private final GroundedContextFactory factory = new GroundedContextFactory(validator);

  @Test
  void sameAuthorizedFactsProduceStableOrderedContext() {
    GroundedContextItem mastery = item("m-1", SourceType.MASTERY, "MASTERY_SCORE", "0.7200");
    GroundedContextItem policy = item("p-1", SourceType.CURRICULUM_POLICY, "THRESHOLD", "0.8000");

    GroundedContext first = create(List.of(policy, mastery));
    GroundedContext second = create(List.of(mastery, policy));

    assertThat(second.contextId()).isEqualTo(first.contextId());
    assertThat(second.items()).extracting(GroundedContextItem::sourceType)
        .containsExactly(SourceType.CURRICULUM_POLICY, SourceType.MASTERY);
  }

  @Test
  void missingAuthoritativeGroundingFailsClosed() {
    GroundedContext context = new GroundedContext("1.0", "context", "opaque", AS_OF,
        AS_OF.plusSeconds(60), "POLICY_V1",
        List.of(item("m-1", SourceType.MASTERY, "SUMMARY", "practice",
            ContextAuthority.MODEL_GENERATED_SUMMARY)));

    assertThatThrownBy(() -> validator.validate(context, Set.of(SourceType.MASTERY), AS_OF))
        .isInstanceOf(GroundedContextValidator.GroundedContextException.class)
        .hasMessage("GROUNDING_REQUIRED_SOURCE_MISSING");
  }

  @Test
  void staleSensitiveAndStructuredValuesAreRejected() {
    GroundedContext stale = new GroundedContext("1.0", "context", "opaque", AS_OF,
        AS_OF.plusSeconds(1), "POLICY_V1", List.of());
    assertThatThrownBy(() -> validator.validate(stale, Set.of(), AS_OF.plusSeconds(2)))
        .hasMessage("GROUNDING_STALE");

    assertThatThrownBy(() -> create(List.of(
        item("x", SourceType.LEARNER_EVIDENCE, "LEARNER_EMAIL", "private@example.invalid"))))
        .hasMessage("GROUNDING_SENSITIVE_FIELD_REJECTED");
    assertThatThrownBy(() -> create(List.of(new GroundedContextItem(
        "x", SourceType.MASTERY, "v1", ContextAuthority.AUTHORITATIVE_FACT,
        "DUMP", Map.of("row", "value"), AS_OF, null))))
        .hasMessage("GROUNDING_VALUE_TYPE_INVALID");
  }

  @Test
  void sharedGoldenContractDeserializesAndValidatesInJava() throws Exception {
    Path here = Path.of("").toAbsolutePath();
    Path fixture = Files.exists(here.resolve("contracts/golden/grounded-context-v1.json"))
        ? here.resolve("contracts/golden/grounded-context-v1.json")
        : here.getParent().resolve("contracts/golden/grounded-context-v1.json");
    GroundedContext context = JsonMapper.builder().findAndAddModules().build()
        .readValue(Files.readString(fixture), GroundedContext.class);

    validator.validate(context,
        Set.of(SourceType.MASTERY, SourceType.CURRICULUM_POLICY), AS_OF);
    assertThat(context.items()).hasSize(2);
  }

  private GroundedContext create(List<GroundedContextItem> items) {
    return factory.create("opaque", "POLICY_V1", AS_OF, Duration.ofMinutes(5), items, Set.of());
  }

  private static GroundedContextItem item(
      String id, SourceType source, String factType, Object value) {
    return item(id, source, factType, value, ContextAuthority.AUTHORITATIVE_FACT);
  }

  private static GroundedContextItem item(String id, SourceType source, String factType,
      Object value, ContextAuthority authority) {
    return new GroundedContextItem(id, source, "v1", authority, factType, value, AS_OF, null);
  }
}
