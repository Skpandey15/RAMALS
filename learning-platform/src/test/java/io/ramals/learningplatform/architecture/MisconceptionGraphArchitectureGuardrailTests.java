package io.ramals.learningplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-033 step 1 boundaries, executable.
 *
 * <ul>
 *   <li>The misconception graph is authored curriculum knowledge, not learner state and not AI
 *       state: its classes reach no mastery / evidence / progression / diagnostic-confidence
 *       writer (§4), and nothing in the AI plane, orchestration, or MCP can author or mutate it
 *       (§14/§17).
 *   <li>It has no diagnostic runtime integration: the frozen {@code DIAGNOSTIC_SELECTION_V1}-
 *       {@code V5} selectors, {@code DiagnosticService}, {@code DiagnosticSubmissionService}, and
 *       {@code ProbeRelationshipResolver} do not depend on the graph package -- the deliberate
 *       "X" between the graph and selection (§6/§15).
 * </ul>
 */
@Tag("architecture")
class MisconceptionGraphArchitectureGuardrailTests {

  private static final String BASE = "io.ramals.learningplatform";
  private static final String GRAPH_PACKAGE = BASE + ".assessment.misconceptiongraph";

  private final JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  @Test
  @DisplayName("§4 -- the misconception graph writes no learner state and no confidence")
  void graphCannotReachLearnerStateOrConfidenceWriters() {
    noClasses()
        .that()
        .resideInAPackage(GRAPH_PACKAGE)
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
        .haveFullyQualifiedName(BASE + ".assessment.MisconceptionEvidenceCaptureService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.MisconceptionConfidenceRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.MisconceptionConfidenceService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticConfidenceRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticConfidenceService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.DiagnosticSubmissionService")
        .because(
            "M2-ADR-033 §4: an edge is authored knowledge about misconceptions in the abstract -- "
                + "it never carries or derives a learner-scoped probability or confidence, and "
                + "(learner_id, misconception_id) confidence stays exactly G3")
        .check(classes);
  }

  @Test
  @DisplayName("§6/§15 -- the graph does not reach DIAGNOSTIC_SELECTION or probe resolution")
  void graphCannotReachDiagnosticSelectionOrProbeResolution() {
    noClasses()
        .that()
        .resideInAPackage(GRAPH_PACKAGE)
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
        .haveFullyQualifiedName(BASE + ".assessment.HypothesisDrivenProbeDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.ProbeRelationshipResolver")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(BASE + ".assessment.ProbeRelationshipService")
        .because(
            "M2-ADR-033 §6/§15: step 1 is representation only -- no selection engine consumes the "
                + "graph, and the graph consumes no selector")
        .check(classes);
  }

  @Test
  @DisplayName("§6/§15 -- DIAGNOSTIC_SELECTION and DiagnosticService do not depend on the graph")
  void diagnosticSelectionDoesNotDependOnTheGraph() {
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
        .resideInAPackage(GRAPH_PACKAGE)
        .because(
            "M2-ADR-033 §6/§15: the deliberate 'X' -- no selection engine reads the misconception "
                + "relationship graph in this milestone")
        .check(classes);
  }

  @Test
  @DisplayName("§14/§17 -- the AI plane, orchestration, and MCP cannot author the graph")
  void aiPlaneCannotAuthorMisconceptionRelationships() {
    noClasses()
        .that()
        .resideInAnyPackage(BASE + ".ai..", BASE + ".orchestration..", BASE + ".mcp..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(GRAPH_PACKAGE)
        .because(
            "M2-ADR-033 §14/§17: only deterministic Java services author relationship edges; an "
                + "LLM / LangGraph / MCP / AIProposalEnvelope / DiagnosticProbeProposal never "
                + "writes or mutates one")
        .check(classes);
  }
}
