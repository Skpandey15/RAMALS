package io.ramals.learningplatform.curriculum;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CurriculumGraphValidator {

  public void validate(CurriculumGraph graph) {
    Set<String> nodes = new HashSet<>();
    for (CurriculumGraph.SkillNode skill : graph.skills()) {
      if (!nodes.add(skill.stableCode())) {
        throw new IllegalArgumentException("Duplicate stable skill code: " + skill.stableCode());
      }
    }

    Map<String, List<String>> prerequisites = new HashMap<>();
    for (CurriculumGraph.SkillNode skill : graph.skills()) {
      for (String prerequisite : skill.prerequisiteSkillCodes()) {
        if (!nodes.contains(prerequisite)) {
          throw new IllegalArgumentException(
              "Unknown prerequisite " + prerequisite + " for " + skill.stableCode());
        }
        if (prerequisite.equals(skill.stableCode())) {
          throw new IllegalArgumentException("Skill cannot require itself: " + skill.stableCode());
        }
      }
      prerequisites.put(skill.stableCode(), skill.prerequisiteSkillCodes());
    }

    Set<String> visiting = new HashSet<>();
    Set<String> visited = new HashSet<>();
    for (String node : nodes) {
      visit(node, prerequisites, visiting, visited);
    }
  }

  private void visit(
      String node,
      Map<String, List<String>> prerequisites,
      Set<String> visiting,
      Set<String> visited) {
    if (visited.contains(node)) {
      return;
    }
    if (!visiting.add(node)) {
      throw new IllegalArgumentException("Prerequisite cycle detected at skill: " + node);
    }
    for (String prerequisite : prerequisites.getOrDefault(node, List.of())) {
      visit(prerequisite, prerequisites, visiting, visited);
    }
    visiting.remove(node);
    visited.add(node);
  }
}
