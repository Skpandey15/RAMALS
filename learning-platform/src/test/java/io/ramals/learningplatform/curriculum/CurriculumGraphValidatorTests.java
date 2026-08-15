package io.ramals.learningplatform.curriculum;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurriculumGraphValidatorTests {

  private final CurriculumGraphValidator validator = new CurriculumGraphValidator();

  @Test
  void acceptsDeterministicAcyclicGraph() {
    CurriculumGraph graph = graph(
        skill("A", List.of()),
        skill("B", List.of("A")),
        skill("C", List.of("B")));
    assertThatNoException().isThrownBy(() -> validator.validate(graph));
  }

  @Test
  void rejectsCycleAndUnknownPrerequisite() {
    assertThatThrownBy(() -> validator.validate(graph(
        skill("A", List.of("B")), skill("B", List.of("A")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
    assertThatThrownBy(() -> validator.validate(graph(skill("A", List.of("MISSING")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown prerequisite");
  }

  private CurriculumGraph graph(CurriculumGraph.SkillNode... skills) {
    return new CurriculumGraph(UUID.randomUUID(), "KAFKA", "v1", "PUBLISHED", List.of(skills));
  }

  private CurriculumGraph.SkillNode skill(String code, List<String> prerequisites) {
    return new CurriculumGraph.SkillNode(
        UUID.randomUUID(), code, code, "Description", "FOUNDATIONAL",
        new BigDecimal("0.8000"), 20, new BigDecimal("0.8000"),
        new BigDecimal("0.7500"), 5, List.of("QUIZ"), List.of("EASY"), 1,
        List.of(new CurriculumGraph.Objective(code + "_OBJECTIVE", "Objective", true, 1)),
        prerequisites);
  }
}
