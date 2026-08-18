package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateCanonicalizerTests {

  private static final UUID VERSION = UUID.fromString("01900000-0000-7000-8000-000000000402");

  @Test
  void canonicalFormIsGoldenAndExcludesMapInsertionOrder() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("stem", "What is a topic?");
    first.put("difficulty", "FOUNDATIONAL");
    first.put("options", List.of("A", "B", "C"));
    first.put("answerKey", List.of("A"));
    first.put("assessmentVersionId", VERSION.toString());
    first.put("itemCode", "AI_CANDIDATE_1");
    first.put("itemType", "SINGLE_CHOICE");
    first.put("objectiveCode", "TOPIC_DEFINE");
    first.put("skillCode", "KAFKA_TOPIC");

    Map<String, Object> reordered = new LinkedHashMap<>();
    first.forEach((key, value) -> reordered.put(key, value));
    reordered.remove("stem");
    reordered.put("stem", "What is a topic?");

    String canonical = new String(CandidateCanonicalizer.canonicalBytes(first), StandardCharsets.UTF_8);
    assertThat(canonical).isEqualTo(
        "{\"answerKey\":[\"A\"],\"assessmentVersionId\":\"01900000-0000-7000-8000-000000000402\","
            + "\"difficulty\":\"FOUNDATIONAL\",\"itemCode\":\"AI_CANDIDATE_1\","
            + "\"itemType\":\"SINGLE_CHOICE\",\"objectiveCode\":\"TOPIC_DEFINE\","
            + "\"options\":[\"A\",\"B\",\"C\"],\"skillCode\":\"KAFKA_TOPIC\","
            + "\"stem\":\"What is a topic?\"}");
    assertThat(CandidateCanonicalizer.canonicalBytes(first))
        .isEqualTo(CandidateCanonicalizer.canonicalBytes(reordered));
  }

  @Test
  void approvalRelevantChangeChangesDigest() {
    Map<String, Object> original = Map.of("stem", "Question A", "options", List.of("A", "B"));
    Map<String, Object> changed = Map.of("stem", "Question B", "options", List.of("A", "B"));

    assertThat(CandidateCanonicalizer.sha256(original))
        .isNotEqualTo(CandidateCanonicalizer.sha256(changed));
  }
}
