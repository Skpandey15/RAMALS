package io.ramals.learningplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.ramals.learningplatform.assessment.HypothesisDiscriminationDiagnosticSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-034 Amendment 3 (Step 3, {@code DIAGNOSTIC_SELECTION_V6}) boundaries, executable.
 *
 * <ul>
 *   <li>{@code HypothesisDiscriminationDiagnosticSelector} reaches no AI, MCP, orchestration, or
 *       Python/LLM-gateway code -- Step 3 is deterministic Java composition of already-governed
 *       collaborators only (Amendment 3 §T: "agents recommend; deterministic Java services decide").
 *   <li>It takes no M2-ADR-033 misconception-graph input and no M2-ADR-032 advisory-proposal input --
 *       the same boundary Steps 1 and 2 already hold, extended to the orchestrator that composes them.
 *   <li>The frozen working-set bound is exactly 4, and the frozen policy identifier is exactly
 *       {@code DIAGNOSTIC_SELECTION_V6} -- never derived from {@code AdaptiveDiagnosticFormProperties}
 *       or any other mutable configuration.
 * </ul>
 */
@Tag("architecture")
class DiagnosticSelectionV6ArchitectureGuardrailTests {

  private static final String BASE = "io.ramals.learningplatform";
  private static final String V6_CLASS = BASE + ".assessment.HypothesisDiscriminationDiagnosticSelector";

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  @Test
  @DisplayName("V6 reaches no AI, MCP, or orchestration code")
  void v6CannotReachAiOrMcpOrOrchestration() {
    noClasses()
        .that()
        .haveFullyQualifiedName(V6_CLASS)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(BASE + ".ai..", BASE + ".orchestration..", BASE + ".mcp..")
        .because(
            "DIAGNOSTIC_SELECTION_V6 is deterministic Java composition only -- no LLM, no LangGraph, "
                + "no MCP computes or adjusts which probe is chosen (Amendment 3 §T)")
        .check(classes);
  }

  @Test
  @DisplayName("V6 takes no M2-ADR-032 advisory-proposal input")
  void v6CannotReachAdvisoryProbeProposal() {
    noClasses()
        .that()
        .haveFullyQualifiedName(V6_CLASS)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(BASE + ".diagnosticassessment..")
        .because(
            "an accepted M2-ADR-032 advisory proposal stays within its own advisory/audit boundary "
                + "and is never eligible input to DIAGNOSTIC_SELECTION_V6, exactly as it is not for "
                + "Steps 1 or 2 -- promoting it would require a separate, explicit ADR")
        .check(classes);
  }

  @Test
  @DisplayName("V6 takes no M2-ADR-033 misconception-graph input")
  void v6CannotReachMisconceptionGraph() {
    noClasses()
        .that()
        .haveFullyQualifiedName(V6_CLASS)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(BASE + ".assessment.misconceptiongraph")
        .because(
            "no graph edge or edge-type weight participates in DIAGNOSTIC_SELECTION_V6, exactly as "
                + "none participates in HYPOTHESIS_UNCERTAINTY_V1 or HYPOTHESIS_DISCRIMINATION_V1")
        .check(classes);
  }

  @Test
  @DisplayName("V1-V4 selectors do not depend on the V6 orchestrator")
  void earlierSelectorsDoNotDependOnV6() {
    // V5 (HypothesisDrivenProbeDiagnosticSelector) is the one documented exception: V6 reuses its
    // frozen RELATIONSHIP_TYPE_PRIORITY constant and its Selection/Adjusted record types by design
    // (Amendment 3 §CRITICAL: V6's fallback must delegate to V5's own unmodified resolution). Only
    // DiagnosticService -- the transactional coordinator -- is authorized to depend on V6 itself.
    noClasses()
        .that()
        .haveSimpleName("DiagnosticFormSelector")
        .or()
        .haveSimpleName("AdaptiveDiagnosticSelector")
        .or()
        .haveSimpleName("PrerequisiteAwareDiagnosticSelector")
        .or()
        .haveSimpleName("HypothesisConfirmationDiagnosticSelector")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(V6_CLASS)
        .because(
            "V1-V4 are untouched by Amendment 3 -- only DiagnosticService orchestrates V6, and V6 "
                + "itself falls back to V5's own unmodified resolution rather than any earlier engine "
                + "depending on it")
        .check(classes);
  }

  @Test
  @DisplayName("MAX_AUTHORIZED_HYPOTHESES_V6 is frozen at exactly 4")
  void workingSetBoundIsFrozenAtFour() {
    assertThat(HypothesisDiscriminationDiagnosticSelector.MAX_AUTHORIZED_HYPOTHESES_V6).isEqualTo(4);
  }

  @Test
  @DisplayName("the frozen policy identifier is exactly DIAGNOSTIC_SELECTION_V6")
  void policyIdentifierIsExactlyTheFrozenName() {
    assertThat(HypothesisDiscriminationDiagnosticSelector.SELECTION_POLICY_VERSION)
        .isEqualTo("DIAGNOSTIC_SELECTION_V6");
  }

  @Test
  @DisplayName("V6 has its own policy identifier, distinct from every earlier version")
  void policyIdentifierIsDistinctFromEveryEarlierVersion() {
    assertThat(HypothesisDiscriminationDiagnosticSelector.SELECTION_POLICY_VERSION)
        .isNotEqualTo(io.ramals.learningplatform.assessment.DiagnosticFormSelector.SELECTION_POLICY_VERSION)
        .isNotEqualTo(io.ramals.learningplatform.assessment.AdaptiveDiagnosticSelector.SELECTION_POLICY_VERSION)
        .isNotEqualTo(io.ramals.learningplatform.assessment.PrerequisiteAwareDiagnosticSelector.SELECTION_POLICY_VERSION)
        .isNotEqualTo(io.ramals.learningplatform.assessment.HypothesisConfirmationDiagnosticSelector.SELECTION_POLICY_VERSION)
        .isNotEqualTo(io.ramals.learningplatform.assessment.HypothesisDrivenProbeDiagnosticSelector.SELECTION_POLICY_VERSION);
  }
}
