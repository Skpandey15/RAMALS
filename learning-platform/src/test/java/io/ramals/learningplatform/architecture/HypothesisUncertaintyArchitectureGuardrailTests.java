package io.ramals.learningplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-034 Amendment 1, Step 1 boundaries, executable.
 *
 * <ul>
 *   <li>{@code HypothesisUncertaintyCalculatorV1} is pure: no database, no AI/MCP/orchestration, no
 *       write service of any kind, no selector (§9/§22 of the implementation brief).
 *   <li>It is inert: nothing in {@code DIAGNOSTIC_SELECTION_V1}-{@code V5} depends on the {@code
 *       hypothesisuncertainty} package, and the package depends on no selector (the same "X"
 *       discipline M2-ADR-033's graph guardrails already hold).
 *   <li>It takes no M2-ADR-033 misconception-graph input.
 * </ul>
 */
@Tag("architecture")
class HypothesisUncertaintyArchitectureGuardrailTests {

  private static final String BASE = "io.ramals.learningplatform";
  private static final String PACKAGE = BASE + ".assessment.hypothesisuncertainty";
  private static final String CALCULATOR = PACKAGE + ".HypothesisUncertaintyCalculatorV1";

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  @Test
  @DisplayName("the pure calculator has no JdbcTemplate / repository dependency")
  void calculatorHasNoPersistenceDependency() {
    noClasses()
        .that()
        .haveFullyQualifiedName(CALCULATOR)
        .should()
        .dependOnClassesThat()
        .haveNameMatching(".*\\bJdbcTemplate$")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("Repository")
        .because(
            "the implementation brief §9/§22: the pure calculator must have no dependency on "
                + "JdbcTemplate or repositories -- assembly is HypothesisUncertaintyContextAssembler's "
                + "job, not the calculator's")
        .check(classes);
  }

  @Test
  @DisplayName("the pure calculator never calls a JdbcTemplate method directly")
  void calculatorNeverCallsJdbcTemplate() {
    DescribedPredicate<JavaCall<?>> aJdbcCall = new DescribedPredicate<>("any JdbcTemplate method") {
      @Override
      public boolean test(JavaCall<?> call) {
        return call.getTargetOwner().getFullName().equals("org.springframework.jdbc.core.JdbcTemplate");
      }
    };
    noClasses()
        .that()
        .haveFullyQualifiedName(CALCULATOR)
        .should()
        .callMethodWhere(aJdbcCall)
        .because("the pure calculator must have no side effects of any kind")
        .check(classes);
  }

  @Test
  @DisplayName("the package reaches no AI, MCP, or orchestration code")
  void packageCannotReachAiOrMcpOrOrchestration() {
    noClasses()
        .that()
        .resideInAPackage(PACKAGE)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(BASE + ".ai..", BASE + ".orchestration..", BASE + ".mcp..")
        .because(
            "HYPOTHESIS_UNCERTAINTY_V1 is deterministic Java only -- no LLM, no LangGraph, no MCP "
                + "computes or adjusts it (M2-ADR-034 Amendment 1 §A/§N)")
        .check(classes);
  }

  @Test
  @DisplayName("the package writes no learner state, mastery, evidence, or progression")
  void packageCannotWriteLearnerStateOrMasteryOrEvidenceOrProgression() {
    noClasses()
        .that()
        .resideInAPackage(PACKAGE)
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".mastery.MasteryRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".mastery.MasteryService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".evidence.EvidenceRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".evidence.EvidenceService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".learning.ProgressionRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".learning.ProgressionService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.MisconceptionConfidenceRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.MisconceptionConfidenceService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticSubmissionService")
        .because(
            "M2-ADR-034 Amendment 1 §D/§N: the hypothesis-uncertainty distribution is a separate "
                + "stream that never merges into mastery, evidence, progression, or G3")
        .check(classes);
  }

  @Test
  @DisplayName("the package does not reach any DIAGNOSTIC_SELECTION selector or probe execution")
  void packageCannotReachSelectorsOrProbeExecution() {
    // HypothesisDrivenProbeDiagnosticSelector is the one documented exception: Amendment 1 §H
    // mandates reusing its frozen RELATIONSHIP_TYPE_PRIORITY constant verbatim rather than
    // redefining the same order a second time. packageOnlyReadsThePriorityConstantOfV5, below,
    // proves that reuse is field-access-only -- no method of V5 is ever called from this package.
    noClasses()
        .that()
        .resideInAPackage(PACKAGE)
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticFormSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.AdaptiveDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.PrerequisiteAwareDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.HypothesisConfirmationDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.ProbeRelationshipResolver")
        .because(
            "M2-ADR-034 Amendment 1 §M: Step 1 is inert -- it computes a belief state, it does not "
                + "decide the next probe, and no DIAGNOSTIC_SELECTION_V1-V5 code is touched")
        .check(classes);
  }

  @Test
  @DisplayName("the package only reads V5's frozen priority constant -- never calls any of its methods")
  void packageOnlyReadsThePriorityConstantOfV5() {
    DescribedPredicate<JavaCall<?>> aV5MethodCall = new DescribedPredicate<>(
        "a method of HypothesisDrivenProbeDiagnosticSelector") {
      @Override
      public boolean test(JavaCall<?> call) {
        return call.getTargetOwner().getFullName()
            .equals(BASE + ".assessment.HypothesisDrivenProbeDiagnosticSelector");
      }
    };
    noClasses()
        .that()
        .resideInAPackage(PACKAGE)
        .should()
        .callMethodWhere(aV5MethodCall)
        .because(
            "Amendment 1 §H's reuse of RELATIONSHIP_TYPE_PRIORITY is a constant read, not behavioural "
                + "coupling -- calling adjustForHypothesisProbe() or any other V5 method from this "
                + "package would be exactly the runtime wiring Step 1 does not authorize")
        .check(classes);
  }

  @Test
  @DisplayName("no DIAGNOSTIC_SELECTION selector depends on the hypothesis-uncertainty package")
  void selectorsDoNotDependOnHypothesisUncertainty() {
    noClasses()
        .that()
        .haveSimpleName("DiagnosticService")
        .or()
        .haveSimpleName("DiagnosticSubmissionService")
        .or()
        .haveSimpleName("DiagnosticFormSelector")
        .or()
        .haveSimpleName("AdaptiveDiagnosticSelector")
        .or()
        .haveSimpleName("PrerequisiteAwareDiagnosticSelector")
        .or()
        .haveSimpleName("HypothesisConfirmationDiagnosticSelector")
        .or()
        .haveSimpleName("HypothesisDrivenProbeDiagnosticSelector")
        .or()
        .haveSimpleName("ProbeRelationshipResolver")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(PACKAGE)
        .because(
            "the deliberate 'X': no selection engine reads HYPOTHESIS_UNCERTAINTY_V1 in this "
                + "milestone (M2-ADR-034 Amendment 1 §M)")
        .check(classes);
  }

  @Test
  @DisplayName("the package takes no M2-ADR-033 misconception-graph input")
  void packageCannotReachMisconceptionGraph() {
    noClasses()
        .that()
        .resideInAPackage(PACKAGE)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(BASE + ".assessment.misconceptiongraph")
        .because(
            "M2-ADR-034 Amendment 1 §G: MisconceptionGraphQueryService -- X -- "
                + "HYPOTHESIS_UNCERTAINTY_V1; no graph edge, and no edge-type weight, participates")
        .check(classes);
  }
}
